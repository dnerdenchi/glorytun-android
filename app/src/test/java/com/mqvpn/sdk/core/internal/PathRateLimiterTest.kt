package com.mqvpn.sdk.core.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class PathRateLimiterTest {
    @Test
    fun unlimitedModeNeverDelays() {
        val limiter = PathRateLimiter()

        assertEquals(0L, limiter.reserveDelayNanos(1_500, 10L))
        assertEquals(0L, limiter.reserveDelayNanos(1_500, 20L))
    }

    @Test
    fun configuredRateSpacesPacketsByTheirSerializationTime() {
        val limiter = PathRateLimiter()
        limiter.updateRate(1_000L)

        assertEquals(0L, limiter.reserveDelayNanos(500, 0L))
        assertEquals(500_000_000L, limiter.reserveDelayNanos(500, 0L))
        assertEquals(500_000_000L, limiter.reserveDelayNanos(500, 500_000_000L))
    }

    @Test
    fun changingRateClearsOldReservation() {
        val limiter = PathRateLimiter()
        limiter.updateRate(1_000L)
        limiter.reserveDelayNanos(1_000, 0L)

        limiter.updateRate(2_000L)

        assertEquals(0L, limiter.reserveDelayNanos(1_000, 100L))
    }
}
