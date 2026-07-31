package com.example.glorytun

import com.mqvpn.sdk.core.model.MqvpnConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MqvpnRoutingModeTest {
    @Test
    fun modesMapToTheSchedulerTheyDescribe() {
        assertEquals(
            MqvpnConfig.Scheduler.MIN_RTT,
            MqvpnRoutingMode.scheduler(MqvpnRoutingMode.BALANCED),
        )
        assertEquals(
            MqvpnConfig.Scheduler.WLB,
            MqvpnRoutingMode.scheduler(MqvpnRoutingMode.UDP_SPEED),
        )
        assertEquals(
            MqvpnConfig.Scheduler.WLB_UDP_PIN,
            MqvpnRoutingMode.scheduler(MqvpnRoutingMode.LOW_LATENCY),
        )
    }

    @Test
    fun legacyPriorityModesMigrateToTheOfficialMode() {
        assertEquals(MqvpnRoutingMode.BALANCED, MqvpnRoutingMode.normalize("WIFI_FIRST"))
        assertEquals(MqvpnRoutingMode.BALANCED, MqvpnRoutingMode.normalize("SIM_FIRST"))
    }

    @Test
    fun unknownModeFallsBackToBalanced() {
        assertEquals(MqvpnRoutingMode.BALANCED, MqvpnRoutingMode.normalize("unexpected"))
    }
}
