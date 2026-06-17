package com.example.glorytun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatesTest {
    @Test
    fun vpnDisconnectedDoesNotOverrideActiveProxyState() {
        val accepted = ConnectionStates.shouldAcceptBroadcast(
            currentState = ConnectionStates.PROXY_CONNECTED,
            incomingState = ConnectionStates.DISCONNECTED,
            incomingSource = GlorytunConstants.STATE_SOURCE_VPN
        )

        assertFalse(accepted)
    }

    @Test
    fun proxyDisconnectedDoesNotOverrideActiveVpnState() {
        val accepted = ConnectionStates.shouldAcceptBroadcast(
            currentState = ConnectionStates.VPN_CONNECTED,
            incomingState = ConnectionStates.DISCONNECTED,
            incomingSource = GlorytunConstants.STATE_SOURCE_PROXY
        )

        assertFalse(accepted)
    }

    @Test
    fun matchingDisconnectedSourceIsAccepted() {
        val accepted = ConnectionStates.shouldAcceptBroadcast(
            currentState = ConnectionStates.PROXY_CONNECTED,
            incomingState = ConnectionStates.DISCONNECTED,
            incomingSource = GlorytunConstants.STATE_SOURCE_PROXY
        )

        assertTrue(accepted)
    }

    @Test
    fun statsSourceMatchesOnlyActiveConnectionKind() {
        assertTrue(
            ConnectionStates.isStatsSourceActive(
                ConnectionStates.PROXY_CONNECTING,
                GlorytunConstants.STATE_SOURCE_PROXY
            )
        )
        assertFalse(
            ConnectionStates.isStatsSourceActive(
                ConnectionStates.PROXY_CONNECTING,
                GlorytunConstants.STATE_SOURCE_VPN
            )
        )
    }
}
