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
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.NetworkInterface

class GlorytunVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isConnected = false
    private lateinit var connectivityManager: ConnectivityManager

    companion object {
        const val ACTION_CONNECT = "com.example.glorytun.START"
        const val ACTION_DISCONNECT = "com.example.glorytun.STOP"
        private const val TAG = "GlorytunVpn"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "glorytun_vpn_channel"

        init {
            System.loadLibrary("glorytun_jni")
        }
    }

    // ネットワーク別のローカルIPを管理 (networkHandle -> localIp)
    private val activeNetworkPaths = mutableMapOf<Long, String>()

    // WiFi / SIM それぞれの物理インターフェース名を追跡（TrafficStats用）
    // 例: WiFi="wlan0", SIM="rmnet_data0" など
    @Volatile private var wifiIfaceName: String? = null
    @Volatile private var simIfaceName: String? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!isConnected) return
            val handle = network.networkHandle
            val localIp = getLocalIpForNetwork(network) ?: return
            val iface = connectivityManager.getLinkProperties(network)?.interfaceName
            Log.i(TAG, "WiFi available: handle=$handle ip=$localIp iface=$iface")
            wifiIfaceName = iface
            activeNetworkPaths[handle] = localIp
            addPathForNetwork(localIp, handle)
        }
        override fun onLost(network: Network) {
            val handle = network.networkHandle
            val localIp = activeNetworkPaths.remove(handle) ?: return
            Log.i(TAG, "WiFi lost: handle=$handle ip=$localIp")
            wifiIfaceName = null
            removePathForNetwork(localIp)
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!isConnected) return
            val handle = network.networkHandle
            val localIp = getLocalIpForNetwork(network) ?: return
            val iface = connectivityManager.getLinkProperties(network)?.interfaceName
            Log.i(TAG, "SIM available: handle=$handle ip=$localIp iface=$iface")
            simIfaceName = iface
            activeNetworkPaths[handle] = localIp
            addPathForNetwork(localIp, handle)
        }
        override fun onLost(network: Network) {
            val handle = network.networkHandle
            val localIp = activeNetworkPaths.remove(handle) ?: return
            Log.i(TAG, "SIM lost: handle=$handle ip=$localIp")
            simIfaceName = null
            removePathForNetwork(localIp)
        }
    }

    // 1秒ごとに TrafficStats でインターフェース別の通信量を取得してブロードキャスト。
    // TrafficStats.getRxBytes/getTxBytes はシステム起動からの累積値を返す。
    // MainActivity 側でデルタを計算して KB/s に変換する。
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) return

            val wIface = wifiIfaceName
            val sIface = simIfaceName

            val wifiTx = if (wIface != null) TrafficStats.getTxBytes(wIface) else TrafficStats.UNSUPPORTED.toLong()
            val wifiRx = if (wIface != null) TrafficStats.getRxBytes(wIface) else TrafficStats.UNSUPPORTED.toLong()
            val simTx  = if (sIface != null) TrafficStats.getTxBytes(sIface) else TrafficStats.UNSUPPORTED.toLong()
            val simRx  = if (sIface != null) TrafficStats.getRxBytes(sIface) else TrafficStats.UNSUPPORTED.toLong()

            sendBroadcast(Intent("VPN_TRAFFIC_STATS").apply {
                setPackage(packageName)
                // UNSUPPORTED (-1) の場合は 0 として送信
                putExtra("wifi_tx_bytes", if (wifiTx >= 0L) wifiTx else 0L)
                putExtra("wifi_rx_bytes", if (wifiRx >= 0L) wifiRx else 0L)
                putExtra("wifi_active",   wIface != null && wifiTx >= 0L)
                putExtra("sim_tx_bytes",  if (simTx >= 0L) simTx else 0L)
                putExtra("sim_rx_bytes",  if (simRx >= 0L) simRx else 0L)
                putExtra("sim_active",    sIface != null && simTx >= 0L)
            })
            statsHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CONNECT) {
            val serverIp = intent.getStringExtra("IP") ?: ""
            val port = intent.getStringExtra("PORT") ?: ""
            val secret = intent.getStringExtra("SECRET") ?: ""
            connectVpn(serverIp, port, secret)
        } else if (action == ACTION_DISCONNECT) {
            disconnectVpn()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Glorytun VPN", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "VPN接続状態の通知" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Glorytun VPN")
            .setContentText("VPN接続中 (マルチパス)")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun connectVpn(serverIp: String, port: String, secret: String) {
        if (isConnected) return

        Thread {
            try {
                val builder = Builder()
                builder.setSession("Glorytun VPN")
                builder.addAddress("10.0.1.2", 24)
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")
                builder.setMtu(1420)

                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    isConnected = true
                    val fd = vpnInterface!!.fd
                    Log.i(TAG, "VPN Established, fd=$fd")

                    startForegroundNotification()
                    sendVpnState("Connected")

                    // glorytun スレッドを先に起動し、g_mud が初期化されてから
                    // ネットワークコールバックを登録する（タイミング競合を回避）
                    startGlorytunNative(fd, serverIp, port, secret)
                    waitForGlorytunReady()
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN Setup failed", e)
            }
        }.start()
    }

    private fun registerNetworkCallbacks() {
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cellRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        connectivityManager.requestNetwork(wifiRequest, wifiCallback)
        connectivityManager.requestNetwork(cellRequest, cellCallback)
        statsHandler.post(statsRunnable)
    }

    private fun unregisterNetworkCallbacks() {
        for (cb in listOf(wifiCallback, cellCallback)) {
            try { connectivityManager.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
    }

    private fun disconnectVpn() {
        isConnected = false
        statsHandler.removeCallbacks(statsRunnable)
        unregisterNetworkCallbacks()
        activeNetworkPaths.clear()
        wifiIfaceName = null
        simIfaceName = null
        stopGlorytunNative()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "VPN interface close failed", e)
        }
        vpnInterface = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        sendVpnState("Disconnected")
        stopSelf()
    }

    private fun sendVpnState(state: String) {
        sendBroadcast(Intent("VPN_STATE").apply {
            setPackage(packageName)
            putExtra("state", state)
        })
    }

    /** ネットワークに紐付いたインターフェースのローカル IP アドレスを取得する */
    private fun getLocalIpForNetwork(network: Network): String? {
        return try {
            val linkProps = connectivityManager.getLinkProperties(network) ?: return null
            linkProps.linkAddresses
                .map { it.address }
                .firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                    !addr.isLinkLocalAddress &&
                    addr.hostAddress?.contains(':') == false  // IPv4 優先
                }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "getLocalIpForNetwork failed", e)
            null
        }
    }

    /** C 側から呼ばれる: 指定ソケットを特定 Network にバインドする。
     *  Network.bindSocket() は FileDescriptor を要求するため、
     *  リフレクションで Int fd → FileDescriptor に変換する。 */
    fun bindSocketToNetwork(fd: Int, networkHandle: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val network = Network.fromNetworkHandle(networkHandle)
            val fileDescriptor = java.io.FileDescriptor()
            // Android の内部フィールド名は "descriptor"。見つからなければ "fd" を試みる
            val field = runCatching {
                java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
            }.getOrElse {
                java.io.FileDescriptor::class.java.getDeclaredField("fd")
            }
            field.isAccessible = true
            field.setInt(fileDescriptor, fd)
            network.bindSocket(fileDescriptor)
            Log.i(TAG, "bindSocketToNetwork(fd=$fd, handle=$networkHandle): OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "bindSocketToNetwork(fd=$fd, handle=$networkHandle): FAIL", e)
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectVpn()
    }

    /**
     * glorytun (g_mud) の初期化完了を最大2秒ポーリングで待ち、
     * 完了次第ネットワークコールバックを登録する。
     */
    private fun waitForGlorytunReady(retriesLeft: Int = 40) {
        if (!isConnected) return
        if (isGlorytunReady()) {
            Log.i(TAG, "glorytun ready — registering network callbacks")
            registerNetworkCallbacks()
            return
        }
        if (retriesLeft <= 0) {
            Log.w(TAG, "glorytun did not initialize in time, skipping multipath")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            waitForGlorytunReady(retriesLeft - 1)
        }, 50)
    }

    // JNI メソッド宣言
    private external fun startGlorytunNative(fd: Int, ip: String, port: String, secret: String): Int
    private external fun stopGlorytunNative()
    private external fun isGlorytunReady(): Boolean
    private external fun addPathForNetwork(localIp: String, networkHandle: Long): Int
    private external fun removePathForNetwork(localIp: String): Int
    /** 指定ローカルIPのパス統計を返す: [tx_bytes, rx_bytes, tx_rate, rx_rate]、パス未存在時はnull */
    private external fun getPathStatsForIp(localIp: String): LongArray?
}
