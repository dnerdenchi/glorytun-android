package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedTesterTest {
    @Test
    fun formatSpeedUsesKbpsBelowOneMbps() {
        assertEquals("640 kbps", NetworkSpeedTester.formatSpeed(0.64))
    }

    @Test
    fun formatSpeedUsesMbpsAtOneMbpsAndAbove() {
        assertEquals("12.3 Mbps", NetworkSpeedTester.formatSpeed(12.34))
    }

    @Test
    fun medianPicksMiddleLatencySample() {
        assertEquals(31L, NetworkSpeedTester.median(listOf(47L, 19L, 31L)))
    }
}
