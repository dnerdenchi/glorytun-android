package com.example.glorytun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mqvpn.sdk.core.MqvpnVpnService
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.TunnelInfo
import java.util.Calendar

class MqvpnBondingService : MqvpnVpnService() {

    private lateinit var trafficDataStore: TrafficDataStore
    private val statsHandler = Handler(Looper.getMainLooper())

    @Volatile private var latestPaths: List<PathInfo> = emptyList()
    @Volatile private var isTunnelRequested = false
    @Volatile private var isConnected = false

    private var statsRunning = false
    private val pathTrafficAccumulator = PathTrafficAccumulator()
    private var dailyWifiKB = 0.0
    private var dailySimKB = 0.0
    private var monthlyWifiKB = 0.0
    private var monthlySimKB = 0.0
    private var currentHourBucket = 0L
    private var hourlyWifiSum = 0f
    private var hourlySimSum = 0f
    private var hourlySampleCount = 0
    private var lastDayStartMs = 0L
    private var lastMonthStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        trafficDataStore = TrafficDataStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            GlorytunConstants.ACTION_CONNECT -> {
                runCatching {
                    connect(MqvpnConfigFactory.fromIntent(this, intent))
                }.onFailure { error ->
                    Log.e(TAG, "invalid mqvpn config: ${error.message}", error)
                    isTunnelRequested = false
                    isConnected = false
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    sendVpnState(ConnectionStates.DISCONNECTED)
                    stopSelf(startId)
                }
                START_STICKY
            }
            GlorytunConstants.ACTION_DISCONNECT -> {
                disconnect()
                START_NOT_STICKY
            }
            GlorytunConstants.ACTION_QUERY_STATE -> {
                sendVpnState(
                    when {
                        isConnected -> ConnectionStates.VPN_CONNECTED
                        isTunnelRequested -> ConnectionStates.VPN_CONNECTING
                        else -> ConnectionStates.DISCONNECTED
                    }
                )
                if (!isTunnelRequested) stopSelf(startId)
                if (isTunnelRequested) START_STICKY else START_NOT_STICKY
            }
            else -> if (isTunnelRequested) START_STICKY else START_NOT_STICKY
        }
    }

    private fun connect(config: MqvpnConfig) {
        if (isTunnelRequested) return

        isTunnelRequested = true
        isConnected = false
        latestPaths = emptyList()
        resetStatsBaselines()
        startForegroundNotification("mqvpn 接続を準備中")
        sendVpnState(ConnectionStates.VPN_CONNECTING)
        startTunnel(config)
    }

    private fun disconnect() {
        if (!isTunnelRequested) {
            sendVpnState(ConnectionStates.DISCONNECTED)
            stopSelf()
            return
        }

        isTunnelRequested = false
        isConnected = false
        stopStats()
        sendVpnState(ConnectionStates.DISCONNECTING)
        stopTunnel()
    }

    override fun onCreateTun(info: TunnelInfo, config: MqvpnConfig): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("BondVPN mqvpn")
            .addAddress(info.assignedIp, info.prefix)
            .addRoute("0.0.0.0", 0)
            .setMtu(info.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        val assignedIp6 = info.assignedIp6
        if (info.hasV6 && assignedIp6 != null) {
            builder.addAddress(assignedIp6, info.prefix6)
            builder.addRoute("::", 0)
        } else if (config.killSwitch) {
            builder.addRoute("::", 0)
        }

        config.dnsServers.forEach { builder.addDnsServer(it) }

        return builder.establish()
            ?: throw IllegalStateException("VPN 権限がありません")
    }

    override fun onVpnStateChanged(newState: MqvpnState) {
        when (newState) {
            is MqvpnState.Connecting -> {
                updateNotification("mqvpn 接続中")
                sendVpnState(ConnectionStates.VPN_CONNECTING)
            }
            is MqvpnState.Reconnecting -> {
                updateNotification("mqvpn 再接続中")
                sendVpnState(ConnectionStates.VPN_CONNECTING)
            }
            is MqvpnState.Connected -> {
                isConnected = true
                isTunnelRequested = true
                updateNotification("接続済み: ${newState.tunnelInfo.assignedIp}")
                sendVpnState(ConnectionStates.VPN_CONNECTED)
                startStats()
            }
            is MqvpnState.Disconnected -> {
                isConnected = false
                isTunnelRequested = false
                latestPaths = emptyList()
                stopStats()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                sendVpnState(ConnectionStates.DISCONNECTED)
                stopSelf()
            }
            is MqvpnState.Error -> {
                Log.e(TAG, "mqvpn error: ${newState.error.message}")
                isConnected = false
                isTunnelRequested = false
                stopStats()
                updateNotification("mqvpn エラー: ${newState.error.message}")
                sendVpnState(ConnectionStates.DISCONNECTED)
                stopSelf()
            }
        }
    }

    override fun onPathsUpdated(paths: List<PathInfo>) {
        latestPaths = paths
    }

    override fun onLog(level: Int, message: String) {
        when (level) {
            0 -> Log.d(TAG, message)
            1 -> Log.i(TAG, message)
            2 -> Log.w(TAG, message)
            3 -> Log.e(TAG, message)
        }
    }

    override fun onDestroy() {
        stopStats()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            GlorytunConstants.CHANNEL_ID,
            GlorytunConstants.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "mqvpn 接続状態の通知"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification(contentText: String) {
        val notification = buildNotification(contentText)
        ServiceCompat.startForeground(
            this,
            GlorytunConstants.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(GlorytunConstants.NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String) =
        NotificationCompat.Builder(this, GlorytunConstants.CHANNEL_ID)
            .setContentTitle("BondVPN")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startStats() {
        if (statsRunning) return
        initializeUsageCounters()
        statsRunning = true
        statsHandler.post(statsRunnable)
    }

    private fun stopStats() {
        if (!statsRunning) return
        statsRunning = false
        statsHandler.removeCallbacks(statsRunnable)
        saveCurrentHour()
        resetStatsBaselines()
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!statsRunning) return

            val trafficUpdate = pathTrafficAccumulator.update(latestPaths)

            updateUsageCounters(trafficUpdate.wifiDeltaKB, trafficUpdate.simDeltaKB)
            broadcastStats(trafficUpdate.totals)

            statsHandler.postDelayed(this, 1000)
        }
    }

    private fun initializeUsageCounters() {
        resetStatsBaselines()

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        lastDayStartMs = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        lastMonthStartMs = cal.timeInMillis

        dailyWifiKB = 0.0
        dailySimKB = 0.0
        monthlyWifiKB = 0.0
        monthlySimKB = 0.0

        trafficDataStore.load().forEach { point ->
            if (point.timestamp >= lastDayStartMs) {
                dailyWifiKB += point.wifiKBs * 3600.0
                dailySimKB += point.simKBs * 3600.0
            }
            if (point.timestamp >= lastMonthStartMs) {
                monthlyWifiKB += point.wifiKBs * 3600.0
                monthlySimKB += point.simKBs * 3600.0
            }
        }
    }

    private fun resetStatsBaselines() {
        pathTrafficAccumulator.reset()
        currentHourBucket = 0L
        hourlyWifiSum = 0f
        hourlySimSum = 0f
        hourlySampleCount = 0
    }

    private fun updateUsageCounters(wifiDeltaKB: Float, simDeltaKB: Float) {
        dailyWifiKB += wifiDeltaKB
        dailySimKB += simDeltaKB
        monthlyWifiKB += wifiDeltaKB
        monthlySimKB += simDeltaKB

        val nowMs = System.currentTimeMillis()
        checkTimeBoundary(nowMs)
        val nowHour = nowMs / GlorytunConstants.MILLIS_IN_HOUR
        if (currentHourBucket == 0L) currentHourBucket = nowHour
        if (nowHour != currentHourBucket) {
            saveCurrentHour()
            currentHourBucket = nowHour
        }

        hourlyWifiSum += wifiDeltaKB
        hourlySimSum += simDeltaKB
        hourlySampleCount++
    }

    private fun saveCurrentHour() {
        if (hourlySampleCount == 0 || currentHourBucket == 0L) return

        val timestamp = currentHourBucket * GlorytunConstants.MILLIS_IN_HOUR
        val point = TrafficPoint(
            timestamp = timestamp,
            wifiKBs = hourlyWifiSum / hourlySampleCount,
            simKBs = hourlySimSum / hourlySampleCount
        )
        Thread { trafficDataStore.appendPoint(point) }.start()

        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_HOURLY_STATS).apply {
            setPackage(packageName)
            putExtra("timestamp", timestamp)
            putExtra("wifi_kbs", point.wifiKBs)
            putExtra("sim_kbs", point.simKBs)
        })

        hourlyWifiSum = 0f
        hourlySimSum = 0f
        hourlySampleCount = 0
    }

    private fun checkTimeBoundary(nowMs: Long) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dayStart = cal.timeInMillis
        if (dayStart > lastDayStartMs) {
            lastDayStartMs = dayStart
            dailyWifiKB = 0.0
            dailySimKB = 0.0
        }

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis
        if (monthStart > lastMonthStartMs) {
            lastMonthStartMs = monthStart
            monthlyWifiKB = 0.0
            monthlySimKB = 0.0
        }
    }

    private fun broadcastStats(totals: NetworkTrafficTotals) {
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_TRAFFIC_STATS).apply {
            setPackage(packageName)
            putExtra("wifi_tx_bytes", totals.wifiTx)
            putExtra("wifi_rx_bytes", totals.wifiRx)
            putExtra("wifi_active", totals.wifiActive)
            putExtra("sim_tx_bytes", totals.simTx)
            putExtra("sim_rx_bytes", totals.simRx)
            putExtra("sim_active", totals.simActive)
            putExtra("stats_source", GlorytunConstants.STATE_SOURCE_VPN)
            putExtra("daily_wifi_kb", dailyWifiKB)
            putExtra("daily_sim_kb", dailySimKB)
            putExtra("wifi_throttled", false)
            putExtra("sim_throttled", false)
        })
    }

    private fun sendVpnState(state: String) {
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_STATE).apply {
            setPackage(packageName)
            putExtra(GlorytunConstants.EXTRA_STATE, state)
            putExtra(GlorytunConstants.EXTRA_STATE_SOURCE, GlorytunConstants.STATE_SOURCE_VPN)
        })
    }

    companion object {
        private const val TAG = "MqvpnBondingService"
    }
}
