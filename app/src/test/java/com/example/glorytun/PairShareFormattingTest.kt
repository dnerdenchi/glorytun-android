package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Test

class PairShareFormattingTest {
    @Test
    fun megabytesUseTheCorrectDivisor() {
        assertEquals("1 MB", formatPairShareBytes(1_644_000L))
        assertEquals("12 MB", formatPairShareBytes(12_999_999L))
    }

    @Test
    fun byteUnitBoundariesRemainReadable() {
        assertEquals("999 B", formatPairShareBytes(999L))
        assertEquals("1 KB", formatPairShareBytes(1_000L))
        assertEquals("1 GB", formatPairShareBytes(1_000_000_000L))
    }
}
