package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairBondDashboardTrafficTest {
    @Test
    fun separatesLocalWifiLocalSimAndReceivedPeerTraffic() {
        val accumulator = PairBondDashboardTrafficAccumulator()
        val names = mapOf("oppo" to "OPPO CPH2353")

        accumulator.update(
            stats = listOf(
                stats(PairBondLocalPath.WIFI_ID, 1_000L, 2_000L),
                stats(PairBondLocalPath.CELLULAR_ID, 3_000L, 4_000L),
                stats("oppo", 5_000L, 6_000L),
            ),
            peerNames = names,
            nowMillis = 1_000L,
        )

        val update = accumulator.update(
            stats = listOf(
                stats(PairBondLocalPath.WIFI_ID, 11_000L, 22_000L),
                stats(PairBondLocalPath.CELLULAR_ID, 33_000L, 44_000L),
                stats("oppo", 55_000L, 66_000L),
            ),
            peerNames = names,
            nowMillis = 2_000L,
        )

        assertEquals(10_000L, update.totals.wifiTx)
        assertEquals(20_000L, update.totals.wifiRx)
        assertEquals(30_000L, update.totals.simTx)
        assertEquals(40_000L, update.totals.simRx)
        assertEquals(30_000L * 8L, update.wifiBps)
        assertEquals(70_000L * 8L, update.simBps)
        assertEquals(110_000L * 8L, update.pairShareBps)
        assertEquals(listOf("OPPO CPH2353"), update.receivingPeerNames)
        assertTrue(update.totals.wifiActive)
        assertTrue(update.totals.simActive)
    }

    @Test
    fun counterResetDoesNotCreateANegativeOrHugeRate() {
        val accumulator = PairBondDashboardTrafficAccumulator()
        accumulator.update(
            listOf(stats(PairBondLocalPath.WIFI_ID, 10_000L, 20_000L)),
            emptyMap(),
            1_000L,
        )

        val update = accumulator.update(
            listOf(stats(PairBondLocalPath.WIFI_ID, 100L, 200L)),
            emptyMap(),
            2_000L,
        )

        assertEquals(0L, update.wifiBps)
        assertEquals(0L, update.totals.wifiTotal)
    }

    private fun stats(id: String, tx: Long, rx: Long) = PairSharePeerStats(
        peerId = id,
        txBytes = tx,
        rxBytes = rx,
    )
}
