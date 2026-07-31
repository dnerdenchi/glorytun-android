package com.example.glorytun

import com.mqvpn.sdk.core.model.MqvpnConfig

/** User-facing routing modes that map directly to the official mqvpn schedulers. */
object MqvpnRoutingMode {
    const val PREFS_NAME = "network_mode_prefs"
    const val KEY_MODE = "network_mode"

    const val WLB = "WLB"
    const val MIN_RTT = "MIN_RTT"
    const val WLB_UDP_PIN = "WLB_UDP_PIN"

    private const val LEGACY_BONDING = "BONDING"
    private const val LEGACY_UDP_SPEED = "UDP_SPEED"
    private const val LEGACY_LOW_LATENCY = "LOW_LATENCY"
    private const val LEGACY_WIFI_FIRST = "WIFI_FIRST"
    private const val LEGACY_SIM_FIRST = "SIM_FIRST"

    fun normalize(value: String?): String = when (value) {
        WLB, MIN_RTT, WLB_UDP_PIN -> value
        LEGACY_BONDING, LEGACY_UDP_SPEED, LEGACY_WIFI_FIRST, LEGACY_SIM_FIRST -> WLB
        LEGACY_LOW_LATENCY -> WLB_UDP_PIN
        else -> WLB
    }

    fun scheduler(value: String?): MqvpnConfig.Scheduler = when (normalize(value)) {
        MIN_RTT -> MqvpnConfig.Scheduler.MIN_RTT
        WLB_UDP_PIN -> MqvpnConfig.Scheduler.WLB_UDP_PIN
        else -> MqvpnConfig.Scheduler.WLB
    }

    fun displayName(value: String?): String = when (normalize(value)) {
        MIN_RTT -> "低遅延モード"
        WLB_UDP_PIN -> "UDP安定モード"
        else -> "帯域集約モード"
    }
}
