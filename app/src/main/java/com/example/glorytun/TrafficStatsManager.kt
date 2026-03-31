package com.example.glorytun

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import java.util.Calendar

class TrafficStatsManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onHourlyAggregated(timestamp: Long, wifiAvgKBps: Float, simAvgKBps: Float)
        fun onDayReset()
        fun onMonthReset()
        fun getWifiIface(): String?
        fun getSimIface(): String?
        fun getCurrentEstimatedWifiBw(): Long
        fun getCurrentEstimatedSimBw(): Long
        fun onStatsProcessed(wifiDeltaKB: Float, simDeltaKB: Float)
        fun getDailyWifiKB(): Double
        fun getDailySimKB(): Double
        fun isWifiThrottled(): Boolean
        fun isSimThrottled(): Boolean
    }

    private val statsHandler = Handler(Looper.getMainLooper())
    private var isConnected = false

    private var prevWifiTx = -1L
    private var prevWifiRx = 0L
    private var prevSimTx = -1L
    private var prevSimRx = 0L

    private var currentHourBucket = 0L
    private var hourlyWifiSum = 0f
    private var hourlySimSum = 0f
    private var hourlySampleCount = 0

    private var lastDayStartMs = 0L
    private var lastMonthStartMs = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) return

            val wIface = listener.getWifiIface()
            val sIface = listener.getSimIface()

            val wifiTx = if (wIface != null) TrafficStats.getTxBytes(wIface) else -1L
            val wifiRx = if (wIface != null) TrafficStats.getRxBytes(wIface) else 0L
            val simTx = if (sIface != null) TrafficStats.getTxBytes(sIface) else -1L
            val simRx = if (sIface != null) TrafficStats.getRxBytes(sIface) else 0L

            val safeWifiRx = if (wifiRx >= 0L) wifiRx else 0L
            val safeSimRx = if (simRx >= 0L) simRx else 0L

            val wifiDeltaKB = if (prevWifiTx >= 0 && wifiTx >= 0)
                ((wifiTx - prevWifiTx) + (wifiRx - prevWifiRx)).coerceAtLeast(0L) / 1024f
            else 0f

            val simDeltaKB = if (prevSimTx >= 0 && simTx >= 0)
                ((simTx - prevSimTx) + (simRx - prevSimRx)).coerceAtLeast(0L) / 1024f
            else 0f

            prevWifiTx = if (wifiTx >= 0) wifiTx else -1L
            prevWifiRx = if (wifiRx >= 0) wifiRx else 0L
            prevSimTx = if (simTx >= 0) simTx else -1L
            prevSimRx = if (simRx >= 0) simRx else 0L

            updateAggregation(wifiDeltaKB, simDeltaKB)
            
            listener.onStatsProcessed(wifiDeltaKB, simDeltaKB)
            
            broadcastStats(wifiTx, safeWifiRx, wIface != null && wifiTx >= 0L,
                           simTx, safeSimRx, sIface != null && simTx >= 0L)

            statsHandler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (isConnected) return
        isConnected = true
        resetTracking()
        statsHandler.post(statsRunnable)
    }

    fun stop() {
        isConnected = false
        statsHandler.removeCallbacks(statsRunnable)
        if (hourlySampleCount > 0) {
            saveCurrentHour()
        }
    }

    private fun resetTracking() {
        prevWifiTx = -1L
        prevSimTx = -1L
        currentHourBucket = 0L
        hourlyWifiSum = 0f
        hourlySimSum = 0f
        hourlySampleCount = 0
        
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        lastDayStartMs = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        lastMonthStartMs = cal.timeInMillis
    }

    private fun updateAggregation(wifiDeltaKB: Float, simDeltaKB: Float) {
        val nowMs = System.currentTimeMillis()
        val nowHour = nowMs / GlorytunConstants.MILLIS_IN_HOUR

        if (currentHourBucket == 0L) currentHourBucket = nowHour

        if (nowHour != currentHourBucket) {
            saveCurrentHour()
            currentHourBucket = nowHour
            checkTimeBoundary(nowMs)
        }

        hourlyWifiSum += wifiDeltaKB
        hourlySimSum += simDeltaKB
        hourlySampleCount++
    }

    private fun saveCurrentHour() {
        if (hourlySampleCount == 0) return
        val avgWifi = hourlyWifiSum / hourlySampleCount
        val avgSim = hourlySimSum / hourlySampleCount
        listener.onHourlyAggregated(currentHourBucket * GlorytunConstants.MILLIS_IN_HOUR, avgWifi, avgSim)
        hourlyWifiSum = 0f; hourlySimSum = 0f; hourlySampleCount = 0
    }

    private fun checkTimeBoundary(nowMs: Long) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val midnightMs = cal.timeInMillis
        
        if (midnightMs > lastDayStartMs) {
            lastDayStartMs = midnightMs
            listener.onDayReset()
        }

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStartMs = cal.timeInMillis
        if (monthStartMs > lastMonthStartMs) {
            lastMonthStartMs = monthStartMs
            listener.onMonthReset()
        }
    }

    private fun broadcastStats(wTx: Long, wRx: Long, wActive: Boolean,
                               sTx: Long, sRx: Long, sActive: Boolean) {
        context.sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_TRAFFIC_STATS).apply {
            setPackage(context.packageName)
            putExtra("wifi_tx_bytes", if (wTx >= 0L) wTx else 0L)
            putExtra("wifi_rx_bytes", wRx)
            putExtra("wifi_active",   wActive)
            putExtra("sim_tx_bytes",  if (sTx >= 0L) sTx else 0L)
            putExtra("sim_rx_bytes",  sRx)
            putExtra("sim_active",    sActive)
            putExtra("wifi_est_bw_bytes", listener.getCurrentEstimatedWifiBw())
            putExtra("sim_est_bw_bytes",  listener.getCurrentEstimatedSimBw())
            putExtra("daily_wifi_kb", listener.getDailyWifiKB())
            putExtra("daily_sim_kb",  listener.getDailySimKB())
            putExtra("wifi_throttled", listener.isWifiThrottled())
            putExtra("sim_throttled",  listener.isSimThrottled())
        })
    }
}
