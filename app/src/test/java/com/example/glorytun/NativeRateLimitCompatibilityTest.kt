package com.example.glorytun

import com.mqvpn.sdk.core.internal.callNativeRateLimitSafely
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeRateLimitCompatibilityTest {
    @Test
    fun returnsNativeResultWhenApiExists() {
        assertEquals(0, callNativeRateLimitSafely { 0 })
    }

    @Test
    fun missingNativeApiDoesNotEscapeAndKillThePoller() {
        assertNull(callNativeRateLimitSafely { throw UnsatisfiedLinkError("missing") })
    }
}
