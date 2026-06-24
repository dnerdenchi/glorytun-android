package com.mqvpn.sdk.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqvpnClosePolicyTest {
    @Test
    fun cleanCloseDoesNotEmitState() {
        assertNull(
            MqvpnClosePolicy.stateForClose(
                errorCode = 0,
                reconnectEnabled = true,
                reconnectIntervalSec = 5,
            )
        )
    }

    @Test
    fun retryableCloseKeepsTunnelReconnectingWhenReconnectIsEnabled() {
        val state = MqvpnClosePolicy.stateForClose(
            errorCode = -10,
            reconnectEnabled = true,
            reconnectIntervalSec = 5,
        )

        assertTrue(state is MqvpnState.Reconnecting)
        assertEquals(5, (state as MqvpnState.Reconnecting).info.delaySec)
    }

    @Test
    fun connectionTimeoutKeepsTunnelReconnectingWhenReconnectIsEnabled() {
        val state = MqvpnClosePolicy.stateForClose(
            errorCode = -12,
            reconnectEnabled = true,
            reconnectIntervalSec = 7,
        )

        assertTrue(state is MqvpnState.Reconnecting)
        assertEquals(7, (state as MqvpnState.Reconnecting).info.delaySec)
    }

    @Test
    fun authFailureRemainsTerminalError() {
        val state = MqvpnClosePolicy.stateForClose(
            errorCode = -5,
            reconnectEnabled = true,
            reconnectIntervalSec = 5,
        )

        assertTrue(state is MqvpnState.Error)
        assertTrue((state as MqvpnState.Error).error is MqvpnError.AuthFailed)
    }

    @Test
    fun retryableCloseIsTerminalWhenReconnectIsDisabled() {
        val state = MqvpnClosePolicy.stateForClose(
            errorCode = -10,
            reconnectEnabled = false,
            reconnectIntervalSec = 5,
        )

        assertTrue(state is MqvpnState.Error)
        assertTrue((state as MqvpnState.Error).error is MqvpnError.Timeout)
    }
}
