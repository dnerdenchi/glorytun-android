package com.example.glorytun

object ConnectionStates {
    const val DISCONNECTED = "Disconnected"
    const val DISCONNECTING = "Disconnecting..."
    const val VPN_CONNECTING = "Connecting..."
    const val VPN_CONNECTED = "Connected"
    const val PROXY_CONNECTING = "ProxyConnecting"
    const val PROXY_CONNECTED = "ProxyConnected"

    fun isVpnState(state: String?): Boolean {
        return state == VPN_CONNECTING || state == VPN_CONNECTED
    }

    fun isProxyState(state: String?): Boolean {
        return state == PROXY_CONNECTING || state == PROXY_CONNECTED
    }

    fun isConnectedOrConnecting(state: String?): Boolean {
        return isVpnState(state) || isProxyState(state)
    }

    fun sourceForState(state: String?): String? {
        return when {
            isVpnState(state) -> GlorytunConstants.STATE_SOURCE_VPN
            isProxyState(state) -> GlorytunConstants.STATE_SOURCE_PROXY
            else -> null
        }
    }

    fun isStatsSourceActive(connectionState: String?, statsSource: String): Boolean {
        return sourceForState(connectionState) == statsSource
    }

    fun shouldAcceptBroadcast(
        currentState: String?,
        incomingState: String,
        incomingSource: String?
    ): Boolean {
        if (incomingSource == null) return true

        val currentSource = sourceForState(currentState)
        if (incomingState == DISCONNECTED && currentSource != null && currentSource != incomingSource) {
            return false
        }

        return true
    }
}
