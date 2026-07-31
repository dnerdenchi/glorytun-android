package com.example.glorytun

import com.mqvpn.sdk.core.model.MqvpnConfig

/** User-facing routing modes that map directly to the official mqvpn schedulers. */
object MqvpnRoutingMode {
    const val PREFS_NAME = "network_mode_prefs"
    const val KEY_MODE = "network_mode"

    const val BALANCED = "BONDING"
    const val UDP_SPEED = "UDP_SPEED"
    const val LOW_LATENCY = "LOW_LATENCY"

    private const val LEGACY_WIFI_FIRST = "WIFI_FIRST"
    private const val LEGACY_SIM_FIRST = "SIM_FIRST"

    fun normalize(value: String?): String = when (value) {
        BALANCED, UDP_SPEED, LOW_LATENCY -> value
        LEGACY_WIFI_FIRST, LEGACY_SIM_FIRST -> BALANCED
        else -> BALANCED
    }

    fun scheduler(value: String?): MqvpnConfig.Scheduler = when (normalize(value)) {
        UDP_SPEED -> MqvpnConfig.Scheduler.WLB
        LOW_LATENCY -> MqvpnConfig.Scheduler.WLB_UDP_PIN
        else -> MqvpnConfig.Scheduler.MIN_RTT
    }

    fun displayName(value: String?): String = when (normalize(value)) {
        UDP_SPEED -> "帯域集約"
        LOW_LATENCY -> "UDP安定"
        else -> "公式・自動"
    }
}
