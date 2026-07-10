package com.example.glorytun

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object ConnectionController {
    fun isProxyModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(GlorytunConstants.PREFS_PROXY, Context.MODE_PRIVATE)
            .getBoolean(GlorytunConstants.KEY_ADGUARD_PROXY_MODE_ENABLED, false)
    }

    fun startVpn(
        context: Context,
        ip: String,
        port: String,
        secret: String,
        allowInsecureCertificate: Boolean = MqvpnConfigFactory.DEFAULT_ALLOW_INSECURE
    ) {
        stopProxy(context)
        ContextCompat.startForegroundService(context, Intent(context, MqvpnBondingService::class.java).apply {
            action = GlorytunConstants.ACTION_CONNECT
            putExtra(MqvpnConfigFactory.EXTRA_SERVER_ADDRESS, ip)
            putExtra(MqvpnConfigFactory.EXTRA_SERVER_PORT, port)
            putExtra(MqvpnConfigFactory.EXTRA_AUTH_KEY, secret)
            putExtra(MqvpnConfigFactory.EXTRA_ALLOW_INSECURE, allowInsecureCertificate)
        })
    }

    fun startProxy(
        context: Context,
        ip: String,
        port: String,
        secret: String,
        allowInsecureCertificate: Boolean = MqvpnConfigFactory.DEFAULT_ALLOW_INSECURE
    ) {
        stopVpn(context)
        context.startService(Intent(context, AdGuardProxyService::class.java).apply {
            action = GlorytunConstants.ACTION_PROXY_START
            putExtra(MqvpnConfigFactory.EXTRA_SERVER_ADDRESS, ip)
            putExtra(MqvpnConfigFactory.EXTRA_SERVER_PORT, port)
            putExtra(MqvpnConfigFactory.EXTRA_AUTH_KEY, secret)
            putExtra(MqvpnConfigFactory.EXTRA_ALLOW_INSECURE, allowInsecureCertificate)
        })
    }

    fun startPairShareProxy(
        context: Context,
        ip: String,
        port: String,
        secret: String,
    ) {
        stopVpn(context)
        context.startService(Intent(context, AdGuardProxyService::class.java).apply {
            action = GlorytunConstants.ACTION_PROXY_START
            putExtra(GlorytunConstants.EXTRA_PAIR_SHARE_RECEIVE, true)
            putExtra(MqvpnConfigFactory.EXTRA_SERVER_ADDRESS, ip)
            putExtra(MqvpnConfigFactory.EXTRA_SERVER_PORT, port)
            putExtra(MqvpnConfigFactory.EXTRA_AUTH_KEY, secret)
        })
    }

    fun disconnectActive(context: Context, state: String?) {
        if (ConnectionStates.isProxyState(state)) {
            stopProxy(context)
        } else {
            stopVpn(context)
        }
    }

    private fun stopVpn(context: Context) {
        context.startService(Intent(context, MqvpnBondingService::class.java).apply {
            action = GlorytunConstants.ACTION_DISCONNECT
        })
    }

    private fun stopProxy(context: Context) {
        context.startService(Intent(context, AdGuardProxyService::class.java).apply {
            action = GlorytunConstants.ACTION_PROXY_STOP
        })
    }
}
