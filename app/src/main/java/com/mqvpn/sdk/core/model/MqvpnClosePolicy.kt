package com.mqvpn.sdk.core.model

internal object MqvpnClosePolicy {
    fun stateForClose(
        errorCode: Int,
        reconnectEnabled: Boolean,
        reconnectIntervalSec: Int,
    ): MqvpnState? {
        if (errorCode == 0) return null

        if (reconnectEnabled && MqvpnError.isRetryableCloseCode(errorCode)) {
            return MqvpnState.Reconnecting(ReconnectInfo(reconnectIntervalSec))
        }

        return MqvpnState.Error(MqvpnError.fromNativeCode(errorCode))
    }
}
