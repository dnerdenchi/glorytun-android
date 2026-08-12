package com.example.glorytun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairBondReconnectTest {
    @Test
    fun failedPathWaitsForReconnectInterval() {
        assertFalse(
            shouldStartPairBondConnection(
                hasTransport = false,
                connecting = false,
                nowMillis = 1_050L,
                lastAttemptMillis = 1_000L,
                reconnectIntervalMillis = 3_000L,
            ),
        )
        assertTrue(
            shouldStartPairBondConnection(
                hasTransport = false,
                connecting = false,
                nowMillis = 4_000L,
                lastAttemptMillis = 1_000L,
                reconnectIntervalMillis = 3_000L,
            ),
        )
    }

    @Test
    fun activeOrConnectingPathDoesNotStartAgain() {
        assertFalse(shouldStartPairBondConnection(true, false, 5_000L, 0L, 3_000L))
        assertFalse(shouldStartPairBondConnection(false, true, 5_000L, 0L, 3_000L))
    }
}
