package com.example.glorytun

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PairShareCryptoTest {
    @Test
    fun `both pairing sides derive the same key`() {
        val host = PairShareCrypto.generateEphemeralKeyPair()
        val client = PairShareCrypto.generateEphemeralKeyPair()
        val hostSecret = PairShareCrypto.sharedSecret(host.private, client.public)
        val clientSecret = PairShareCrypto.sharedSecret(client.private, host.public)

        val hostKey = PairShareCrypto.derivePairKey(
            hostSecret,
            "123456",
            "host-id",
            "client-id",
        )
        val clientKey = PairShareCrypto.derivePairKey(
            clientSecret,
            "123456",
            "host-id",
            "client-id",
        )
        val differentCodeKey = PairShareCrypto.derivePairKey(
            clientSecret,
            "654321",
            "host-id",
            "client-id",
        )

        assertArrayEquals(hostKey, clientKey)
        assertFalse(PairShareCrypto.constantTimeEquals(hostKey, differentCodeKey))
    }

    @Test
    fun `encrypted frames round trip only in the matching direction`() {
        val key = PairShareCrypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        val sender = PairShareFrameCodec(
            input = DataInputStream(ByteArrayInputStream(ByteArray(0))),
            output = DataOutputStream(wire),
            key = key,
            inboundLabel = "host-to-client",
            outboundLabel = "client-to-host",
        )
        sender.send(PairShareFrameType.TCP_DATA, 42, "payload".toByteArray())

        val receiver = PairShareFrameCodec(
            input = DataInputStream(ByteArrayInputStream(wire.toByteArray())),
            output = DataOutputStream(ByteArrayOutputStream()),
            key = key,
            inboundLabel = "client-to-host",
            outboundLabel = "host-to-client",
        )
        val frame = receiver.read()

        assertEquals(PairShareFrameType.TCP_DATA, frame.type)
        assertEquals(42, frame.streamId)
        assertArrayEquals("payload".toByteArray(), frame.payload)
    }

    @Test(expected = GeneralSecurityException::class)
    fun `tampered encrypted frame is rejected`() {
        val key = PairShareCrypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        val sender = PairShareFrameCodec(
            input = DataInputStream(ByteArrayInputStream(ByteArray(0))),
            output = DataOutputStream(wire),
            key = key,
            inboundLabel = "host-to-client",
            outboundLabel = "client-to-host",
        )
        sender.send(PairShareFrameType.TCP_DATA, 1, byteArrayOf(1, 2, 3))
        val tampered = wire.toByteArray().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }

        PairShareFrameCodec(
            input = DataInputStream(ByteArrayInputStream(tampered)),
            output = DataOutputStream(ByteArrayOutputStream()),
            key = key,
            inboundLabel = "client-to-host",
            outboundLabel = "host-to-client",
        ).read()
    }

    @Test
    fun `concurrent relay writers keep encrypted frame records intact`() {
        val key = PairShareCrypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        val sender = PairShareFrameCodec(
            input = DataInputStream(ByteArrayInputStream(ByteArray(0))),
            output = DataOutputStream(wire),
            key = key,
            inboundLabel = "host-to-client",
            outboundLabel = "client-to-host",
        )
        val started = CountDownLatch(1)
        val completed = CountDownLatch(24)
        repeat(24) { index ->
            Thread {
                started.await()
                sender.send(PairShareFrameType.PING, index)
                completed.countDown()
            }.start()
        }
        started.countDown()
        assertEquals(true, completed.await(5, TimeUnit.SECONDS))

        val receiver = PairShareFrameCodec(
            input = DataInputStream(ByteArrayInputStream(wire.toByteArray())),
            output = DataOutputStream(ByteArrayOutputStream()),
            key = key,
            inboundLabel = "client-to-host",
            outboundLabel = "host-to-client",
        )
        val streamIds = buildSet {
            repeat(24) {
                val frame = receiver.read()
                assertEquals(PairShareFrameType.PING, frame.type)
                add(frame.streamId)
            }
        }
        assertEquals((0 until 24).toSet(), streamIds)
    }
}
