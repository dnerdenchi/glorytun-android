package com.mqvpn.sdk.core.internal

import java.util.concurrent.locks.LockSupport

internal class PathRateLimiter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val parkNanos: (Long) -> Unit = LockSupport::parkNanos,
) {
    @Volatile
    private var bytesPerSecond = 0L
    private var nextAvailableNanos = 0L

    @Synchronized
    fun updateRate(newBytesPerSecond: Long) {
        val normalized = newBytesPerSecond.coerceAtLeast(0L)
        if (normalized == bytesPerSecond) return
        bytesPerSecond = normalized
        nextAvailableNanos = 0L
    }

    fun acquire(byteCount: Int) {
        val delay = reserveDelayNanos(byteCount, nanoTime())
        if (delay > 0L) parkNanos(delay)
    }

    @Synchronized
    internal fun reserveDelayNanos(byteCount: Int, nowNanos: Long): Long {
        val rate = bytesPerSecond
        if (rate <= 0L || byteCount <= 0) {
            nextAvailableNanos = nowNanos
            return 0L
        }

        val start = maxOf(nowNanos, nextAvailableNanos)
        val duration = ((byteCount.toDouble() / rate.toDouble()) * NANOS_PER_SECOND)
            .toLong()
            .coerceAtLeast(1L)
        nextAvailableNanos = start + duration
        return (start - nowNanos).coerceAtLeast(0L)
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
