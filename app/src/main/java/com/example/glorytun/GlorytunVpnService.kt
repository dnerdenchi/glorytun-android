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
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Calendar
import java.io.File

class GlorytunVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isConnected = false
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var trafficDataStore: TrafficDataStore
    
    private lateinit var statsManager: TrafficStatsManager
    private lateinit var bandwidthController: BandwidthController

    // 帯域統計（累積 KB）
    private var bwInitialized   = false
    private var bwDailyWifiKB   = 0.0
    private var bwDailySimKB    = 0.0
    private var bwMonthlyWifiKB = 0.0
    private var bwMonthlySimKB  = 0.0

    // 時間集計用
    private var currentHourBucket = 0L

    companion object {
        private const val TAG = "GlorytunVpn"

        init {
            System.loadLibrary("glorytun_jni")
        }
    }

    // ネットワーク別のローカルIPを管理 (networkHandle -> localIp)
    private val activeNetworkPaths = mutableMapOf<Long, String>()

    // WiFi / SIM それぞれの物理インターフェース名を追跡（TrafficStats用）
    @Volatile private var wifiIfaceName: String? = null
    @Volatile private var simIfaceName: String? = null

    // 利用可能なネットワーク情報 (モード制御用)
    @Volatile private var availableWifiNetwork: Network? = null
    @Volatile private var availableWifiLocalIp: String? = null
    @Volatile private var availableSimNetwork: Network? = null
    @Volatile private var availableSimLocalIp: String? = null

    // NetworkCapabilities から推定した帯域幅 (bytes/s)
    // getLinkDownstreamBandwidthKbps() は kbps → ×125 で bytes/s
    @Volatile private var estimatedWifiBwBytesPerSec = 0L
    @Volatile private var estimatedSimBwBytesPerSec  = 0L

    // 現在のネットワークモードと速度しきい値を SharedPreferences から読み取る
    private fun currentMode(): String =
        getSharedPreferences(NetworkProtocolFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(NetworkProtocolFragment.KEY_MODE, NetworkProtocolFragment.MODE_BONDING)
            ?: NetworkProtocolFragment.MODE_BONDING

    /** しきい値を bytes/s で返す */
    private fun currentThresholdBytesPerSec(): Long =
        getSharedPreferences(NetworkProtocolFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(NetworkProtocolFragment.KEY_THRESHOLD_MBPS, NetworkProtocolFragment.DEFAULT_THRESHOLD_MBPS)
            .toLong() * GlorytunConstants.MBPS_TO_BYTES_PER_SEC

    /** NetworkCapabilities から推定帯域幅を bytes/s に変換して保存する */
    private fun updateBandwidthFromCaps(network: Network, isWifi: Boolean) {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return
        val kbps = caps.getLinkDownstreamBandwidthKbps()
        val bytesPerSec = kbps.toLong() * GlorytunConstants.KBPS_TO_BYTES_PER_SEC
        if (isWifi) estimatedWifiBwBytesPerSec = bytesPerSec
        else estimatedSimBwBytesPerSec = bytesPerSec
        Log.i(TAG, "${if (isWifi) "WiFi" else "SIM"} 推定帯域: %.2f Mbps".format(bytesPerSec / GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat()))
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!isConnected) return
            val localIp = getLocalIpForNetwork(network) ?: return
            val iface   = connectivityManager.getLinkProperties(network)?.interfaceName
            Log.i(TAG, "WiFi available: handle=${network.networkHandle} ip=$localIp iface=$iface")
            wifiIfaceName        = iface
            availableWifiNetwork = network
            availableWifiLocalIp = localIp
            updateBandwidthFromCaps(network, isWifi = true)
            // ボンディングとWiFi優先はプライマリとして即時追加
            when (currentMode()) {
                NetworkProtocolFragment.MODE_BONDING,
                NetworkProtocolFragment.MODE_WIFI_FIRST -> addNetworkPath(network.networkHandle, localIp)
                // SIM優先: statsRunnable がしきい値判定後に追加
            }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!isConnected) return
            val kbps = caps.getLinkDownstreamBandwidthKbps()
            estimatedWifiBwBytesPerSec = kbps.toLong() * GlorytunConstants.KBPS_TO_BYTES_PER_SEC
            Log.d(TAG, "WiFi caps更新: %.2f Mbps".format(estimatedWifiBwBytesPerSec / GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat()))
        }
        override fun onLost(network: Network) {
            val handle  = network.networkHandle
            val localIp = activeNetworkPaths.remove(handle)
            Log.i(TAG, "WiFi lost: handle=$handle ip=$localIp")
            wifiIfaceName             = null
            availableWifiNetwork      = null
            availableWifiLocalIp      = null
            estimatedWifiBwBytesPerSec = 0L
            if (localIp != null) removePathForNetwork(localIp)
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!isConnected) return
            val localIp = getLocalIpForNetwork(network) ?: return
            val iface   = connectivityManager.getLinkProperties(network)?.interfaceName
            Log.i(TAG, "SIM available: handle=${network.networkHandle} ip=$localIp iface=$iface")
            simIfaceName        = iface
            availableSimNetwork = network
            availableSimLocalIp = localIp
            updateBandwidthFromCaps(network, isWifi = false)
            when (currentMode()) {
                NetworkProtocolFragment.MODE_BONDING,
                NetworkProtocolFragment.MODE_SIM_FIRST -> addNetworkPath(network.networkHandle, localIp)
                // WiFi優先: statsRunnable がしきい値判定後に追加
            }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!isConnected) return
            val kbps = caps.getLinkDownstreamBandwidthKbps()
            estimatedSimBwBytesPerSec = kbps.toLong() * GlorytunConstants.KBPS_TO_BYTES_PER_SEC
            Log.d(TAG, "SIM caps更新: %.2f Mbps".format(estimatedSimBwBytesPerSec / GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat()))
        }
        override fun onLost(network: Network) {
            val handle  = network.networkHandle
            val localIp = activeNetworkPaths.remove(handle)
            Log.i(TAG, "SIM lost: handle=$handle ip=$localIp")
            simIfaceName            = null
            availableSimNetwork     = null
            availableSimLocalIp     = null
            estimatedSimBwBytesPerSec = 0L
            if (localIp != null) removePathForNetwork(localIp)
        }
    }

    /** パスを追加し activeNetworkPaths に記録する (重複追加を防ぐ) */
    private fun addNetworkPath(handle: Long, localIp: String) {
        if (activeNetworkPaths.containsKey(handle)) return
        activeNetworkPaths[handle] = localIp
        addPathForNetwork(localIp, handle)
    }

    /** パスを削除し activeNetworkPaths から除去する */
    private fun removeNetworkPath(handle: Long) {
        val localIp = activeNetworkPaths.remove(handle) ?: return
        removePathForNetwork(localIp)
    }

    /**
     * ボンディングモード用レート設定。
     * 各パスの tx/rx_max_rate を NetworkCapabilities の推定帯域幅に合わせて設定することで、
     * glorytun が帯域の少ないパスに過剰送信しないようにする。
     */
    private fun applyBondingRates() {
        val wifiNet = availableWifiNetwork
        val simNet  = availableSimNetwork
        val wifiIp  = availableWifiLocalIp
        val simIp   = availableSimLocalIp
        val wifiBw  = estimatedWifiBwBytesPerSec
        val simBw   = estimatedSimBwBytesPerSec

        // モード切替後に不足しているパスを追加（addNetworkPath は重複追加を防ぐ）
        if (wifiNet != null && wifiIp != null) {
            addNetworkPath(wifiNet.networkHandle, wifiIp)
            if (wifiBw > 0L) setPathMaxRate(wifiIp, wifiBw, wifiBw)
        }
        if (simNet != null && simIp != null) {
            addNetworkPath(simNet.networkHandle, simIp)
            if (simBw > 0L) setPathMaxRate(simIp, simBw, simBw)
        }
    }

    /**
     * WiFi優先モード。
     * WiFi 推定帯域 < しきい値 → SIM を追加し、不足分だけに rate を制限する。
     * WiFi が回復 → SIM を除去。
     */
    private fun manageWifiFirstMode(thresholdBytesPerSec: Long) {
        val wifiNet = availableWifiNetwork
        val wifiIp  = availableWifiLocalIp

        // WiFiが利用可能ならプライマリとして必ず追加（モード切替後の復元を含む）
        if (wifiNet != null && wifiIp != null) {
            addNetworkPath(wifiNet.networkHandle, wifiIp)
        }

        val simNet    = availableSimNetwork ?: return
        val simHandle = simNet.networkHandle
        val simIp     = availableSimLocalIp ?: return

        val wifiBw    = estimatedWifiBwBytesPerSec
        val wifiSlow  = wifiNet == null || wifiBw < thresholdBytesPerSec
        val simActive = activeNetworkPaths.containsKey(simHandle)

        if (wifiSlow) {
            val neededBw = (thresholdBytesPerSec - wifiBw).coerceAtLeast(1L)
            val simBwCap = estimatedSimBwBytesPerSec.takeIf { it > 0L } ?: neededBw
            val simRate  = minOf(neededBw, simBwCap)

            if (!simActive) {
                Log.i(TAG, "WiFi低速 (%.1f Mbps < %.1f Mbps) → SIM追加 rate=%.1f Mbps"
                    .format(wifiBw/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat(), thresholdBytesPerSec/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat(), simRate/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat()))
                addNetworkPath(simHandle, simIp)
            }
            setPathMaxRate(simIp, simRate, simRate)

        } else if (simActive) {
            Log.i(TAG, "WiFi回復 (%.1f Mbps) → SIM除去".format(wifiBw/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat()))
            removeNetworkPath(simHandle)
        }
    }

    /**
     * SIM優先モード。
     * SIM 推定帯域 < しきい値 → WiFi を追加し、不足分だけに rate を制限する。
     * SIM が回復 → WiFi を除去。
     */
    private fun manageSimFirstMode(thresholdBytesPerSec: Long) {
        val simNet = availableSimNetwork
        val simIp  = availableSimLocalIp

        // SIMが利用可能ならプライマリとして必ず追加（モード切替後の復元を含む）
        if (simNet != null && simIp != null) {
            addNetworkPath(simNet.networkHandle, simIp)
        }

        val wifiNet    = availableWifiNetwork ?: return
        val wifiHandle = wifiNet.networkHandle
        val wifiIp     = availableWifiLocalIp ?: return

        val simBw      = estimatedSimBwBytesPerSec
        val simSlow    = simNet == null || simBw < thresholdBytesPerSec
        val wifiActive = activeNetworkPaths.containsKey(wifiHandle)

        if (simSlow) {
            val neededBw  = (thresholdBytesPerSec - simBw).coerceAtLeast(1L)
            val wifiBwCap = estimatedWifiBwBytesPerSec.takeIf { it > 0L } ?: neededBw
            val wifiRate  = minOf(neededBw, wifiBwCap)

            if (!wifiActive) {
                Log.i(TAG, "SIM低速 (%.1f Mbps < %.1f Mbps) → WiFi追加 rate=%.1f Mbps"
                    .format(simBw/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat(), thresholdBytesPerSec/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat(), wifiRate/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat()))
                addNetworkPath(wifiHandle, wifiIp)
            }
            setPathMaxRate(wifiIp, wifiRate, wifiRate)

        } else if (wifiActive) {
            Log.i(TAG, "SIM回復 (%.1f Mbps) → WiFi除去".format(simBw/GlorytunConstants.MBPS_TO_BYTES_PER_SEC.toFloat()))
            removeNetworkPath(wifiHandle)
        }
    }

    private val statsListener = object : TrafficStatsManager.Listener {
        override fun getWifiIface(): String? = wifiIfaceName
        override fun getSimIface(): String? = simIfaceName
        override fun getCurrentEstimatedWifiBw(): Long = estimatedWifiBwBytesPerSec
        override fun getCurrentEstimatedSimBw(): Long = estimatedSimBwBytesPerSec
        override fun getDailyWifiKB(): Double = bwDailyWifiKB
        override fun getDailySimKB(): Double = bwDailySimKB
        override fun isWifiThrottled(): Boolean = bandwidthController.isWifiThrottled()
        override fun isSimThrottled(): Boolean = bandwidthController.isSimThrottled()

        override fun onStatsProcessed(wifiDeltaKB: Float, simDeltaKB: Float) {
            if (!bwInitialized) return
            
            // 累計データの更新
            bwDailyWifiKB += wifiDeltaKB
            bwDailySimKB += simDeltaKB
            bwMonthlyWifiKB += wifiDeltaKB
            bwMonthlySimKB += simDeltaKB

            // モードに応じたパス管理
            val threshold = currentThresholdBytesPerSec()
            when (currentMode()) {
                NetworkProtocolFragment.MODE_BONDING -> applyBondingRates()
                NetworkProtocolFragment.MODE_WIFI_FIRST -> manageWifiFirstMode(threshold)
                NetworkProtocolFragment.MODE_SIM_FIRST -> manageSimFirstMode(threshold)
            }

            // スロットリングチェック
            bandwidthController.checkAndThrottle(bwDailyWifiKB, bwDailySimKB, bwMonthlyWifiKB, bwMonthlySimKB)
        }

        override fun onHourlyAggregated(timestamp: Long, wifiAvgKBps: Float, simAvgKBps: Float) {
            currentHourBucket = timestamp / GlorytunConstants.MILLIS_IN_HOUR
            saveHourlyAggregate(timestamp, wifiAvgKBps, simAvgKBps)
        }

        override fun onDayReset() {
            bwDailyWifiKB = 0.0; bwDailySimKB = 0.0
            bandwidthController.resetThrottledFlags()
            bandwidthController.clearCache()
            Log.i(TAG, "Day boundary: counters reset")
        }

        override fun onMonthReset() {
            bwMonthlyWifiKB = 0.0; bwMonthlySimKB = 0.0
            bandwidthController.clearCache()
            Log.i(TAG, "Month boundary: counters reset")
        }
    }

    private val bandwidthListener = object : BandwidthController.Listener {
        override fun setPathMaxRate(localIp: String, txRate: Long, rxRate: Long) {
            this@GlorytunVpnService.setPathMaxRate(localIp, txRate, rxRate)
        }
        override fun setPathThrottleRate(localIp: String, txRate: Long, rxRate: Long, enable: Boolean) {
            this@GlorytunVpnService.setPathThrottleRate(localIp, txRate, rxRate, enable)
        }
        override fun getActiveNetworkPaths(): Map<Long, String> = activeNetworkPaths
        override fun getAvailableWifiLocalIp(): String? = availableWifiLocalIp
        override fun getAvailableSimLocalIp(): String? = availableSimLocalIp
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        trafficDataStore = TrafficDataStore(this)
        statsManager = TrafficStatsManager(this, statsListener)
        bandwidthController = BandwidthController(this, bandwidthListener)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == GlorytunConstants.ACTION_CONNECT) {
            val serverIp = intent.getStringExtra("IP") ?: ""
            val port = intent.getStringExtra("PORT") ?: ""
            val secret = intent.getStringExtra("SECRET") ?: ""
            connectVpn(serverIp, port, secret)
        } else if (action == GlorytunConstants.ACTION_DISCONNECT) {
            disconnectVpn()
        } else if (action == GlorytunConstants.ACTION_QUERY_STATE) {
            sendVpnState(if (isConnected) "Connected" else "Disconnected")
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GlorytunConstants.CHANNEL_ID, GlorytunConstants.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "VPN接続状態の通知" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, GlorytunConstants.CHANNEL_ID)
            .setContentTitle("Glorytun VPN")
            .setContentText("VPN接続中 (マルチパス)")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(GlorytunConstants.NOTIFICATION_ID, notification)
    }

    private fun connectVpn(serverIp: String, port: String, secret: String) {
        if (isConnected) return

        Thread {
            try {
                val builder = Builder()
                builder.setSession("Glorytun VPN")
                builder.addAddress(GlorytunConstants.DEFAULT_VPN_LOCAL_IP, 24)
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                val adguardEnabled = getSharedPreferences(GlorytunConstants.PREFS_DNS, Context.MODE_PRIVATE)
                    .getBoolean(GlorytunConstants.KEY_ADGUARD_DNS_ENABLED, false)
                if (adguardEnabled) {
                    builder.addDnsServer(GlorytunConstants.ADGUARD_DNS_PRIMARY)
                    builder.addDnsServer(GlorytunConstants.ADGUARD_DNS_SECONDARY)
                } else {
                    builder.addDnsServer(GlorytunConstants.DEFAULT_DNS_PRIMARY)
                    builder.addDnsServer(GlorytunConstants.DEFAULT_DNS_SECONDARY)
                }
                builder.setMtu(GlorytunConstants.DEFAULT_MTU)

                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    isConnected = true
                    startForegroundNotification()
                    sendVpnState("Connected")

                    val keyfilePath = File(cacheDir, "keyfile.txt").absolutePath
                    startGlorytunNative(vpnInterface!!.fd, serverIp, port, secret, keyfilePath)
                    waitForGlorytunReady()
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN Setup failed", e)
            }
        }.start()
    }

    private fun initBandwidthTracking() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val bwDayStartMs = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val bwMonthStartMs = cal.timeInMillis

        Thread {
            val points = trafficDataStore.load()
            var dWifi = 0.0; var dSim = 0.0; var mWifi = 0.0; var mSim = 0.0
            for (p in points) {
                if (p.timestamp >= bwDayStartMs) {
                    dWifi += p.wifiKBs * 3600.0
                    dSim  += p.simKBs  * 3600.0
                }
                if (p.timestamp >= bwMonthStartMs) {
                    mWifi += p.wifiKBs * 3600.0
                    mSim  += p.simKBs  * 3600.0
                }
            }
            Handler(Looper.getMainLooper()).post {
                bwDailyWifiKB   += dWifi; bwDailySimKB   += dSim
                bwMonthlyWifiKB += mWifi; bwMonthlySimKB += mSim
                bwInitialized = true
                Log.i(TAG, "帯域追跡初期化完了")
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
        initBandwidthTracking()
        statsManager.start()
    }

    private fun unregisterNetworkCallbacks() {
        for (cb in listOf(wifiCallback, cellCallback)) {
            try { connectivityManager.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
    }

    /**
     * 指定時間スタンプの1時間集計データをファイルに保存し、
     * MainActivityへブロードキャストしてViewModelのメモリを更新させる。
     */
    private fun saveHourlyAggregate(hourTimestamp: Long, avgWifi: Float, avgSim: Float) {
        val point = TrafficPoint(hourTimestamp, avgWifi, avgSim)
        Thread { trafficDataStore.appendPoint(point) }.start()

        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_HOURLY_STATS).apply {
            setPackage(packageName)
            putExtra("timestamp", hourTimestamp)
            putExtra("wifi_kbs",  avgWifi)
            putExtra("sim_kbs",   avgSim)
        })
    }

    private fun disconnectVpn() {
        isConnected = false
        statsManager.stop()

        bwInitialized   = false
        bwDailyWifiKB   = 0.0; bwDailySimKB    = 0.0
        bwMonthlyWifiKB = 0.0; bwMonthlySimKB  = 0.0
        bandwidthController.resetThrottledFlags()
        bandwidthController.clearCache()

        unregisterNetworkCallbacks()
        activeNetworkPaths.clear()
        wifiIfaceName = null
        simIfaceName  = null
        availableWifiNetwork       = null
        availableWifiLocalIp       = null
        availableSimNetwork        = null
        availableSimLocalIp        = null
        estimatedWifiBwBytesPerSec = 0L
        estimatedSimBwBytesPerSec  = 0L
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
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_STATE).apply {
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
    private external fun startGlorytunNative(fd: Int, ip: String, port: String, secret: String, keyfilePath: String): Int
    private external fun stopGlorytunNative()
    private external fun isGlorytunReady(): Boolean
    private external fun addPathForNetwork(localIp: String, networkHandle: Long): Int
    private external fun removePathForNetwork(localIp: String): Int
    /**
     * 指定パスの送受信レート上限を bytes/s で設定する。
     * 0 を渡すとデフォルト (10 MB/s) に戻る。
     * ボンディングモードでは推定帯域幅を、優先モードでは不足分をそれぞれ渡す。
     * 輻輳制御は有効のまま（tx.rate は tx_max_rate を上限として動的に変化する）。
     */
    private external fun setPathMaxRate(localIp: String, txRateBytesPerSec: Long, rxRateBytesPerSec: Long): Int

    /**
     * 帯域幅スロットル専用。fixed_rate を制御し輻輳制御による
     * tx.rate の上書きを防ぐことで、設定速度を正確に維持する。
     * enable=true  → スロットルON（fixed_rate 有効化）
     * enable=false → スロットルOFF（fixed_rate 無効化・輻輳制御を復帰）
     */
    private external fun setPathThrottleRate(localIp: String, txRateBytesPerSec: Long, rxRateBytesPerSec: Long, enable: Boolean): Int

    /** 指定ローカルIPのパス統計を返す: [tx_bytes, rx_bytes, tx_rate, rx_rate]、パス未存在時はnull */
    private external fun getPathStatsForIp(localIp: String): LongArray?
}
