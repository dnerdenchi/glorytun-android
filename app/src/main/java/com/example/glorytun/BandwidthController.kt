package com.example.glorytun

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class BandwidthController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun setPathMaxRate(localIp: String, txRate: Long, rxRate: Long)
        /** スロットル専用: fixed_rate を制御して輻輳制御による速度低下を防ぐ */
        fun setPathThrottleRate(localIp: String, txRate: Long, rxRate: Long, enable: Boolean)
        fun getActiveNetworkPaths(): Map<Long, String>
        fun getAvailableWifiLocalIp(): String?
        fun getAvailableSimLocalIp(): String?
    }

    data class ThrottleLimit(val enabled: Boolean, val limitKB: Long, val throttleBps: Long)
    data class NetworkLimit(val daily: ThrottleLimit, val monthly: ThrottleLimit)
    data class BwSettings(val wifi: NetworkLimit, val sim: NetworkLimit)

    @Volatile private var bwSettingsCache: BwSettings? = null
    @Volatile private var bwSettingsCacheMs = 0L

    @Volatile private var wifiThrottled = false
    @Volatile private var simThrottled = false

    /** 帯域幅制限設定を読み込み、キャッシュする */
    fun loadSettings(): BwSettings {
        val now = System.currentTimeMillis()
        val cached = bwSettingsCache
        if (cached != null && now - bwSettingsCacheMs < GlorytunConstants.SETTINGS_CACHE_TIMEOUT_MS) return cached
        
        val prefs = context.getSharedPreferences(GlorytunConstants.PREFS_BANDWIDTH, Context.MODE_PRIVATE)
        return BwSettings(
            wifi = loadNetworkLimit(prefs, "wifi"),
            sim = loadNetworkLimit(prefs, "sim")
        ).also { 
            bwSettingsCache = it
            bwSettingsCacheMs = now 
        }
    }

    private fun loadNetworkLimit(prefs: SharedPreferences, prefix: String): NetworkLimit {
        return NetworkLimit(
            daily = loadLimit(prefs, prefix, "daily", GlorytunConstants.BW_DEFAULT_DAILY_LIMIT_MB, 1_024L, "mb"),
            monthly = loadLimit(prefs, prefix, "monthly", GlorytunConstants.BW_DEFAULT_MONTHLY_LIMIT_GB, 1_048_576L, "gb")
        )
    }

    private fun loadLimit(
        prefs: SharedPreferences, 
        prefix: String, 
        type: String, 
        defLimit: Int, 
        mul: Long, 
        suffix: String
    ): ThrottleLimit {
        return ThrottleLimit(
            enabled = prefs.getBoolean("${prefix}_${type}_enabled", false),
            limitKB = prefs.getInt("${prefix}_${type}_limit_${suffix}", defLimit).toLong() * mul,
            throttleBps = prefs.getInt("${prefix}_${type}_throttle_mbps", GlorytunConstants.BW_DEFAULT_THROTTLE_MBPS).toLong() * GlorytunConstants.MBPS_TO_BYTES_PER_SEC
        )
    }

    /** 帯域幅制限を確認し、超過時はスロットリングを適用する */
    fun checkAndThrottle(dailyWifiKB: Double, dailySimKB: Double, monthlyWifiKB: Double, monthlySimKB: Double) {
        val bw = loadSettings()
        val activePaths = listener.getActiveNetworkPaths()

        wifiThrottled = applyThrottle(
            "WiFi", listener.getAvailableWifiLocalIp(), activePaths,
            dailyWifiKB, monthlyWifiKB, bw.wifi, wifiThrottled
        )
        simThrottled = applyThrottle(
            "SIM", listener.getAvailableSimLocalIp(), activePaths,
            dailySimKB, monthlySimKB, bw.sim, simThrottled
        )
    }

    private fun applyThrottle(
        name: String,
        ip: String?,
        activePaths: Map<Long, String>,
        dailyKB: Double, 
        monthlyKB: Double,
        limit: NetworkLimit,
        wasThrottled: Boolean
    ): Boolean {
        if (ip == null || !activePaths.containsValue(ip)) return wasThrottled

        val dailyOver = limit.daily.enabled && dailyKB >= limit.daily.limitKB
        val monthlyOver = limit.monthly.enabled && monthlyKB >= limit.monthly.limitKB
        
        val shouldThrottle = dailyOver || monthlyOver
        
        if (shouldThrottle) {
            val throttleBps = if (dailyOver) limit.daily.throttleBps else limit.monthly.throttleBps
            listener.setPathThrottleRate(ip, throttleBps, throttleBps, true)
            if (!wasThrottled) {
                Log.i("BandwidthController", "$name 帯域制限ON: ${throttleBps / GlorytunConstants.MBPS_TO_BYTES_PER_SEC} Mbps")
            }
        } else if (wasThrottled) {
            listener.setPathThrottleRate(ip, 0L, 0L, false)
            Log.i("BandwidthController", "$name 帯域制限OFF")
        }
        
        return shouldThrottle
    }

    fun isWifiThrottled(): Boolean = wifiThrottled
    fun isSimThrottled(): Boolean = simThrottled

    fun clearCache() {
        bwSettingsCache = null
    }

    fun resetThrottledFlags() {
        wifiThrottled = false
        simThrottled = false
    }
}
