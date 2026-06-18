package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpReceiveReassemblerTest {
    @Test
    fun outOfOrderSegmentsAreReleasedWhenTheGapArrives() {
        val reassembler = TcpReceiveReassembler(initialSequence = 1_000, maxBufferedBytes = 65_535)

        assertTrue(reassembler.accept(1_006, "world".toByteArray()).isEmpty())
        val delivered = reassembler.accept(1_000, "hello ".toByteArray())

        assertEquals("hello world", delivered.joinToString("") { String(it) })
        assertEquals(1_011, reassembler.nextSequence)
    }

    @Test
    fun duplicateSegmentsAreIgnored() {
        val reassembler = TcpReceiveReassembler(initialSequence = 100, maxBufferedBytes = 65_535)

        assertEquals(1, reassembler.accept(100, byteArrayOf(1, 2, 3)).size)
        assertTrue(reassembler.accept(100, byteArrayOf(1, 2, 3)).isEmpty())
        assertEquals(103, reassembler.nextSequence)
    }

    @Test
    fun overlappingRetransmissionDeliversOnlyTheNewSuffix() {
        val reassembler = TcpReceiveReassembler(initialSequence = 100, maxBufferedBytes = 65_535)

        reassembler.accept(100, byteArrayOf(1, 2, 3, 4))
        val delivered = reassembler.accept(102, byteArrayOf(3, 4, 5, 6))

        assertEquals(listOf<Byte>(5, 6), delivered.single().toList())
        assertEquals(106, reassembler.nextSequence)
    }

    @Test
    fun sequenceNumbersWrapAt32Bits() {
        val reassembler = TcpReceiveReassembler(
            initialSequence = 0xffff_fffcL,
            maxBufferedBytes = 65_535
        )

        assertTrue(reassembler.accept(2, byteArrayOf(7, 8)).isEmpty())
        val delivered = reassembler.accept(0xffff_fffcL, ByteArray(6) { 1 })

        assertEquals(2, delivered.size)
        assertEquals(4, reassembler.nextSequence)
    }
}
