package com.example.glorytun

import com.mqvpn.sdk.core.model.MqvpnConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MqvpnRoutingModeTest {
    @Test
    fun modesMapToTheSchedulerTheyDescribe() {
        assertEquals(
            MqvpnConfig.Scheduler.WLB,
            MqvpnRoutingMode.scheduler(MqvpnRoutingMode.WLB),
        )
        assertEquals(
            MqvpnConfig.Scheduler.MIN_RTT,
            MqvpnRoutingMode.scheduler(MqvpnRoutingMode.MIN_RTT),
        )
        assertEquals(
            MqvpnConfig.Scheduler.WLB_UDP_PIN,
            MqvpnRoutingMode.scheduler(MqvpnRoutingMode.WLB_UDP_PIN),
        )
    }

    @Test
    fun legacyModesMigrateWithoutLosingSchedulerIntent() {
        assertEquals(MqvpnRoutingMode.WLB, MqvpnRoutingMode.normalize("BONDING"))
        assertEquals(MqvpnRoutingMode.WLB, MqvpnRoutingMode.normalize("UDP_SPEED"))
        assertEquals(MqvpnRoutingMode.WLB_UDP_PIN, MqvpnRoutingMode.normalize("LOW_LATENCY"))
        assertEquals(MqvpnRoutingMode.WLB, MqvpnRoutingMode.normalize("WIFI_FIRST"))
        assertEquals(MqvpnRoutingMode.WLB, MqvpnRoutingMode.normalize("SIM_FIRST"))
    }

    @Test
    fun unknownModeFallsBackToRecommendedWlb() {
        assertEquals(MqvpnRoutingMode.WLB, MqvpnRoutingMode.normalize("unexpected"))
    }

    @Test
    fun displayNamesUseFeatureNamePlusMode() {
        assertEquals("帯域集約モード", MqvpnRoutingMode.displayName(MqvpnRoutingMode.WLB))
        assertEquals("低遅延モード", MqvpnRoutingMode.displayName(MqvpnRoutingMode.MIN_RTT))
        assertEquals("UDP安定モード", MqvpnRoutingMode.displayName(MqvpnRoutingMode.WLB_UDP_PIN))
    }
}
