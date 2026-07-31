package com.mqvpn.sdk.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqvpnNativeClientLeaseTest {
    @Test
    fun secondClientCannotStartUntilFirstClientIsDestroyed() {
        val lease = MqvpnNativeClientLease()
        val first = Any()
        val second = Any()

        assertTrue(lease.acquire(first, timeoutMs = 50))
        assertFalse(lease.acquire(second, timeoutMs = 20))

        lease.release(first)
        assertTrue(lease.acquire(second, timeoutMs = 50))
        lease.release(second)
    }

    @Test
    fun differentOwnerCannotReleaseActiveClient() {
        val lease = MqvpnNativeClientLease()
        val owner = Any()

        assertTrue(lease.acquire(owner, timeoutMs = 50))
        lease.release(Any())

        assertFalse(lease.acquire(Any(), timeoutMs = 20))
        lease.release(owner)
    }
}
