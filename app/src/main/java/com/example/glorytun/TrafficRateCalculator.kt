package com.example.glorytun

data class TrafficRates(
    val wifiKBs: Float,
    val simKBs: Float,
    val wifiTotalBytes: Long,
    val simTotalBytes: Long
)

class TrafficRateCalculator {
    private var previousWifiTotal: Long? = null
    private var previousSimTotal: Long? = null
    private var previousTimestampMs: Long? = null

    fun update(
        nowMs: Long,
        wifiActive: Boolean,
        simActive: Boolean,
        wifiTx: Long,
        wifiRx: Long,
        simTx: Long,
        simRx: Long
    ): TrafficRates {
        val elapsedSeconds = previousTimestampMs
            ?.let { ((nowMs - it) / 1000f).coerceAtLeast(MIN_ELAPSED_SECONDS) }

        val wifiTotal = (wifiTx + wifiRx).coerceAtLeast(0L)
        val simTotal = (simTx + simRx).coerceAtLeast(0L)

        val wifiKBs = calculateRateKBs(wifiActive, wifiTotal, previousWifiTotal, elapsedSeconds)
        val simKBs = calculateRateKBs(simActive, simTotal, previousSimTotal, elapsedSeconds)

        previousWifiTotal = if (wifiActive) wifiTotal else null
        previousSimTotal = if (simActive) simTotal else null
        previousTimestampMs = if (wifiActive || simActive) nowMs else null

        return TrafficRates(
            wifiKBs = wifiKBs,
            simKBs = simKBs,
            wifiTotalBytes = if (wifiActive) wifiTotal else 0L,
            simTotalBytes = if (simActive) simTotal else 0L
        )
    }

    fun reset() {
        previousWifiTotal = null
        previousSimTotal = null
        previousTimestampMs = null
    }

    private fun calculateRateKBs(
        active: Boolean,
        currentTotal: Long,
        previousTotal: Long?,
        elapsedSeconds: Float?
    ): Float {
        if (!active || previousTotal == null || elapsedSeconds == null) return 0f
        val deltaBytes = (currentTotal - previousTotal).coerceAtLeast(0L)
        return deltaBytes / 1024f / elapsedSeconds
    }

    private companion object {
        const val MIN_ELAPSED_SECONDS = 0.001f
    }
}
