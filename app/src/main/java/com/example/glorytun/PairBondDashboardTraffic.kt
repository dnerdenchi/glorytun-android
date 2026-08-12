package com.example.glorytun

internal data class PairBondDashboardTrafficUpdate(
    val totals: NetworkTrafficTotals,
    val wifiDeltaKB: Float,
    val simDeltaKB: Float,
    val wifiBps: Long,
    val simBps: Long,
    val pairShareBps: Long,
    val receivingPeerNames: List<String>,
)

/** Converts PairBond's cumulative per-path counters into one-second dashboard samples. */
internal class PairBondDashboardTrafficAccumulator {
    private data class Counter(val tx: Long, val rx: Long)

    private val previousById = mutableMapOf<String, Counter>()
    private var previousSampleMillis: Long? = null
    private var cumulativeWifiTx = 0L
    private var cumulativeWifiRx = 0L
    private var cumulativeSimTx = 0L
    private var cumulativeSimRx = 0L

    fun update(
        stats: Collection<PairSharePeerStats>,
        peerNames: Map<String, String>,
        nowMillis: Long,
    ): PairBondDashboardTrafficUpdate {
        var wifiDeltaTx = 0L
        var wifiDeltaRx = 0L
        var simDeltaTx = 0L
        var simDeltaRx = 0L
        var pairDelta = 0L
        val activeIds = mutableSetOf<String>()
        val receivingNames = linkedSetOf<String>()

        stats.forEach { path ->
            activeIds += path.peerId
            val current = Counter(path.txBytes.coerceAtLeast(0L), path.rxBytes.coerceAtLeast(0L))
            val previous = previousById[path.peerId] ?: current
            val deltaTx = (current.tx - previous.tx).coerceAtLeast(0L)
            val deltaRx = (current.rx - previous.rx).coerceAtLeast(0L)
            when (path.peerId) {
                PairBondLocalPath.WIFI_ID -> {
                    wifiDeltaTx += deltaTx
                    wifiDeltaRx += deltaRx
                }
                PairBondLocalPath.CELLULAR_ID -> {
                    simDeltaTx += deltaTx
                    simDeltaRx += deltaRx
                }
                else -> {
                    pairDelta += deltaTx + deltaRx
                    peerNames[path.peerId]?.let(receivingNames::add)
                }
            }
            previousById[path.peerId] = current
        }
        previousById.keys.retainAll(activeIds)

        cumulativeWifiTx += wifiDeltaTx
        cumulativeWifiRx += wifiDeltaRx
        cumulativeSimTx += simDeltaTx
        cumulativeSimRx += simDeltaRx

        val elapsedMillis = previousSampleMillis
            ?.let { (nowMillis - it).coerceAtLeast(1L) }
        previousSampleMillis = nowMillis

        fun toBitsPerSecond(bytes: Long): Long = if (elapsedMillis == null) {
            0L
        } else {
            bytes.coerceAtLeast(0L) * 8_000L / elapsedMillis
        }

        return PairBondDashboardTrafficUpdate(
            totals = NetworkTrafficTotals(
                wifiTx = cumulativeWifiTx,
                wifiRx = cumulativeWifiRx,
                wifiActive = stats.any { it.peerId == PairBondLocalPath.WIFI_ID },
                simTx = cumulativeSimTx,
                simRx = cumulativeSimRx,
                simActive = stats.any { it.peerId == PairBondLocalPath.CELLULAR_ID },
            ),
            wifiDeltaKB = (wifiDeltaTx + wifiDeltaRx) / 1024f,
            simDeltaKB = (simDeltaTx + simDeltaRx) / 1024f,
            wifiBps = toBitsPerSecond(wifiDeltaTx + wifiDeltaRx),
            simBps = toBitsPerSecond(simDeltaTx + simDeltaRx),
            pairShareBps = toBitsPerSecond(pairDelta),
            receivingPeerNames = receivingNames.toList(),
        )
    }

    fun reset() {
        previousById.clear()
        previousSampleMillis = null
        cumulativeWifiTx = 0L
        cumulativeWifiRx = 0L
        cumulativeSimTx = 0L
        cumulativeSimRx = 0L
    }
}
