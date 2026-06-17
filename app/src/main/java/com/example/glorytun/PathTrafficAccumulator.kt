package com.example.glorytun

import com.mqvpn.sdk.core.model.PathInfo

data class NetworkTrafficTotals(
    val wifiTx: Long,
    val wifiRx: Long,
    val wifiActive: Boolean,
    val simTx: Long,
    val simRx: Long,
    val simActive: Boolean
) {
    val wifiTotal: Long get() = wifiTx + wifiRx
    val simTotal: Long get() = simTx + simRx
}

data class PathTrafficUpdate(
    val totals: NetworkTrafficTotals,
    val wifiDeltaKB: Float,
    val simDeltaKB: Float
)

class PathTrafficAccumulator {
    private val previousByHandle = mutableMapOf<Long, PathCounter>()
    private var cumulativeWifiTx = 0L
    private var cumulativeWifiRx = 0L
    private var cumulativeSimTx = 0L
    private var cumulativeSimRx = 0L

    fun update(paths: List<PathInfo>): PathTrafficUpdate {
        var wifiDeltaBytes = 0L
        var simDeltaBytes = 0L
        var wifiActive = false
        var simActive = false
        val activeHandles = mutableSetOf<Long>()

        paths.forEach { path ->
            activeHandles.add(path.handle)

            val group = path.transportGroup()
            if (group == TransportGroup.WIFI) {
                wifiActive = true
            } else {
                simActive = true
            }

            val current = PathCounter(
                tx = path.bytesTx.coerceAtLeast(0L),
                rx = path.bytesRx.coerceAtLeast(0L)
            )
            val previous = previousByHandle[path.handle]
            if (previous != null) {
                val deltaTx = (current.tx - previous.tx).coerceAtLeast(0L)
                val deltaRx = (current.rx - previous.rx).coerceAtLeast(0L)
                if (group == TransportGroup.WIFI) {
                    cumulativeWifiTx += deltaTx
                    cumulativeWifiRx += deltaRx
                    wifiDeltaBytes += deltaTx + deltaRx
                } else {
                    cumulativeSimTx += deltaTx
                    cumulativeSimRx += deltaRx
                    simDeltaBytes += deltaTx + deltaRx
                }
            }
            previousByHandle[path.handle] = current
        }

        previousByHandle.keys.retainAll(activeHandles)

        return PathTrafficUpdate(
            totals = NetworkTrafficTotals(
                wifiTx = cumulativeWifiTx,
                wifiRx = cumulativeWifiRx,
                wifiActive = wifiActive,
                simTx = cumulativeSimTx,
                simRx = cumulativeSimRx,
                simActive = simActive
            ),
            wifiDeltaKB = wifiDeltaBytes / 1024f,
            simDeltaKB = simDeltaBytes / 1024f
        )
    }

    fun reset() {
        previousByHandle.clear()
        cumulativeWifiTx = 0L
        cumulativeWifiRx = 0L
        cumulativeSimTx = 0L
        cumulativeSimRx = 0L
    }

    private fun PathInfo.transportGroup(): TransportGroup {
        val lower = iface.lowercase()
        return when {
            lower.startsWith("cellular") ||
                lower.startsWith("rmnet") ||
                lower.startsWith("ccmni") ||
                lower.startsWith("wwan") ||
                lower.startsWith("pdp") -> TransportGroup.CELLULAR
            else -> TransportGroup.WIFI
        }
    }

    private data class PathCounter(
        val tx: Long,
        val rx: Long
    )

    private enum class TransportGroup {
        WIFI,
        CELLULAR
    }
}
