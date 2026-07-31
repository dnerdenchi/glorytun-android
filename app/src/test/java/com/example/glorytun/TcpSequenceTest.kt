package com.example.glorytun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSequenceTest {
    @Test
    fun acknowledgementAfterWrapReleasesSegmentsFromBeforeWrap() {
        assertTrue(
            TcpSequence.isAcknowledged(
                segmentEnd = 0xffff_fff0L,
                acknowledgement = 0x0000_0010L,
            )
        )
        assertTrue(
            TcpSequence.isAcknowledged(
                segmentEnd = 0x0000_0010L,
                acknowledgement = 0x0000_0010L,
            )
        )
        assertFalse(
            TcpSequence.isAcknowledged(
                segmentEnd = 0x0000_0020L,
                acknowledgement = 0x0000_0010L,
            )
        )
    }

    @Test
    fun advanceWrapsAtTheTcpSequenceBoundary() {
        assertTrue(TcpSequence.advance(0xffff_fff0L, 32) == 0x0000_0010L)
    }
}
