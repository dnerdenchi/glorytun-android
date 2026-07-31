package com.example.glorytun

import android.content.SharedPreferences
import kotlin.math.min

data class BandwidthLimit(
    val enabled: Boolean,
    val rateKbps: Int,
)

data class QuotaBandwidthLimit(
    val enabled: Boolean,
    val limitBytes: Long,
    val rateKbps: Int,
)

data class NetworkBandwidthSettings(
    val always: BandwidthLimit,
    val monthly: QuotaBandwidthLimit,
    val daily: QuotaBandwidthLimit,
)

data class BandwidthSettings(
    val wifi: NetworkBandwidthSettings,
    val sim: NetworkBandwidthSettings,
) {
    companion object {
        fun from(prefs: SharedPreferences): BandwidthSettings = BandwidthSettings(
            wifi = readNetwork(prefs, "wifi"),
            sim = readNetwork(prefs, "sim"),
        )

        private fun readNetwork(
            prefs: SharedPreferences,
            prefix: String,
        ): NetworkBandwidthSettings {
            val alwaysRateKey = "${prefix}_always_throttle_kbps"
            return NetworkBandwidthSettings(
                always = BandwidthLimit(
                    enabled = prefs.getBoolean("${prefix}_always_enabled", false),
                    rateKbps = prefs.getInt(
                        alwaysRateKey,
                        GlorytunConstants.BW_DEFAULT_THROTTLE_KBPS,
                    ).coerceAtLeast(1),
                ),
                monthly = QuotaBandwidthLimit(
                    enabled = prefs.getBoolean("${prefix}_monthly_enabled", false),
                    limitBytes = prefs.getInt(
                        "${prefix}_monthly_limit_gb",
                        GlorytunConstants.BW_DEFAULT_MONTHLY_LIMIT_GB,
                    ).coerceAtLeast(1) * BYTES_PER_GIB,
                    rateKbps = readLegacyCompatibleRate(
                        prefs,
                        "${prefix}_monthly_throttle_mbps",
                    ),
                ),
                daily = QuotaBandwidthLimit(
                    enabled = prefs.getBoolean("${prefix}_daily_enabled", false),
                    limitBytes = prefs.getInt(
                        "${prefix}_daily_limit_mb",
                        GlorytunConstants.BW_DEFAULT_DAILY_LIMIT_MB,
                    ).coerceAtLeast(1) * BYTES_PER_MIB,
                    rateKbps = readLegacyCompatibleRate(
                        prefs,
                        "${prefix}_daily_throttle_mbps",
                    ),
                ),
            )
        }

        /**
         * Early releases stored Mbps in a key whose UI later switched to kbps.
         * Values up to 10 are therefore migrated as Mbps; larger values are kbps.
         */
        private fun readLegacyCompatibleRate(
            prefs: SharedPreferences,
            key: String,
        ): Int {
            val raw = prefs.getInt(key, GlorytunConstants.BW_DEFAULT_THROTTLE_MBPS)
            return (if (raw in 1..10) raw * 1_000 else raw).coerceAtLeast(1)
        }

        private const val BYTES_PER_MIB = 1_024L * 1_024L
        private const val BYTES_PER_GIB = 1_024L * 1_024L * 1_024L
    }
}

data class BandwidthUsage(
    val dailyWifiBytes: Long = 0L,
    val dailySimBytes: Long = 0L,
    val monthlyWifiBytes: Long = 0L,
    val monthlySimBytes: Long = 0L,
)

data class NetworkBandwidthDecision(
    val rateBytesPerSecond: Long,
    val limited: Boolean,
)

data class BandwidthDecision(
    val wifi: NetworkBandwidthDecision,
    val sim: NetworkBandwidthDecision,
)

object BandwidthPolicy {
    fun evaluate(
        settings: BandwidthSettings,
        usage: BandwidthUsage,
    ): BandwidthDecision = BandwidthDecision(
        wifi = evaluateNetwork(
            settings.wifi,
            usage.dailyWifiBytes,
            usage.monthlyWifiBytes,
        ),
        sim = evaluateNetwork(
            settings.sim,
            usage.dailySimBytes,
            usage.monthlySimBytes,
        ),
    )

    private fun evaluateNetwork(
        settings: NetworkBandwidthSettings,
        dailyBytes: Long,
        monthlyBytes: Long,
    ): NetworkBandwidthDecision {
        var effectiveKbps: Int? = null

        fun include(rateKbps: Int) {
            effectiveKbps = effectiveKbps?.let { min(it, rateKbps) } ?: rateKbps
        }

        if (settings.always.enabled) include(settings.always.rateKbps)
        if (settings.daily.enabled && dailyBytes >= settings.daily.limitBytes) {
            include(settings.daily.rateKbps)
        }
        if (settings.monthly.enabled && monthlyBytes >= settings.monthly.limitBytes) {
            include(settings.monthly.rateKbps)
        }

        val rate = effectiveKbps
        return NetworkBandwidthDecision(
            rateBytesPerSecond = rate?.toLong()?.times(GlorytunConstants.KBPS_TO_BYTES_PER_SEC) ?: 0L,
            limited = rate != null,
        )
    }
}
