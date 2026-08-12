package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Test

class PairShareTrafficTest {
    @Test
    fun pairBondAccountingIncludesOnlyUserDataFrames() {
        val tcp = PairBondFrame(PairBondFrameType.TCP_DATA, 1, 0L, byteArrayOf(1, 2, 3))
        val udp = PairBondFrame(
            PairBondFrameType.UDP_DATA,
            2,
            0L,
            PairBondPayload.udpDatagram("1.1.1.1", 53, byteArrayOf(4, 5)),
        )
        val quality = PairBondFrame(
            PairBondFrameType.PATH_QUALITY,
            0,
            0L,
            PairBondPayload.pathQuality(
                PairBondPathQuality(
                    priority = PairBondPathPriority.ACTIVE,
                    rttMillis = 25,
                    lossPermille = 0,
                    deliveryRateBps = 1_000,
                ),
            ),
        )

        assertEquals(3L, tcp.userTrafficBytes())
        assertEquals(2L, udp.userTrafficBytes())
        assertEquals(0L, quality.userTrafficBytes())
    }

    @Test
    fun usageHistoryAccumulatesBothDirectionsWithinTheSameHour() {
        val history = PairShareUsageHistory()

        history.add(7_200_100L, txBytes = 100L, rxBytes = 200L)
        history.add(7_299_999L, txBytes = 20L, rxBytes = 30L)

        assertEquals(PairShareUsageTotal(txBytes = 120L, rxBytes = 230L), history.totalSince(7_200_000L))
        assertEquals(
            PairShareUsagePoint(hourStartMillis = 7_200_000L, txBytes = 120L, rxBytes = 230L),
            history.points().single(),
        )
    }

    @Test
    fun usageHistoryFiltersOlderHours() {
        val history = PairShareUsageHistory()
        history.add(0L, txBytes = 100L, rxBytes = 100L)
        history.add(3_600_000L, txBytes = 200L, rxBytes = 300L)

        assertEquals(PairShareUsageTotal(200L, 300L), history.totalSince(3_600_000L))
    }

    @Test
    fun usageHistoryPrunesTheOldestBuckets() {
        val history = PairShareUsageHistory(maxPoints = 2)
        history.add(0L, txBytes = 1L, rxBytes = 0L)
        history.add(3_600_000L, txBytes = 2L, rxBytes = 0L)
        history.add(7_200_000L, txBytes = 3L, rxBytes = 0L)

        assertEquals(listOf(3_600_000L, 7_200_000L), history.points().map { it.hourStartMillis })
        assertEquals(PairShareUsageTotal(5L, 0L), history.totalSince(0L))
    }
}
