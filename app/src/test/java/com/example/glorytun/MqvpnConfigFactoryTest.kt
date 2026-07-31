package com.example.glorytun

import com.mqvpn.sdk.core.model.MqvpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqvpnConfigFactoryTest {
    @Test
    fun defaultConfigEnablesWlbHybridAndReorder() {
        val config = MqvpnConfigFactory.create(
            serverAddress = "203.0.113.10",
            serverPort = "443",
            authKey = "test-key"
        )

        assertEquals(MqvpnConfig.Scheduler.WLB, config.scheduler)
        assertTrue(config.reorderEnabled)
        assertEquals(MqvpnConfig.ReorderProfile.CELLULAR_BOND, config.reorderProfile)
        assertEquals(listOf(443), config.reorderPorts)
        assertTrue(config.hybridEnabled)
        assertEquals(MqvpnConfig.HybridTcpMode.AUTO, config.hybridTcpMode)
    }

    @Test
    fun defaultConfigEnablesKillSwitchToPreventIpv6Leaks() {
        val config = MqvpnConfigFactory.create(
            serverAddress = "203.0.113.10",
            serverPort = "443",
            authKey = "test-key"
        )

        assertTrue(config.killSwitch)
    }

    @Test
    fun explicitKillSwitchSettingIsRespected() {
        val config = MqvpnConfigFactory.create(
            serverAddress = "203.0.113.10",
            serverPort = "443",
            authKey = "test-key",
            killSwitch = false
        )

        org.junit.Assert.assertFalse(config.killSwitch)
    }

    @Test
    fun explicitWlbSchedulerEnablesUdpPacketDistribution() {
        val config = MqvpnConfigFactory.create(
            serverAddress = "203.0.113.10",
            serverPort = "443",
            authKey = "test-key",
            schedulerName = "WLB",
        )

        assertEquals(MqvpnConfig.Scheduler.WLB, config.scheduler)
    }

    @Test
    fun unknownSchedulerFallsBackToRecommendedWlb() {
        val config = MqvpnConfigFactory.create(
            serverAddress = "203.0.113.10",
            serverPort = "443",
            authKey = "test-key",
            schedulerName = "unknown",
        )

        assertEquals(MqvpnConfig.Scheduler.WLB, config.scheduler)
    }
}
