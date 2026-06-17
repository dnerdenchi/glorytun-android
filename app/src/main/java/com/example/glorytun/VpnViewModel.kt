package com.example.glorytun

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.Calendar

data class TrafficPoint(val timestamp: Long, val wifiKBs: Float, val simKBs: Float)

data class ServerCheckEntry(
    val checkedAt: Long,
    val reachable: Boolean,
    val detail: String,
    val rttMs: Long
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    val trafficDataStore = TrafficDataStore(application)
    val historicalPoints = mutableListOf<TrafficPoint>()
    val trafficHistory = mutableListOf<TrafficPoint>()

    val realtimeWifiRates = ArrayDeque<Float>()
    val realtimeSimRates = ArrayDeque<Float>()
    val realtimeUpdated = MutableLiveData(0L)

    val connectionState = MutableLiveData(ConnectionStates.DISCONNECTED)

    val wifiKBs = MutableLiveData(0f)
    val simKBs = MutableLiveData(0f)
    val wifiTotalBytes = MutableLiveData(0L)
    val simTotalBytes = MutableLiveData(0L)

    val wifiDailyBytes = MutableLiveData(0L)
    val simDailyBytes = MutableLiveData(0L)

    val wifiThrottled = MutableLiveData(false)
    val simThrottled = MutableLiveData(false)

    val maxWifiKBs = MutableLiveData(0f)
    val maxSimKBs = MutableLiveData(0f)

    val serverIp = MutableLiveData("")
    val serverPort = MutableLiveData(MqvpnConfigFactory.DEFAULT_PORT)
    val serverCheckCache = mutableMapOf<String, ServerCheckEntry>()

    private val trafficRateCalculator = TrafficRateCalculator()

    init {
        historicalPoints.addAll(trafficDataStore.load())

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var dWifi = 0L
        var dSim = 0L
        for (point in historicalPoints) {
            if (point.timestamp >= todayStart) {
                dWifi += (point.wifiKBs * 3600 * 1024).toLong()
                dSim += (point.simKBs * 3600 * 1024).toLong()
            }
        }
        wifiDailyBytes.value = dWifi
        simDailyBytes.value = dSim
    }

    fun addHistoricalPoint(point: TrafficPoint) {
        val existingIdx = historicalPoints.indexOfFirst {
            it.timestamp / GlorytunConstants.MILLIS_IN_HOUR ==
                point.timestamp / GlorytunConstants.MILLIS_IN_HOUR
        }
        if (existingIdx >= 0) {
            historicalPoints[existingIdx] = point
        } else {
            historicalPoints.add(point)
        }
    }

    fun updateTraffic(
        wifiActive: Boolean,
        simActive: Boolean,
        wifiTx: Long,
        wifiRx: Long,
        simTx: Long,
        simRx: Long
    ) {
        val nowMs = System.currentTimeMillis()
        val rates = trafficRateCalculator.update(
            nowMs = nowMs,
            wifiActive = wifiActive,
            simActive = simActive,
            wifiTx = wifiTx,
            wifiRx = wifiRx,
            simTx = simTx,
            simRx = simRx
        )

        wifiKBs.value = rates.wifiKBs
        simKBs.value = rates.simKBs

        if (rates.wifiKBs > (maxWifiKBs.value ?: 0f)) maxWifiKBs.value = rates.wifiKBs
        if (rates.simKBs > (maxSimKBs.value ?: 0f)) maxSimKBs.value = rates.simKBs

        trafficHistory.add(TrafficPoint(nowMs, rates.wifiKBs, rates.simKBs))
        while (trafficHistory.size > MAX_TRAFFIC_HISTORY_POINTS) trafficHistory.removeAt(0)

        addRealtimePoint(rates.wifiKBs, rates.simKBs)

        wifiTotalBytes.value = rates.wifiTotalBytes
        simTotalBytes.value = rates.simTotalBytes
    }

    fun addRealtimePoint(wifi: Float, sim: Float) {
        if (realtimeWifiRates.size >= MAX_REALTIME_POINTS) realtimeWifiRates.removeFirst()
        if (realtimeSimRates.size >= MAX_REALTIME_POINTS) realtimeSimRates.removeFirst()
        realtimeWifiRates.addLast(wifi.coerceAtLeast(0f))
        realtimeSimRates.addLast(sim.coerceAtLeast(0f))
        realtimeUpdated.value = System.currentTimeMillis()
    }

    fun resetRealtimeData() {
        realtimeWifiRates.clear()
        realtimeSimRates.clear()
        realtimeUpdated.postValue(0L)
    }

    fun resetTrafficBaselines(clearSessionHistory: Boolean = false) {
        trafficRateCalculator.reset()
        wifiKBs.value = 0f
        simKBs.value = 0f
        wifiTotalBytes.value = 0L
        simTotalBytes.value = 0L
        if (clearSessionHistory) {
            trafficHistory.clear()
            resetRealtimeData()
        }
    }

    fun updateDailyTraffic(
        wifiKB: Double,
        simKB: Double,
        wThrottled: Boolean,
        sThrottled: Boolean
    ) {
        wifiDailyBytes.value = (wifiKB * 1024).toLong()
        simDailyBytes.value = (simKB * 1024).toLong()
        wifiThrottled.value = wThrottled
        simThrottled.value = sThrottled
    }

    fun reset() {
        resetTrafficBaselines(clearSessionHistory = true)
        wifiThrottled.value = false
        simThrottled.value = false
    }

    fun formatBps(kbs: Float): String {
        val bps = kbs * 8192f
        return when {
            bps >= 100_000f -> "%.2f Mbps".format(bps / 1_000_000f)
            bps >= 1000f -> "%.1f kbps".format(bps / 1000f)
            else -> "%.0f bps".format(bps)
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576f)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private companion object {
        const val MAX_REALTIME_POINTS = 60
        const val MAX_TRAFFIC_HISTORY_POINTS = 86_400
    }
}
