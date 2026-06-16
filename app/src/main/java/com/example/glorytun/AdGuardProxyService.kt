package com.example.glorytun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AdGuardProxyService : VpnService() {

    private data class SelectedNetwork(
        val network: Network,
        val kind: NetworkKind
    )

    private enum class NetworkKind { WIFI, SIM }

    private data class ProxyTarget(
        val host: String,
        val port: Int
    )

    private lateinit var connectivityManager: ConnectivityManager

    private val isRunning = AtomicBoolean(false)
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val statsHandler = Handler(Looper.getMainLooper())
    private val wifiNetwork = AtomicReference<Network?>(null)
    private val simNetwork = AtomicReference<Network?>(null)
    private val wifiTxBytes = AtomicLong(0L)
    private val wifiRxBytes = AtomicLong(0L)
    private val simTxBytes = AtomicLong(0L)
    private val simRxBytes = AtomicLong(0L)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var lastBondingPickWifi = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork.set(network)
            Log.i(TAG, "WiFi proxy path available: ${network.networkHandle}")
        }

        override fun onLost(network: Network) {
            wifiNetwork.compareAndSet(network, null)
            Log.i(TAG, "WiFi proxy path lost: ${network.networkHandle}")
        }
    }

    private val simCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            simNetwork.set(network)
            Log.i(TAG, "SIM proxy path available: ${network.networkHandle}")
        }

        override fun onLost(network: Network) {
            simNetwork.compareAndSet(network, null)
            Log.i(TAG, "SIM proxy path lost: ${network.networkHandle}")
        }
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            broadcastTrafficStats()
            statsHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            GlorytunConstants.ACTION_PROXY_START -> startProxy()
            GlorytunConstants.ACTION_PROXY_STOP -> stopProxy()
            GlorytunConstants.ACTION_PROXY_QUERY_STATE -> {
                if (isRunning.get()) sendProxyState() else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy() {
        if (!isRunning.compareAndSet(false, true)) return

        val port = proxyPort()
        startForegroundNotification(port)
        registerNetworkCallbacks()
        resetStats()
        sendState("ProxyConnecting")

        acceptExecutor.execute {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port))
                    serverSocket = socket
                    Log.i(TAG, "AdGuard proxy listening on $LOOPBACK_HOST:$port")
                    sendState("ProxyConnected")
                    statsHandler.post(statsRunnable)

                    while (isRunning.get()) {
                        val client = try {
                            socket.accept()
                        } catch (e: IOException) {
                            if (isRunning.get()) Log.w(TAG, "Proxy accept failed", e)
                            break
                        }
                        clientExecutor.execute { handleClient(client) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy start failed", e)
                isRunning.set(false)
                sendState("Disconnected")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                serverSocket = null
            }
        }
    }

    private fun stopProxy() {
        if (!isRunning.getAndSet(false)) {
            sendState("Disconnected")
            stopSelf()
            return
        }
        closeQuietly(serverSocket)
        serverSocket = null
        statsHandler.removeCallbacks(statsRunnable)
        unregisterNetworkCallbacks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        sendState("Disconnected")
        stopSelf()
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = SOCKET_TIMEOUT_MS

        try {
            client.use {
                val clientIn = PushbackInputStream(client.getInputStream(), 1)
                val firstByte = clientIn.read()
                if (firstByte < 0) return
                clientIn.unread(firstByte)

                if (firstByte == SOCKS5_VERSION) {
                    handleSocks5(client, clientIn)
                } else {
                    handleHttpConnect(client, clientIn)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Proxy client closed: ${e.message}")
        }
    }

    private fun handleSocks5(client: Socket, clientIn: PushbackInputStream) {
        val version = readRequiredByte(clientIn)
        if (version != SOCKS5_VERSION) return

        val methods = readRequiredByte(clientIn)
        repeat(methods) { readRequiredByte(clientIn) }
        client.getOutputStream().write(byteArrayOf(SOCKS5_VERSION.toByte(), 0x00))

        val requestVersion = readRequiredByte(clientIn)
        val command = readRequiredByte(clientIn)
        readRequiredByte(clientIn)
        val addressType = readRequiredByte(clientIn)
        if (requestVersion != SOCKS5_VERSION || command != SOCKS5_CONNECT) {
            sendSocks5Reply(client.getOutputStream(), SOCKS5_COMMAND_NOT_SUPPORTED)
            return
        }

        val host = when (addressType) {
            SOCKS5_IPV4 -> {
                val bytes = readRequiredBytes(clientIn, 4)
                InetAddress.getByAddress(bytes).hostAddress ?: return
            }
            SOCKS5_DOMAIN -> {
                val length = readRequiredByte(clientIn)
                String(readRequiredBytes(clientIn, length), StandardCharsets.UTF_8)
            }
            SOCKS5_IPV6 -> {
                val bytes = readRequiredBytes(clientIn, 16)
                InetAddress.getByAddress(bytes).hostAddress ?: return
            }
            else -> {
                sendSocks5Reply(client.getOutputStream(), SOCKS5_ADDRESS_NOT_SUPPORTED)
                return
            }
        }
        val port = readPort(clientIn)

        val selection = selectNetwork()
        if (selection == null) {
            sendSocks5Reply(client.getOutputStream(), SOCKS5_NETWORK_UNREACHABLE)
            return
        }

        connectOutbound(selection, host, port).use { remote ->
            sendSocks5Reply(client.getOutputStream(), SOCKS5_SUCCEEDED)
            relay(clientIn, client.getOutputStream(), remote, selection.kind)
        }
    }

    private fun handleHttpConnect(client: Socket, clientIn: PushbackInputStream) {
        val header = readHttpHeader(clientIn)
        val firstLine = header.lineSequence().firstOrNull() ?: return
        val parts = firstLine.split(' ')
        if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
            client.getOutputStream().write(
                "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            return
        }

        val target = parseAuthority(parts[1]) ?: run {
            client.getOutputStream().write(
                "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            return
        }

        val selection = selectNetwork()
        if (selection == null) {
            client.getOutputStream().write(
                "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            return
        }

        connectOutbound(selection, target.host, target.port).use { remote ->
            client.getOutputStream().write(
                "HTTP/1.1 200 Connection Established\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            relay(clientIn, client.getOutputStream(), remote, selection.kind)
        }
    }

    private fun connectOutbound(selection: SelectedNetwork, host: String, port: Int): Socket {
        return try {
            val socket = createProtectedSocket()
            selection.network.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val address = resolveHost(selection.network, host)
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket
        } catch (e: IOException) {
            Log.w(TAG, "Network-bound connect failed; falling back to default socket: ${e.message}")
            connectDefaultOutbound(host, port)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Network-bound socket failed; falling back to default socket: ${e.message}")
            connectDefaultOutbound(host, port)
        }
    }

    private fun connectDefaultOutbound(host: String, port: Int): Socket {
        val socket = createProtectedSocket()
        socket.tcpNoDelay = true
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return socket
    }

    private fun createProtectedSocket(): Socket {
        val socket = Socket()
        if (!protect(socket)) {
            Log.w(TAG, "VpnService.protect() returned false for outbound socket")
        }
        return socket
    }

    private fun resolveHost(network: Network, host: String): InetAddress {
        return runCatching { InetAddress.getByName(host) }
            .getOrNull()
            ?.takeIf { host.any { char -> char.isDigit() } && !host.any { char -> char.isLetter() } }
            ?: network.getAllByName(host).first()
    }

    private fun relay(
        clientIn: InputStream,
        clientOut: OutputStream,
        remote: Socket,
        kind: NetworkKind
    ) {
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()

        val upload = clientExecutor.submit {
            copyAndCount(clientIn, remoteOut, kind, upload = true)
            closeQuietly(remote)
        }
        val download = clientExecutor.submit {
            copyAndCount(remoteIn, clientOut, kind, upload = false)
            closeQuietly(remote)
        }

        runCatching { upload.get() }
        runCatching { download.get() }
    }

    private fun copyAndCount(input: InputStream, output: OutputStream, kind: NetworkKind, upload: Boolean) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (read < 0) break
            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (_: IOException) {
                break
            }
            addTraffic(kind, upload, read.toLong())
        }
    }

    private fun addTraffic(kind: NetworkKind, upload: Boolean, bytes: Long) {
        when (kind) {
            NetworkKind.WIFI -> if (upload) wifiTxBytes.addAndGet(bytes) else wifiRxBytes.addAndGet(bytes)
            NetworkKind.SIM -> if (upload) simTxBytes.addAndGet(bytes) else simRxBytes.addAndGet(bytes)
        }
    }

    private fun selectNetwork(): SelectedNetwork? {
        val wifi = wifiNetwork.get()
        val sim = simNetwork.get()
        val mode = getSharedPreferences(NetworkProtocolFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(NetworkProtocolFragment.KEY_MODE, NetworkProtocolFragment.MODE_BONDING)

        return when (mode) {
            NetworkProtocolFragment.MODE_WIFI_FIRST ->
                wifi?.let { SelectedNetwork(it, NetworkKind.WIFI) }
                    ?: sim?.let { SelectedNetwork(it, NetworkKind.SIM) }
            NetworkProtocolFragment.MODE_SIM_FIRST ->
                sim?.let { SelectedNetwork(it, NetworkKind.SIM) }
                    ?: wifi?.let { SelectedNetwork(it, NetworkKind.WIFI) }
            else -> {
                if (wifi != null && sim != null) {
                    lastBondingPickWifi = !lastBondingPickWifi
                    if (lastBondingPickWifi) SelectedNetwork(wifi, NetworkKind.WIFI)
                    else SelectedNetwork(sim, NetworkKind.SIM)
                } else {
                    wifi?.let { SelectedNetwork(it, NetworkKind.WIFI) }
                        ?: sim?.let { SelectedNetwork(it, NetworkKind.SIM) }
                }
            }
        }
    }

    private fun registerNetworkCallbacks() {
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val simRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.requestNetwork(wifiRequest, wifiCallback)
        connectivityManager.requestNetwork(simRequest, simCallback)
    }

    private fun unregisterNetworkCallbacks() {
        try { connectivityManager.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(simCallback) } catch (_: Exception) {}
        wifiNetwork.set(null)
        simNetwork.set(null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GlorytunConstants.CHANNEL_ID,
                GlorytunConstants.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AdGuard 互換プロキシの接続状態" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(port: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, GlorytunConstants.CHANNEL_ID)
            .setContentTitle("BondVPN Proxy")
            .setContentText("$LOOPBACK_HOST:$port で待受中")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(GlorytunConstants.NOTIFICATION_ID + 1, notification)
    }

    private fun sendProxyState() {
        sendState(if (isRunning.get()) "ProxyConnected" else "Disconnected")
    }

    private fun sendState(state: String) {
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_STATE).apply {
            setPackage(packageName)
            putExtra("state", state)
        })
    }

    private fun broadcastTrafficStats() {
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_TRAFFIC_STATS).apply {
            setPackage(packageName)
            putExtra("wifi_tx_bytes", wifiTxBytes.get())
            putExtra("wifi_rx_bytes", wifiRxBytes.get())
            putExtra("wifi_active", wifiNetwork.get() != null)
            putExtra("sim_tx_bytes", simTxBytes.get())
            putExtra("sim_rx_bytes", simRxBytes.get())
            putExtra("sim_active", simNetwork.get() != null)
            putExtra("stats_source", "proxy")
            putExtra("daily_wifi_kb", (wifiTxBytes.get() + wifiRxBytes.get()) / 1024.0)
            putExtra("daily_sim_kb", (simTxBytes.get() + simRxBytes.get()) / 1024.0)
            putExtra("wifi_throttled", false)
            putExtra("sim_throttled", false)
        })
    }

    private fun resetStats() {
        wifiTxBytes.set(0L)
        wifiRxBytes.set(0L)
        simTxBytes.set(0L)
        simRxBytes.set(0L)
    }

    private fun proxyPort(): Int {
        return getSharedPreferences(GlorytunConstants.PREFS_PROXY, Context.MODE_PRIVATE)
            .getInt(GlorytunConstants.KEY_ADGUARD_PROXY_PORT, GlorytunConstants.DEFAULT_ADGUARD_PROXY_PORT)
            .coerceIn(1024, 65535)
    }

    private fun readHttpHeader(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HTTP_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            matched = if (value == HTTP_HEADER_END[matched].toInt()) matched + 1 else 0
            if (matched == HTTP_HEADER_END.size) break
        }
        return out.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun parseAuthority(authority: String): ProxyTarget? {
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end <= 0 || end + 2 > authority.length || authority[end + 1] != ':') return null
            host = authority.substring(1, end)
            portText = authority.substring(end + 2)
        } else {
            val split = authority.lastIndexOf(':')
            if (split <= 0 || split == authority.lastIndex - 1) return null
            host = authority.substring(0, split)
            portText = authority.substring(split + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ProxyTarget(host, port)
    }

    private fun readPort(input: InputStream): Int {
        val high = readRequiredByte(input)
        val low = readRequiredByte(input)
        return (high shl 8) or low
    }

    private fun readRequiredByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw IOException("Unexpected EOF")
        return value
    }

    private fun readRequiredBytes(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw IOException("Unexpected EOF")
            offset += read
        }
        return bytes
    }

    private fun sendSocks5Reply(output: OutputStream, status: Int) {
        output.write(byteArrayOf(SOCKS5_VERSION.toByte(), status.toByte(), 0x00, SOCKS5_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
    }

    private fun closeQuietly(closeable: Closeable?) {
        try { closeable?.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopProxy()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdGuardProxyService"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024

        private const val SOCKS5_VERSION = 0x05
        private const val SOCKS5_CONNECT = 0x01
        private const val SOCKS5_SUCCEEDED = 0x00
        private const val SOCKS5_NETWORK_UNREACHABLE = 0x03
        private const val SOCKS5_COMMAND_NOT_SUPPORTED = 0x07
        private const val SOCKS5_ADDRESS_NOT_SUPPORTED = 0x08
        private const val SOCKS5_IPV4 = 0x01
        private const val SOCKS5_DOMAIN = 0x03
        private const val SOCKS5_IPV6 = 0x04
        private val HTTP_HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}
