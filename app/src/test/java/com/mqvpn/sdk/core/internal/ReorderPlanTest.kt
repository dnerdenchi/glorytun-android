package com.mqvpn.sdk.core.internal

import com.mqvpn.sdk.core.model.MqvpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderPlanTest {
    private fun config(
        enabled: Boolean,
        ports: List<Int>,
        profile: MqvpnConfig.ReorderProfile = MqvpnConfig.ReorderProfile.CELLULAR_BOND,
    ) = MqvpnConfig(
        serverAddress = "203.0.113.10",
        authKey = "test-key",
        reorderEnabled = enabled,
        reorderPorts = ports,
        reorderProfile = profile,
    )

    @Test
    fun disabledConfigProducesNoRules() {
        val plan = planReorder(config(false, listOf(443)))

        assertFalse(plan.enabled)
        assertTrue(plan.rules.isEmpty())
    }

    @Test
    fun officialCellularBondRuleTargetsUdp443() {
        val plan = planReorder(config(true, listOf(443)))

        assertTrue(plan.enabled)
        assertEquals(
            ReorderRuleSpec(
                proto = REORDER_PROTO_UDP,
                port = 443,
                profile = MqvpnConfig.ReorderProfile.CELLULAR_BOND.native,
            ),
            plan.rules.single(),
        )
    }

    @Test
    fun invalidAndDuplicatePortsAreIgnored() {
        val plan = planReorder(config(true, listOf(0, 443, 443, 65_536)))

        assertEquals(listOf(443), plan.rules.map { it.port })
        assertEquals(2, plan.warnings.size)
    }
}
