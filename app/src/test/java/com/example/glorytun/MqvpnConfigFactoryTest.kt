package com.example.glorytun

import com.mqvpn.sdk.core.model.MqvpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqvpnConfigFactoryTest {
    @Test
    fun defaultConfigPinsUdpFlowsToAvoidMultipathVideoReordering() {
        val config = MqvpnConfigFactory.create(
            serverAddress = "203.0.113.10",
            serverPort = "443",
            authKey = "test-key"
        )

        assertEquals(MqvpnConfig.Scheduler.WLB_UDP_PIN, config.scheduler)
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

        assertFalse(config.killSwitch)
    }
}
