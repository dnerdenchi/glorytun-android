package com.mqvpn.sdk.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPollGateTest {
    @Test
    fun telemetryIsPolledAtMostTwicePerSecond() {
        val gate = TelemetryPollGate(intervalMs = 500)

        assertTrue(gate.shouldPoll(1_000))
        assertFalse(gate.shouldPoll(1_499))
        assertTrue(gate.shouldPoll(1_500))
    }

    @Test
    fun pathEventForcesTheNextTelemetrySnapshot() {
        val gate = TelemetryPollGate(intervalMs = 500)
        assertTrue(gate.shouldPoll(1_000))

        gate.requestImmediatePoll()

        assertTrue(gate.shouldPoll(1_001))
        assertFalse(gate.shouldPoll(1_002))
    }

    @Test
    fun resetPollsImmediatelyForANewTunnel() {
        val gate = TelemetryPollGate(intervalMs = 500)
        assertTrue(gate.shouldPoll(1_000))
        gate.reset()

        assertTrue(gate.shouldPoll(1_001))
    }
}
