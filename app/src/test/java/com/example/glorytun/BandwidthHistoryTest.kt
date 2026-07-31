package com.example.glorytun

import com.mqvpn.sdk.core.model.PathInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandwidthHistoryTest {
    private fun path(
        handle: Long = 1L,
        iface: String,
        tx: Long,
        rx: Long,
    ) = PathInfo(
        handle = handle,
        status = 0,
        iface = iface,
        bytesTx = tx,
        bytesRx = rx,
        srttMs = 10L,
    )

    @Test
    fun firstTickEstablishesBaselineAndSecondTickComputesBitsPerSecond() {
        val history = BandwidthHistory()

        val first = history.onTick(
            listOf(path(iface = "wifi-1", tx = 1_000, rx = 2_000)),
            1_000_000_000L,
        )
        val second = history.onTick(
            listOf(path(iface = "wifi-1", tx = 2_000, rx = 2_250)),
            2_000_000_000L,
        )

        assertEquals(0L, first.last().totalBps)
        assertEquals(10_000L, second.last().perPathBps["wifi-1"])
        assertEquals(10_000L, second.last().totalBps)
    }

    @Test
    fun multipleHandlesForOneInterfaceAreSummedBeforeDelta() {
        val history = BandwidthHistory()
        history.onTick(
            listOf(
                path(handle = 1, iface = "wifi-1", tx = 100, rx = 0),
                path(handle = 2, iface = "wifi-1", tx = 200, rx = 0),
            ),
            1_000_000_000L,
        )

        val sample = history.onTick(
            listOf(
                path(handle = 1, iface = "wifi-1", tx = 600, rx = 0),
                path(handle = 2, iface = "wifi-1", tx = 325, rx = 0),
            ),
            2_000_000_000L,
        ).last()

        assertEquals(5_000L, sample.perPathBps["wifi-1"])
    }

    @Test
    fun disappearingPathRebaselinesWhenItReturns() {
        val history = BandwidthHistory()
        history.onTick(
            listOf(path(iface = "cellular-1", tx = 1_000, rx = 0)),
            1_000_000_000L,
        )
        history.onTick(emptyList(), 2_000_000_000L)

        val returned = history.onTick(
            listOf(path(iface = "cellular-1", tx = 9_000_000, rx = 0)),
            3_000_000_000L,
        )

        assertEquals(0L, returned.last().perPathBps["cellular-1"])
    }

    @Test
    fun historyAndInterfaceSlotsAreBoundedAndStable() {
        val history = BandwidthHistory(maxSamples = 2)
        history.onTick(listOf(path(iface = "wifi-1", tx = 0, rx = 0)), 1_000_000_000L)
        history.onTick(
            listOf(
                path(iface = "wifi-1", tx = 0, rx = 0),
                path(handle = 2, iface = "cellular-1", tx = 0, rx = 0),
            ),
            2_000_000_000L,
        )
        val samples = history.onTick(
            listOf(path(iface = "wifi-1", tx = 100, rx = 0)),
            3_000_000_000L,
        )

        assertEquals(2, samples.size)
        assertEquals(0, history.ifaceSlots()["wifi-1"])
        assertEquals(1, history.ifaceSlots()["cellular-1"])
        history.clear()
        assertTrue(history.ifaceSlots().isEmpty())
    }
}
