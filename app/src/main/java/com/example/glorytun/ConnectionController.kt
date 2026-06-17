package com.example.glorytun

import android.content.Context
import android.content.Intent

object ConnectionController {
    fun isProxyModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(GlorytunConstants.PREFS_PROXY, Context.MODE_PRIVATE)
            .getBoolean(GlorytunConstants.KEY_ADGUARD_PROXY_MODE_ENABLED, false)
    }

    fun startVpn(context: Context, ip: String, port: String, secret: String) {
        stopProxy(context)
        context.startService(Intent(context, GlorytunVpnService::class.java).apply {
            action = GlorytunConstants.ACTION_CONNECT
            putExtra("IP", ip)
            putExtra("PORT", port)
            putExtra("SECRET", secret)
        })
    }

    fun startProxy(context: Context) {
        stopVpn(context)
        context.startService(Intent(context, AdGuardProxyService::class.java).apply {
            action = GlorytunConstants.ACTION_PROXY_START
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
        context.startService(Intent(context, GlorytunVpnService::class.java).apply {
            action = GlorytunConstants.ACTION_DISCONNECT
        })
    }

    private fun stopProxy(context: Context) {
        context.startService(Intent(context, AdGuardProxyService::class.java).apply {
            action = GlorytunConstants.ACTION_PROXY_STOP
        })
    }
}
