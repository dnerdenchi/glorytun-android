package com.example.glorytun

import com.mqvpn.sdk.core.model.PathInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTrafficAccumulatorTest {
    @Test
    fun pathReconnectKeepsCumulativeTotalsAndResumesFromNewPathBaseline() {
        val accumulator = PathTrafficAccumulator()

        accumulator.update(listOf(path(handle = 1L, iface = "wifi-10", rx = 1_000L)))
        val beforeReconnect = accumulator.update(listOf(path(handle = 1L, iface = "wifi-10", rx = 5_000L)))

        assertEquals(4_000L, beforeReconnect.totals.wifiTotal)
        assertEquals(4_000f / 1024f, beforeReconnect.wifiDeltaKB, 0.001f)
        assertTrue(beforeReconnect.totals.wifiActive)

        val lostPath = accumulator.update(emptyList())

        assertEquals(4_000L, lostPath.totals.wifiTotal)
        assertEquals(0f, lostPath.wifiDeltaKB, 0.001f)
        assertFalse(lostPath.totals.wifiActive)

        val newPathBaseline = accumulator.update(listOf(path(handle = 2L, iface = "wifi-22", rx = 1_000L)))

        assertEquals(4_000L, newPathBaseline.totals.wifiTotal)
        assertEquals(0f, newPathBaseline.wifiDeltaKB, 0.001f)
        assertTrue(newPathBaseline.totals.wifiActive)

        val afterReconnect = accumulator.update(listOf(path(handle = 2L, iface = "wifi-22", rx = 2_500L)))

        assertEquals(5_500L, afterReconnect.totals.wifiTotal)
        assertEquals(1_500f / 1024f, afterReconnect.wifiDeltaKB, 0.001f)
    }

    @Test
    fun wifiAndCellularDeltasStaySeparated() {
        val accumulator = PathTrafficAccumulator()

        accumulator.update(
            listOf(
                path(handle = 1L, iface = "wifi-10", tx = 100L, rx = 200L),
                path(handle = 2L, iface = "cellular-11", tx = 300L, rx = 400L)
            )
        )

        val update = accumulator.update(
            listOf(
                path(handle = 1L, iface = "wifi-10", tx = 600L, rx = 1_200L),
                path(handle = 2L, iface = "cellular-11", tx = 1_300L, rx = 2_400L)
            )
        )

        assertEquals(500L, update.totals.wifiTx)
        assertEquals(1_000L, update.totals.wifiRx)
        assertEquals(1_000L, update.totals.simTx)
        assertEquals(2_000L, update.totals.simRx)
        assertEquals(1_500f / 1024f, update.wifiDeltaKB, 0.001f)
        assertEquals(3_000f / 1024f, update.simDeltaKB, 0.001f)
    }

    @Test
    fun resetClearsBaselinesAndCumulativeTotals() {
        val accumulator = PathTrafficAccumulator()

        accumulator.update(listOf(path(handle = 1L, iface = "wifi-10", rx = 1_000L)))
        accumulator.update(listOf(path(handle = 1L, iface = "wifi-10", rx = 5_000L)))
        accumulator.reset()

        val update = accumulator.update(listOf(path(handle = 1L, iface = "wifi-10", rx = 9_000L)))

        assertEquals(0L, update.totals.wifiTotal)
        assertEquals(0f, update.wifiDeltaKB, 0.001f)
    }

    private fun path(
        handle: Long,
        iface: String,
        tx: Long = 0L,
        rx: Long = 0L
    ): PathInfo {
        return PathInfo(
            handle = handle,
            status = 0,
            iface = iface,
            bytesTx = tx,
            bytesRx = rx,
            srttMs = 0L
        )
    }
}
