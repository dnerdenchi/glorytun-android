package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandwidthPolicyTest {
    @Test
    fun alwaysLimitAppliesBeforeAnyQuotaIsExceeded() {
        val decision = BandwidthPolicy.evaluate(
            settings = settings(
                wifi = network(always = limit(true, 5_000)),
            ),
            usage = BandwidthUsage(),
        )

        assertTrue(decision.wifi.limited)
        assertEquals(625_000L, decision.wifi.rateBytesPerSecond)
        assertFalse(decision.sim.limited)
    }

    @Test
    fun quotaLimitStartsOnlyAtConfiguredBoundary() {
        val wifi = network(
            daily = quota(enabled = true, limitBytes = 1_000L, rateKbps = 512),
        )

        val before = BandwidthPolicy.evaluate(
            settings(wifi = wifi),
            BandwidthUsage(dailyWifiBytes = 999L),
        )
        val atLimit = BandwidthPolicy.evaluate(
            settings(wifi = wifi),
            BandwidthUsage(dailyWifiBytes = 1_000L),
        )

        assertFalse(before.wifi.limited)
        assertTrue(atLimit.wifi.limited)
        assertEquals(64_000L, atLimit.wifi.rateBytesPerSecond)
    }

    @Test
    fun strictestActiveLimitWins() {
        val wifi = network(
            always = limit(true, 10_000),
            daily = quota(true, 1_000L, 2_000),
            monthly = quota(true, 2_000L, 5_000),
        )

        val decision = BandwidthPolicy.evaluate(
            settings(wifi = wifi),
            BandwidthUsage(
                dailyWifiBytes = 1_000L,
                monthlyWifiBytes = 2_000L,
            ),
        )

        assertEquals(250_000L, decision.wifi.rateBytesPerSecond)
    }

    private fun settings(
        wifi: NetworkBandwidthSettings = network(),
        sim: NetworkBandwidthSettings = network(),
    ) = BandwidthSettings(wifi, sim)

    private fun network(
        always: BandwidthLimit = limit(false, 1_000),
        monthly: QuotaBandwidthLimit = quota(false, Long.MAX_VALUE, 1_000),
        daily: QuotaBandwidthLimit = quota(false, Long.MAX_VALUE, 1_000),
    ) = NetworkBandwidthSettings(always, monthly, daily)

    private fun limit(enabled: Boolean, rateKbps: Int) =
        BandwidthLimit(enabled, rateKbps)

    private fun quota(enabled: Boolean, limitBytes: Long, rateKbps: Int) =
        QuotaBandwidthLimit(enabled, limitBytes, rateKbps)
}
