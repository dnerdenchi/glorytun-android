package com.example.glorytun

object GlorytunConstants {
    // --- Intent Actions ---
    const val ACTION_CONNECT = "com.example.glorytun.START"
    const val ACTION_DISCONNECT = "com.example.glorytun.STOP"
    const val ACTION_QUERY_STATE = "com.example.glorytun.QUERY_STATE"
    const val ACTION_VPN_STATE = "VPN_STATE"
    const val ACTION_VPN_TRAFFIC_STATS = "VPN_TRAFFIC_STATS"
    const val ACTION_VPN_HOURLY_STATS = "VPN_HOURLY_STATS"

    // --- Notification ---
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "glorytun_vpn_channel"
    const val CHANNEL_NAME = "Glorytun VPN"

    // --- Conversions ---
    /** 1 Mbps = 125,000 bytes/s */
    const val MBPS_TO_BYTES_PER_SEC = 125_000L
    /** 1 kbps = 125 bytes/s */
    const val KBPS_TO_BYTES_PER_SEC = 125L
    
    const val MILLIS_IN_HOUR = 3_600_000L
    const val MILLIS_IN_MINUTE = 60_000L
    
    /** 10 MB/s default rate in glorytun */
    const val DEFAULT_MAX_RATE_BYTES_PER_SEC = 10_000_000L

    // --- Shared Preferences ---
    const val PREFS_NETWORK_PROTOCOL = "network_protocol_prefs"
    const val PREFS_BANDWIDTH = "bandwidth_prefs"
    
    // --- Bandwidth Keys & Defaults ---
    const val KEY_WIFI_MONTHLY_ENABLED  = "wifi_monthly_enabled"
    const val KEY_WIFI_MONTHLY_LIMIT_GB = "wifi_monthly_limit_gb"
    const val KEY_WIFI_MONTHLY_THROTTLE = "wifi_monthly_throttle_mbps"

    const val KEY_WIFI_DAILY_ENABLED    = "wifi_daily_enabled"
    const val KEY_WIFI_DAILY_LIMIT_MB   = "wifi_daily_limit_mb"
    const val KEY_WIFI_DAILY_THROTTLE   = "wifi_daily_throttle_mbps"

    const val KEY_SIM_MONTHLY_ENABLED   = "sim_monthly_enabled"
    const val KEY_SIM_MONTHLY_LIMIT_GB  = "sim_monthly_limit_gb"
    const val KEY_SIM_MONTHLY_THROTTLE  = "sim_monthly_throttle_mbps"

    const val KEY_SIM_DAILY_ENABLED     = "sim_daily_enabled"
    const val KEY_SIM_DAILY_LIMIT_MB    = "sim_daily_limit_mb"
    const val KEY_SIM_DAILY_THROTTLE    = "sim_daily_throttle_mbps"

    const val BW_DEFAULT_MONTHLY_LIMIT_GB  = 30
    const val BW_DEFAULT_THROTTLE_MBPS     = 1
    const val BW_DEFAULT_DAILY_LIMIT_MB    = 1000
    
    // --- Cache ---
    const val SETTINGS_CACHE_TIMEOUT_MS = 60_000L

    // --- Internal IP logic ---
    const val DEFAULT_VPN_LOCAL_IP = "10.0.1.2"
    const val DEFAULT_DNS_PRIMARY = "8.8.8.8"
    const val DEFAULT_DNS_SECONDARY = "1.1.1.1"
    const val ADGUARD_DNS_PRIMARY = "94.140.14.14"
    const val ADGUARD_DNS_SECONDARY = "94.140.15.15"
    const val DEFAULT_MTU = 1420

    // --- DNS Prefs ---
    const val PREFS_DNS = "dns_prefs"
    const val KEY_ADGUARD_DNS_ENABLED = "adguard_dns_enabled"
}
