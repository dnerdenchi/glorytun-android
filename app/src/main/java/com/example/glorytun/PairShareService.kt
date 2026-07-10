package com.example.glorytun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground, Wi-Fi-only Pair & Share service.
 *
 * It advertises a LAN endpoint with Android NSD, accepts manual-code pairing,
 * and only relays traffic after a user has approved the peer and BondVPN's VPN
 * service is connected on the sharing device.
 */
class PairShareService : Service() {
    private val repository by lazy { PairShareRepository(this) }
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val nsdManager by lazy { getSystemService(NsdManager::class.java) }

    private val running = AtomicBoolean(false)
    private val lanLock = Any()
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val discoveries = ConcurrentHashMap<String, PairShareDiscovery>()
    private val serviceIdsByName = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<String, PendingPairing>()
    private val hostSessions = ConcurrentHashMap.newKeySet<PairShareHostSession>()
    private val pairingAttempts = ConcurrentHashMap<String, PairingAttemptWindow>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var wifiNetwork: Network? = null

    @Volatile
    private var registeredService: NsdServiceInfo? = null

    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var vpnConnected = false

    @Volatile
    private var serviceStatus = "Pair & Share を開始しています"

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(GlorytunConstants.EXTRA_STATE_SOURCE) != GlorytunConstants.STATE_SOURCE_VPN) {
                return
            }
            vpnConnected = intent.getStringExtra(GlorytunConstants.EXTRA_STATE) == ConnectionStates.VPN_CONNECTED
            if (!vpnConnected) closeHostVpnRelays()
            publishState()
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleLanRefresh()

        override fun onLost(network: Network) {
            if (network == wifiNetwork) scheduleLanRefresh()
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            registeredService = null
            serviceStatus = "LAN 共有の公開に失敗しました"
            publishState()
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Pair & Share NSD 登録を解除できませんでした: $errorCode")
        }

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registeredService = serviceInfo
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            vpnStateReceiver,
            IntentFilter(GlorytunConstants.ACTION_VPN_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        connectivityManager.registerNetworkCallback(PairShareNetwork.wifiNetworkRequest(), wifiCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startPairShare()
            ACTION_STOP -> stopPairShare()
            ACTION_PAIR -> requestPair(
                PairShareDiscovery(
                    id = intent.getStringExtra(EXTRA_PEER_ID).orEmpty(),
                    displayName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty(),
                    host = intent.getStringExtra(EXTRA_PEER_HOST).orEmpty(),
                    port = intent.getIntExtra(EXTRA_PEER_PORT, 0),
                    lastSeenMillis = System.currentTimeMillis(),
                ),
                intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty(),
            )
            ACTION_ACCEPT_PAIR -> resolvePending(intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty(), true)
            ACTION_REJECT_PAIR -> resolvePending(intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty(), false)
            ACTION_REGENERATE_CODE -> {
                repository.regeneratePairingCode()
                publishState()
            }
            ACTION_QUERY -> {
                if (!repository.isSharingEnabled()) closeHostSessions()
                else if (!vpnConnected) closeHostVpnRelays()
                publishState()
            }
        }
        return if (running.get()) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPairShare() {
        if (!running.compareAndSet(false, true)) {
            publishState()
            return
        }
        repository.setEnabled(true)
        startForegroundNotification("同じ Wi-Fi 上の端末を探しています")
        serviceStatus = "同じ Wi-Fi 上の端末を探しています"
        publishState()
        controlExecutor.execute {
            startLanComponents()
            // Obtain the current state even if the VPN broadcast happened before this service started.
            startService(Intent(this, MqvpnBondingService::class.java).apply {
                action = GlorytunConstants.ACTION_QUERY_STATE
            })
        }
    }

    private fun stopPairShare() {
        repository.setEnabled(false)
        if (!running.getAndSet(false)) {
            publishState()
            stopSelf()
            return
        }
        PairShareCoordinator.closeClient()
        closeHostSessions()
        controlExecutor.execute {
            stopLanComponents()
            serviceStatus = "Pair & Share は無効です"
            publishState()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun scheduleLanRefresh() {
        if (!running.get()) return
        controlExecutor.execute {
            stopLanComponents()
            startLanComponents()
        }
    }

    private fun startLanComponents() {
        if (!running.get()) return
        val network = PairShareNetwork.activeWifi(this)
        val localAddress = network?.let { PairShareNetwork.localIpv4(this, it) }
        if (network == null || localAddress == null) {
            wifiNetwork = null
            serviceStatus = "Pair & Share には同じ Wi-Fi 接続が必要です"
            updateNotification(serviceStatus)
            publishState()
            return
        }

        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(localAddress, 0))
            }
        }.getOrElse { error ->
            serviceStatus = "LAN 共有の開始に失敗しました: ${error.message}"
            updateNotification(serviceStatus)
            publishState()
            return
        }

        synchronized(lanLock) {
            serverSocket = server
            wifiNetwork = network
        }
        registerNsdService(server.localPort)
        startDiscovery()
        serviceStatus = "ペアリング待機中（同じ Wi-Fi）"
        updateNotification(serviceStatus)
        publishState()

        acceptExecutor.execute {
            while (running.get() && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                sessionExecutor.execute { handleIncoming(client) }
            }
        }
    }

    private fun stopLanComponents() {
        synchronized(lanLock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            wifiNetwork = null
        }
        stopDiscovery()
        unregisterNsdService()
        discoveries.clear()
        serviceIdsByName.clear()
        pending.values.forEach { it.resolve(false) }
        pending.clear()
        closeHostSessions()
    }

    private fun closeHostSessions() {
        hostSessions.toSet().forEach { it.close() }
        hostSessions.clear()
    }

    private fun closeHostVpnRelays() {
        hostSessions.toSet().forEach { it.closeVpnRelays() }
    }

    private fun registerNsdService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "BondVPN-${repository.deviceId().take(8)}"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", repository.deviceId())
            setAttribute("name", repository.displayName())
            setAttribute("version", PairShareWire.VERSION.toString())
        }
        registeredService = info
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure { error ->
            Log.w(TAG, "Pair & Share NSD 登録に失敗", error)
            serviceStatus = "LAN 検出を開始できませんでした"
        }
    }

    private fun unregisterNsdService() {
        if (registeredService == null) return
        registeredService = null
        runCatching { nsdManager.unregisterService(registrationListener) }
    }

    private fun startDiscovery() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                if (serviceInfo.serviceName == registeredService?.serviceName) return
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = serviceIdsByName.remove(serviceInfo.serviceName) ?: return
                discoveries.remove(id)
                publishState()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
                serviceStatus = "近くの端末を検索できませんでした"
                publishState()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
                discoveryListener = null
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discoveryListener = null }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        runCatching {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val id = info.attributes["id"]
                        ?.toString(StandardCharsets.UTF_8)
                        ?.takeIf { it.isNotBlank() }
                        ?: return
                    if (id == repository.deviceId()) return
                    val host = info.host?.hostAddress ?: return
                    val port = info.port.takeIf { it in 1..65535 } ?: return
                    val displayName = info.attributes["name"]
                        ?.toString(StandardCharsets.UTF_8)
                        ?.takeIf { it.isNotBlank() }
                        ?: "BondVPN デバイス"
                    val discovery = PairShareDiscovery(
                        id = id,
                        displayName = displayName,
                        host = host,
                        port = port,
                        lastSeenMillis = System.currentTimeMillis(),
                    )
                    discoveries[id] = discovery
                    serviceIdsByName[info.serviceName] = id
                    repository.updatePeerEndpoint(id, host, port, discovery.lastSeenMillis)
                    publishState()
                }
            })
        }
    }

    private fun handleIncoming(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                when (PairShareWire.readHeader(input)) {
                    PairShareWire.HELLO_PAIR -> handlePairRequest(client, input, output)
                    PairShareWire.HELLO_SESSION -> handleSessionRequest(client, input, output)
                    else -> throw IOException("不明な Pair & Share 接続種別です")
                }
            } catch (error: Throwable) {
                Log.d(TAG, "Pair & Share 接続を閉じました: ${error.message}")
            }
        }
    }

    private fun handlePairRequest(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        if (!running.get() || !repository.isEnabled()) {
            rejectPair(output, "Pair & Share が無効です")
            return
        }
        if (!acceptPairingAttempt(socket.inetAddress.hostAddress)) {
            rejectPair(output, "ペアリング試行が多すぎます。しばらく待ってから再試行してください")
            return
        }
        if (pending.size >= MAX_PENDING_PAIRING_REQUESTS) {
            rejectPair(output, "承認待ちのペアリング要求が多すぎます")
            return
        }
        val clientId = PairShareWire.readString(input)
        val clientName = PairShareWire.readString(input).trim().take(48)
        val pairingCode = PairShareWire.readString(input, 16)
        val clientPublicBytes = PairShareWire.readBytes(input, PairShareWire.MAX_PUBLIC_KEY_BYTES)
        if (clientId.isBlank() || clientName.isBlank() || pairingCode.length != 6) {
            rejectPair(output, "ペアリング情報が不正です")
            return
        }
        if (!PairShareCrypto.constantTimeEquals(
                pairingCode.toByteArray(StandardCharsets.UTF_8),
                repository.pairingCode().toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            rejectPair(output, "6桁コードが一致しません")
            return
        }

        val hostKeyPair = PairShareCrypto.generateEphemeralKeyPair()
        val sharedSecret = PairShareCrypto.sharedSecret(
            hostKeyPair.private,
            PairShareCrypto.decodePublicKey(clientPublicBytes),
        )
        val hostId = repository.deviceId()
        val pairKey = PairShareCrypto.derivePairKey(sharedSecret, pairingCode, hostId, clientId)
        val requestId = UUID.randomUUID().toString()
        val item = PendingPairing(
            requestId = requestId,
            clientId = clientId,
            clientName = clientName,
            pairKey = pairKey,
            socketAddress = socket.inetAddress.hostAddress,
        )
        pending[requestId] = item

        output.writeByte(PairShareWire.PAIR_PENDING)
        PairShareWire.writeString(output, hostId)
        PairShareWire.writeString(output, repository.displayName())
        PairShareWire.writeBytes(output, hostKeyPair.public.encoded)
        PairShareWire.writeString(output, requestId)
        output.write(
            PairShareCrypto.handshakeProof(
                pairKey,
                "pair-pending",
                requestId.toByteArray(StandardCharsets.UTF_8),
                hostId.toByteArray(StandardCharsets.UTF_8),
                clientId.toByteArray(StandardCharsets.UTF_8),
            ),
        )
        output.flush()
        serviceStatus = "${clientName} からのペアリング承認待ち"
        publishState()

        val approved = item.awaitDecision()
        pending.remove(requestId)
        if (!approved) {
            rejectPair(output, "ペアリング要求は承認されませんでした")
            publishState()
            return
        }

        val existing = repository.peer(clientId)
        repository.upsertPeer(
            PairSharePeer(
                id = clientId,
                displayName = clientName,
                sharedKey = PairShareCrypto.base64(pairKey),
                address = item.socketAddress,
                port = 0,
                canUseMyConnection = existing?.canUseMyConnection ?: false,
                speedLimitMbps = existing?.speedLimitMbps ?: PairSharePeer.DEFAULT_SPEED_LIMIT_MBPS,
            ),
        )
        output.writeByte(PairShareWire.PAIR_ACCEPTED)
        output.write(
            PairShareCrypto.handshakeProof(
                pairKey,
                "pair-accepted",
                requestId.toByteArray(StandardCharsets.UTF_8),
                hostId.toByteArray(StandardCharsets.UTF_8),
                clientId.toByteArray(StandardCharsets.UTF_8),
            ),
        )
        output.flush()
        serviceStatus = "${clientName} とペアリングしました"
        publishState()
    }

    private fun rejectPair(output: DataOutputStream, message: String) {
        output.writeByte(PairShareWire.PAIR_REJECTED)
        PairShareWire.writeString(output, message)
        output.flush()
    }

    private fun acceptPairingAttempt(address: String): Boolean {
        val now = System.currentTimeMillis()
        if (pairingAttempts.size > MAX_TRACKED_PAIRING_SOURCES) pairingAttempts.clear()
        return pairingAttempts.getOrPut(address) { PairingAttemptWindow(now) }.allow(now)
    }

    private fun handleSessionRequest(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        val clientId = PairShareWire.readString(input)
        val requestedHostId = PairShareWire.readString(input)
        val clientNonce = PairShareWire.readBytes(input, PairShareWire.NONCE_BYTES)
        val requestProof = PairShareWire.readExact(input, PairShareWire.PROOF_BYTES)
        val hostId = repository.deviceId()
        if (requestedHostId != hostId || clientNonce.size != PairShareWire.NONCE_BYTES) {
            rejectSession(output, "ペア端末の識別情報が一致しません")
            return
        }
        val peer = repository.peer(clientId)
        if (peer == null) {
            rejectSession(output, "この端末とはペアリングされていません")
            return
        }
        val pairKey = PairShareCrypto.fromBase64(peer.sharedKey)
        val expectedProof = PairShareCrypto.handshakeProof(
            pairKey,
            "session-request",
            clientId.toByteArray(StandardCharsets.UTF_8),
            hostId.toByteArray(StandardCharsets.UTF_8),
            clientNonce,
        )
        if (!PairShareCrypto.constantTimeEquals(requestProof, expectedProof)) {
            rejectSession(output, "ペア端末の認証に失敗しました")
            return
        }
        if (!bondingAllowedFor(clientId)) {
            rejectSession(output, bondingUnavailableReason(clientId))
            return
        }

        val serverNonce = PairShareCrypto.randomBytes(PairShareWire.NONCE_BYTES)
        output.writeByte(PairShareWire.SESSION_ACCEPTED)
        PairShareWire.writeBytes(output, serverNonce, PairShareWire.NONCE_BYTES)
        output.write(
            PairShareCrypto.handshakeProof(
                pairKey,
                "session-accepted",
                clientId.toByteArray(StandardCharsets.UTF_8),
                hostId.toByteArray(StandardCharsets.UTF_8),
                clientNonce,
                serverNonce,
            ),
        )
        output.flush()

        socket.soTimeout = 0
        val sessionKey = PairShareCrypto.deriveSessionKey(pairKey, clientNonce, serverNonce)
        lateinit var session: PairShareHostSession
        session = PairShareHostSession(
            context = this,
            socket = socket,
            codec = PairShareFrameCodec(
                input = input,
                output = output,
                key = sessionKey,
                inboundLabel = "client-to-host",
                outboundLabel = "host-to-client",
            ),
            sharingAllowed = { sharingAllowedFor(clientId) },
            bondingAllowed = { bondingAllowedFor(clientId) },
            speedLimitMbps = {
                repository.peer(clientId)?.speedLimitMbps ?: PairSharePeer.DEFAULT_SPEED_LIMIT_MBPS
            },
            onClosed = { hostSessions.remove(session) },
        )
        hostSessions.add(session)
        session.run()
    }

    private fun rejectSession(output: DataOutputStream, message: String) {
        output.writeByte(PairShareWire.SESSION_REJECTED)
        PairShareWire.writeString(output, message)
        output.flush()
    }

    private fun sharingAllowedFor(peerId: String): Boolean =
        bondingAllowedFor(peerId) && vpnConnected

    private fun bondingAllowedFor(peerId: String): Boolean =
        running.get() && repository.isEnabled() && repository.isSharingEnabled() &&
            repository.peer(peerId)?.canUseMyConnection == true

    private fun bondingUnavailableReason(peerId: String): String = when {
        !repository.isSharingEnabled() -> "この端末の共有がオフです"
        repository.peer(peerId)?.canUseMyConnection != true -> "このペア端末への共有が許可されていません"
        else -> "PairBond を利用できません"
    }

    private fun sharingUnavailableReason(peerId: String): String = when {
        !repository.isSharingEnabled() -> "この端末の共有はオフです"
        !vpnConnected -> "共有する端末で BondVPN VPN に接続してください"
        repository.peer(peerId)?.canUseMyConnection != true -> "このペア端末への共有は許可されていません"
        else -> "Pair & Share を利用できません"
    }

    private fun requestPair(discovery: PairShareDiscovery, code: String) {
        if (
            discovery.id.isBlank() || discovery.host.isBlank() || discovery.port !in 1..65535 ||
            code.length != 6
        ) {
            serviceStatus = "ペアリング情報が不正です"
            publishState()
            return
        }
        sessionExecutor.execute {
            serviceStatus = "${discovery.displayName} にペアリングを要求しています"
            publishState()
            val result = runCatching { pairWithDiscovery(discovery, code) }
            serviceStatus = result.fold(
                onSuccess = { "${discovery.displayName} とペアリングしました" },
                onFailure = { "ペアリングに失敗しました: ${it.message ?: "接続を確認してください"}" },
            )
            publishState()
        }
    }

    private fun pairWithDiscovery(discovery: PairShareDiscovery, code: String) {
        val wifi = PairShareNetwork.activeWifi(this)
            ?: throw IOException("Pair & Share には同じ Wi-Fi 接続が必要です")
        wifi.socketFactory.createSocket().use { socket ->
            socket.soTimeout = PAIRING_WAIT_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(discovery.host, discovery.port), CONNECT_TIMEOUT_MILLIS)
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val clientKeyPair = PairShareCrypto.generateEphemeralKeyPair()
            val clientId = repository.deviceId()
            val clientName = repository.displayName()

            PairShareWire.writeHeader(output, PairShareWire.HELLO_PAIR)
            PairShareWire.writeString(output, clientId)
            PairShareWire.writeString(output, clientName)
            PairShareWire.writeString(output, code, 16)
            PairShareWire.writeBytes(output, clientKeyPair.public.encoded)
            output.flush()

            when (input.readUnsignedByte()) {
                PairShareWire.PAIR_PENDING -> Unit
                PairShareWire.PAIR_REJECTED -> throw IOException(PairShareWire.readString(input))
                else -> throw IOException("ペア端末から不正な応答を受け取りました")
            }
            val hostId = PairShareWire.readString(input)
            val hostName = PairShareWire.readString(input)
            val hostPublicKey = PairShareWire.readBytes(input, PairShareWire.MAX_PUBLIC_KEY_BYTES)
            val requestId = PairShareWire.readString(input)
            val pendingProof = PairShareWire.readExact(input, PairShareWire.PROOF_BYTES)
            if (hostId != discovery.id) throw IOException("検出した端末と応答した端末が一致しません")
            val sharedSecret = PairShareCrypto.sharedSecret(
                clientKeyPair.private,
                PairShareCrypto.decodePublicKey(hostPublicKey),
            )
            val pairKey = PairShareCrypto.derivePairKey(sharedSecret, code, hostId, clientId)
            val expectedPendingProof = PairShareCrypto.handshakeProof(
                pairKey,
                "pair-pending",
                requestId.toByteArray(StandardCharsets.UTF_8),
                hostId.toByteArray(StandardCharsets.UTF_8),
                clientId.toByteArray(StandardCharsets.UTF_8),
            )
            if (!PairShareCrypto.constantTimeEquals(pendingProof, expectedPendingProof)) {
                throw IOException("ペアリング応答を検証できません")
            }
            serviceStatus = "${hostName.ifBlank { discovery.displayName }} の承認待ち（確認語: ${PairShareCrypto.verificationWords(pairKey)}）"
            publishState()

            when (input.readUnsignedByte()) {
                PairShareWire.PAIR_ACCEPTED -> Unit
                PairShareWire.PAIR_REJECTED -> throw IOException(PairShareWire.readString(input))
                else -> throw IOException("ペアリング承認の応答が不正です")
            }
            val acceptedProof = PairShareWire.readExact(input, PairShareWire.PROOF_BYTES)
            val expectedAcceptedProof = PairShareCrypto.handshakeProof(
                pairKey,
                "pair-accepted",
                requestId.toByteArray(StandardCharsets.UTF_8),
                hostId.toByteArray(StandardCharsets.UTF_8),
                clientId.toByteArray(StandardCharsets.UTF_8),
            )
            if (!PairShareCrypto.constantTimeEquals(acceptedProof, expectedAcceptedProof)) {
                throw IOException("ペアリング承認を検証できません")
            }
            repository.upsertPeer(
                PairSharePeer(
                    id = hostId,
                    displayName = hostName.ifBlank { discovery.displayName },
                    sharedKey = PairShareCrypto.base64(pairKey),
                    address = discovery.host,
                    port = discovery.port,
                ),
            )
        }
    }

    private fun resolvePending(requestId: String, approved: Boolean) {
        pending[requestId]?.resolve(approved)
    }

    private fun publishState() {
        val pendingItems = pending.values
            .map {
                PairSharePending(
                    requestId = it.requestId,
                    peerId = it.clientId,
                    displayName = it.clientName,
                    verificationWords = PairShareCrypto.verificationWords(it.pairKey),
                )
            }
            .sortedBy { it.displayName.lowercase() }
        PairShareCoordinator.publish(
            PairShareUiState(
                enabled = repository.isEnabled(),
                serviceStatus = serviceStatus,
                pairingCode = repository.pairingCode(),
                vpnConnected = vpnConnected,
                discovered = discoveries.values
                    .filter { discovery -> repository.peer(discovery.id) == null }
                    .sortedBy { it.displayName.lowercase() },
                peers = repository.peers().sortedBy { it.displayName.lowercase() },
                pending = pendingItems,
            ),
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            PAIR_SHARE_CHANNEL_ID,
            "BondVPN Pair & Share",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Pair & Share の近距離共有状態" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification(content: String) {
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            PAIR_SHARE_NOTIFICATION_ID,
            buildNotification(content),
            foregroundServiceType,
        )
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(PAIR_SHARE_NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String) = NotificationCompat.Builder(this, PAIR_SHARE_CHANNEL_ID)
        .setContentTitle("BondVPN Pair & Share")
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    override fun onDestroy() {
        running.set(false)
        PairShareCoordinator.closeClient()
        runCatching { unregisterReceiver(vpnStateReceiver) }
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        stopLanComponents()
        acceptExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        sessionExecutor.shutdownNow()
        super.onDestroy()
    }

    private class PendingPairing(
        val requestId: String,
        val clientId: String,
        val clientName: String,
        val pairKey: ByteArray,
        val socketAddress: String,
    ) {
        private val decision = CountDownLatch(1)
        @Volatile private var approved = false

        fun resolve(value: Boolean) {
            approved = value
            decision.countDown()
        }

        fun awaitDecision(): Boolean = decision.await(PAIRING_APPROVAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && approved
    }

    private class PairingAttemptWindow(startedAt: Long) {
        private var startedAtMillis = startedAt
        private var attempts = 0

        fun allow(nowMillis: Long): Boolean = synchronized(this) {
            if (nowMillis - startedAtMillis >= PAIRING_ATTEMPT_WINDOW_MILLIS) {
                startedAtMillis = nowMillis
                attempts = 0
            }
            if (attempts >= MAX_PAIRING_ATTEMPTS_PER_WINDOW) return false
            attempts++
            true
        }
    }

    companion object {
        private const val TAG = "PairShareService"
        private const val SERVICE_TYPE = "_bondvpn-share._tcp."
        private const val PAIR_SHARE_CHANNEL_ID = "bondvpn_pair_share_channel"
        private const val PAIR_SHARE_NOTIFICATION_ID = 3
        private const val CONNECT_TIMEOUT_MILLIS = 12_000
        private const val HANDSHAKE_TIMEOUT_MILLIS = 20_000
        private const val PAIRING_WAIT_TIMEOUT_MILLIS = 130_000
        private const val PAIRING_APPROVAL_TIMEOUT_MILLIS = 120_000L
        private const val PAIRING_ATTEMPT_WINDOW_MILLIS = 10 * 60 * 1000L
        private const val MAX_PAIRING_ATTEMPTS_PER_WINDOW = 6
        private const val MAX_TRACKED_PAIRING_SOURCES = 128
        private const val MAX_PENDING_PAIRING_REQUESTS = 5

        private const val ACTION_START = "com.example.glorytun.PAIR_SHARE_START"
        private const val ACTION_STOP = "com.example.glorytun.PAIR_SHARE_STOP"
        private const val ACTION_PAIR = "com.example.glorytun.PAIR_SHARE_REQUEST"
        private const val ACTION_ACCEPT_PAIR = "com.example.glorytun.PAIR_SHARE_ACCEPT"
        private const val ACTION_REJECT_PAIR = "com.example.glorytun.PAIR_SHARE_REJECT"
        private const val ACTION_REGENERATE_CODE = "com.example.glorytun.PAIR_SHARE_REGENERATE_CODE"
        private const val ACTION_QUERY = "com.example.glorytun.PAIR_SHARE_QUERY"

        private const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_PEER_HOST = "peer_host"
        private const val EXTRA_PEER_PORT = "peer_port"
        private const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val EXTRA_REQUEST_ID = "request_id"

        fun start(context: Context) {
            PairShareRepository(context).setEnabled(true)
            ContextCompat.startForegroundService(context, Intent(context, PairShareService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            PairShareRepository(context).setEnabled(false)
            context.startService(Intent(context, PairShareService::class.java).apply { action = ACTION_STOP })
        }

        fun requestPair(context: Context, discovery: PairShareDiscovery, code: String) {
            start(context)
            context.startService(Intent(context, PairShareService::class.java).apply {
                action = ACTION_PAIR
                putExtra(EXTRA_PEER_ID, discovery.id)
                putExtra(EXTRA_PEER_NAME, discovery.displayName)
                putExtra(EXTRA_PEER_HOST, discovery.host)
                putExtra(EXTRA_PEER_PORT, discovery.port)
                putExtra(EXTRA_PAIRING_CODE, code)
            })
        }

        fun acceptPair(context: Context, requestId: String) {
            context.startService(Intent(context, PairShareService::class.java).apply {
                action = ACTION_ACCEPT_PAIR
                putExtra(EXTRA_REQUEST_ID, requestId)
            })
        }

        fun rejectPair(context: Context, requestId: String) {
            context.startService(Intent(context, PairShareService::class.java).apply {
                action = ACTION_REJECT_PAIR
                putExtra(EXTRA_REQUEST_ID, requestId)
            })
        }

        fun regenerateCode(context: Context) {
            context.startService(Intent(context, PairShareService::class.java).apply {
                action = ACTION_REGENERATE_CODE
            })
        }

        fun refresh(context: Context) {
            context.startService(Intent(context, PairShareService::class.java).apply {
                action = ACTION_QUERY
            })
        }
    }
}
