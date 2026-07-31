package com.mqvpn.sdk.core

/** Limits JNI telemetry snapshots without slowing the native engine tick. */
internal class TelemetryPollGate(private val intervalMs: Long) {
    private var lastPollMs: Long? = null
    private var immediatePollRequested = false

    @Synchronized
    fun shouldPoll(nowMs: Long): Boolean {
        val previous = lastPollMs
        val due = immediatePollRequested || previous == null ||
            nowMs < previous || nowMs - previous >= intervalMs
        if (!due) return false

        lastPollMs = nowMs
        immediatePollRequested = false
        return true
    }

    @Synchronized
    fun requestImmediatePoll() {
        immediatePollRequested = true
    }

    @Synchronized
    fun reset() {
        lastPollMs = null
        immediatePollRequested = false
    }
}
