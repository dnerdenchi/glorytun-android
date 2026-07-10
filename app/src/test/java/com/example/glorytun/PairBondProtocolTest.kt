package com.example.glorytun

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PairBondProtocolTest {
    @Test
    fun encryptedRecordsRoundTripInTheOppositeDirection() {
        val key = PairShareCrypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        val sender = PairBondFrameCodec(
            input = DataInputStream(ByteArrayInputStream(ByteArray(0))),
            output = DataOutputStream(wire),
            key = key,
            inboundLabel = "server-to-client",
            outboundLabel = "client-to-server",
        )
        sender.send(PairBondFrame(PairBondFrameType.TCP_DATA, 7, 42L, byteArrayOf(1, 2, 3)))

        val receiver = PairBondFrameCodec(
            input = DataInputStream(ByteArrayInputStream(wire.toByteArray())),
            output = DataOutputStream(ByteArrayOutputStream()),
            key = key,
            inboundLabel = "client-to-server",
            outboundLabel = "server-to-client",
        )
        val frame = receiver.read()

        assertEquals(PairBondFrameType.TCP_DATA, frame.type)
        assertEquals(7, frame.flowId)
        assertEquals(42L, frame.sequence)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.payload)
    }

    @Test
    fun reassemblerWaitsForTheMissingRangeAndIgnoresDuplicates() {
        val reassembler = PairBondOrderedReassembler()

        assertEquals(emptyList<ByteArray>(), reassembler.offer(3L, "def".toByteArray()))
        val contiguous = reassembler.offer(0L, "abc".toByteArray())

        assertEquals(2, contiguous.size)
        assertArrayEquals("abc".toByteArray(), contiguous[0])
        assertArrayEquals("def".toByteArray(), contiguous[1])
        assertEquals(6L, reassembler.nextOffset)
        assertEquals(emptyList<ByteArray>(), reassembler.offer(0L, "abc".toByteArray()))
    }

    @Test
    fun backupPathIsNotSelectedWhileAnActivePathIsHealthy() {
        val scheduler = PairBondPathScheduler()
        val active = PairBondPathSnapshot(
            id = "active",
            priority = PairBondPathPriority.ACTIVE,
            ready = true,
            rttMillis = 50,
            lossPermille = 0,
            deliveryRateBps = 5_000_000,
            inFlightBytes = 0,
        )
        val backup = active.copy(id = "backup", priority = PairBondPathPriority.BACKUP_ONLY)

        repeat(20) {
            assertEquals("active", scheduler.select(listOf(active, backup))?.id)
        }
        assertEquals("backup", scheduler.select(listOf(active.copy(ready = false), backup))?.id)
    }

    @Test
    fun unknownPriorityIsDisabled() {
        assertEquals(PairBondPathPriority.DISABLED, PairBondPathPriority.fromStored("unknown"))
        assertFalse(PairBondPathPriority.fromStored(null) == PairBondPathPriority.ACTIVE)
    }
}
