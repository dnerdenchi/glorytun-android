package com.example.glorytun

import android.content.Context
import android.content.Intent
import com.mqvpn.sdk.core.model.MqvpnConfig

object MqvpnConfigFactory {
    const val EXTRA_SERVER_ADDRESS = "IP"
    const val EXTRA_SERVER_PORT = "PORT"
    const val EXTRA_AUTH_KEY = "SECRET"
    const val EXTRA_ALLOW_INSECURE = "ALLOW_INSECURE"
    const val EXTRA_SCHEDULER = "SCHEDULER"

    const val DEFAULT_PORT = "443"
    const val DEFAULT_SCHEDULER = "WLB"
    const val DEFAULT_ALLOW_INSECURE = true
    const val DEFAULT_KILL_SWITCH = true

    fun fromIntent(context: Context, intent: Intent): MqvpnConfig {
        val schedulerName = intent.getStringExtra(EXTRA_SCHEDULER)
            ?: schedulerNameForCurrentMode(context)
        return create(
            serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS).orEmpty(),
            serverPort = intent.getStringExtra(EXTRA_SERVER_PORT).orEmpty(),
            authKey = intent.getStringExtra(EXTRA_AUTH_KEY).orEmpty(),
            allowInsecure = intent.getBooleanExtra(EXTRA_ALLOW_INSECURE, DEFAULT_ALLOW_INSECURE),
            schedulerName = schedulerName
        )
    }

    fun create(
        serverAddress: String,
        serverPort: String,
        authKey: String,
        allowInsecure: Boolean = DEFAULT_ALLOW_INSECURE,
        schedulerName: String = DEFAULT_SCHEDULER,
        killSwitch: Boolean = DEFAULT_KILL_SWITCH
    ): MqvpnConfig {
        val address = serverAddress.trim()
        require(address.isNotEmpty()) { "サーバーアドレスが未設定です" }

        val key = authKey.trim()
        require(key.isNotEmpty()) { "mqvpn 認証キーが未設定です" }

        return MqvpnConfig(
            serverAddress = address,
            serverPort = serverPort.trim().toIntOrNull() ?: DEFAULT_PORT.toInt(),
            authKey = key,
            insecure = allowInsecure,
            multipathEnabled = true,
            scheduler = schedulerFromName(schedulerName),
            logLevel = MqvpnConfig.LogLevel.INFO,
            reconnect = true,
            reconnectIntervalSec = 5,
            killSwitch = killSwitch,
            dnsServers = listOf(
                GlorytunConstants.DEFAULT_DNS_PRIMARY,
                GlorytunConstants.DEFAULT_DNS_SECONDARY
            ),
            reorderEnabled = true,
            reorderProfile = MqvpnConfig.ReorderProfile.CELLULAR_BOND,
            reorderPorts = listOf(443),
            hybridEnabled = true,
            hybridTcpMode = MqvpnConfig.HybridTcpMode.AUTO,
        )
    }

    private fun schedulerNameForCurrentMode(context: Context): String {
        val mode = context.getSharedPreferences(
            MqvpnRoutingMode.PREFS_NAME,
            Context.MODE_PRIVATE
        ).getString(
            MqvpnRoutingMode.KEY_MODE,
            MqvpnRoutingMode.WLB
        )

        return MqvpnRoutingMode.scheduler(mode).name
    }

    private fun schedulerFromName(value: String): MqvpnConfig.Scheduler {
        return when (value.trim().uppercase()) {
            "MINRTT", "MIN_RTT" -> MqvpnConfig.Scheduler.MIN_RTT
            "WLB" -> MqvpnConfig.Scheduler.WLB
            "WLB_UDP_PIN" -> MqvpnConfig.Scheduler.WLB_UDP_PIN
            "BACKUP_FEC" -> MqvpnConfig.Scheduler.BACKUP_FEC
            else -> MqvpnConfig.Scheduler.WLB
        }
    }
}
