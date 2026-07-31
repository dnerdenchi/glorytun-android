package com.example.glorytun

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

class BandwidthUsageStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var usage = loadCurrentUsage()
    private var dirtyTicks = 0

    @Synchronized
    fun add(wifiBytes: Long, simBytes: Long): BandwidthUsage {
        rollPeriodsIfNeeded()
        val safeWifi = wifiBytes.coerceAtLeast(0L)
        val safeSim = simBytes.coerceAtLeast(0L)
        usage = usage.copy(
            dailyWifiBytes = usage.dailyWifiBytes + safeWifi,
            dailySimBytes = usage.dailySimBytes + safeSim,
            monthlyWifiBytes = usage.monthlyWifiBytes + safeWifi,
            monthlySimBytes = usage.monthlySimBytes + safeSim,
        )
        if (++dirtyTicks >= SAVE_EVERY_TICKS) persist()
        return usage
    }

    @Synchronized
    fun snapshot(): BandwidthUsage {
        rollPeriodsIfNeeded()
        return usage
    }

    @Synchronized
    fun flush() {
        if (dirtyTicks > 0) persist()
    }

    private fun loadCurrentUsage(): BandwidthUsage {
        val now = periodKeys(nowMillis())
        val storedDay = prefs.getLong(KEY_DAY, Long.MIN_VALUE)
        val storedMonth = prefs.getLong(KEY_MONTH, Long.MIN_VALUE)
        return BandwidthUsage(
            dailyWifiBytes = if (storedDay == now.day) prefs.getLong(KEY_DAILY_WIFI, 0L) else 0L,
            dailySimBytes = if (storedDay == now.day) prefs.getLong(KEY_DAILY_SIM, 0L) else 0L,
            monthlyWifiBytes = if (storedMonth == now.month) prefs.getLong(KEY_MONTHLY_WIFI, 0L) else 0L,
            monthlySimBytes = if (storedMonth == now.month) prefs.getLong(KEY_MONTHLY_SIM, 0L) else 0L,
        )
    }

    private fun rollPeriodsIfNeeded() {
        val now = periodKeys(nowMillis())
        val storedDay = prefs.getLong(KEY_DAY, Long.MIN_VALUE)
        val storedMonth = prefs.getLong(KEY_MONTH, Long.MIN_VALUE)
        var changed = false

        if (storedDay != now.day) {
            usage = usage.copy(dailyWifiBytes = 0L, dailySimBytes = 0L)
            changed = true
        }
        if (storedMonth != now.month) {
            usage = usage.copy(monthlyWifiBytes = 0L, monthlySimBytes = 0L)
            changed = true
        }
        if (changed) persist()
    }

    private fun persist() {
        val now = periodKeys(nowMillis())
        prefs.edit()
            .putLong(KEY_DAY, now.day)
            .putLong(KEY_MONTH, now.month)
            .putLong(KEY_DAILY_WIFI, usage.dailyWifiBytes)
            .putLong(KEY_DAILY_SIM, usage.dailySimBytes)
            .putLong(KEY_MONTHLY_WIFI, usage.monthlyWifiBytes)
            .putLong(KEY_MONTHLY_SIM, usage.monthlySimBytes)
            .apply()
        dirtyTicks = 0
    }

    private data class PeriodKeys(val day: Long, val month: Long)

    private fun periodKeys(timeMillis: Long): PeriodKeys {
        val calendar = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val year = calendar.get(Calendar.YEAR).toLong()
        val month = calendar.get(Calendar.MONTH).toLong()
        val day = calendar.get(Calendar.DAY_OF_MONTH).toLong()
        return PeriodKeys(
            day = year * 10_000L + (month + 1L) * 100L + day,
            month = year * 100L + month + 1L,
        )
    }

    companion object {
        private const val PREFS_NAME = "bandwidth_usage"
        private const val KEY_DAY = "period_day"
        private const val KEY_MONTH = "period_month"
        private const val KEY_DAILY_WIFI = "daily_wifi_bytes"
        private const val KEY_DAILY_SIM = "daily_sim_bytes"
        private const val KEY_MONTHLY_WIFI = "monthly_wifi_bytes"
        private const val KEY_MONTHLY_SIM = "monthly_sim_bytes"
        private const val SAVE_EVERY_TICKS = 1
    }
}
