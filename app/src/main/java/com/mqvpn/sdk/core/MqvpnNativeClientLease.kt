package com.mqvpn.sdk.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes native mqvpn clients within one Android process.
 * The bundled JNI bridge owns a single process-global callback context, so a
 * second client must not be created until the previous client is destroyed.
 */
internal class MqvpnNativeClientLease {
    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private var owner: Any? = null

    fun acquire(requester: Any, timeoutMs: Long): Boolean = lock.withLock {
        if (owner === requester) return true

        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        while (owner != null) {
            if (remainingNanos <= 0) return false
            remainingNanos = try {
                released.awaitNanos(remainingNanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        owner = requester
        true
    }

    fun release(requester: Any) = lock.withLock {
        if (owner !== requester) return
        owner = null
        released.signalAll()
    }

    companion object {
        val processWide = MqvpnNativeClientLease()
    }
}
