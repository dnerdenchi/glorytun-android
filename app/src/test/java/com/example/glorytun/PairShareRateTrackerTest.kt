package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Test

class PairShareRateTrackerTest {
    @Test
    fun receivingRateIncludesBothDirectionsAndExcludesSharedTraffic() {
        val tracker = PairShareRateTracker()

        val sample = tracker.sample(
            sessions = listOf(
                PairShareRateCounters("receive", "OPPO", PairShareTrafficRole.RECEIVING, 250, 750),
                PairShareRateCounters("share", "Xiaomi", PairShareTrafficRole.SHARING, 4_000, 6_000),
            ),
            elapsedMillis = 500,
        )

        assertEquals(8_500L, sample.txBytesPerSecond)
        assertEquals(13_500L, sample.rxBytesPerSecond)
        assertEquals(2_000L, sample.receivedBytesPerSecond)
        assertEquals(listOf("OPPO"), sample.receivingPeerNames)
    }

    @Test
    fun sharingOnlySessionHasNoReceivedSpeed() {
        val tracker = PairShareRateTracker()

        val sample = tracker.sample(
            sessions = listOf(
                PairShareRateCounters("share", "Xiaomi", PairShareTrafficRole.SHARING, 1_000, 2_000),
            ),
            elapsedMillis = 1_000,
        )

        assertEquals(0L, sample.receivedBytesPerSecond)
        assertEquals(emptyList<String>(), sample.receivingPeerNames)
    }
}
