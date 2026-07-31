package com.example.glorytun

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProxyTcpTunnelConnectionTest {
    @Test
    fun closeDoesNotWaitForABlockedPacketSender() {
        val executor = Executors.newCachedThreadPool()
        val packetCalls = AtomicInteger()
        val ackSendEntered = CountDownLatch(1)
        val releaseAckSend = CountDownLatch(1)
        val localAddress = ipv4("10.8.0.2")
        val remoteAddress = ipv4("203.0.113.10")
        val key = TcpTunnelKey(
            localPort = 40_001,
            remoteAddress = remoteAddress.hostAddress!!,
            remotePort = 443,
        )
        val connection = ProxyTcpTunnelConnection(
            key = key,
            localAddress = localAddress,
            remoteAddress = remoteAddress,
            clientOutput = ByteArrayOutputStream(),
            mtu = 1_382,
            packetSender = {
                if (packetCalls.incrementAndGet() >= 2) {
                    ackSendEntered.countDown()
                    releaseAckSend.await(5, TimeUnit.SECONDS)
                }
            },
            onClosed = {},
        )

        try {
            val startFuture = executor.submit<Boolean> { connection.start(2_000) }
            val synAck = Ipv4TcpPacket(
                sourceAddress = remoteAddress,
                destinationAddress = localAddress,
                sourcePort = 443,
                destinationPort = key.localPort,
                sequenceNumber = 1_000,
                acknowledgementNumber = 1,
                flags = TcpFlags.SYN or TcpFlags.ACK,
                windowSize = 65_535,
                payload = ByteArray(0),
                options = TcpOptions(windowScale = 6),
            )
            val packetFuture = executor.submit { connection.handlePacket(synAck) }

            assertTrue("SYN-ACK response did not reach the blocked sender", ackSendEntered.await(1, TimeUnit.SECONDS))

            val closeFuture = executor.submit { connection.close() }
            assertTrue(
                "close() must not wait for native packet backpressure",
                runCatching {
                    closeFuture.get(500, TimeUnit.MILLISECONDS)
                    true
                }.getOrDefault(false),
            )

            releaseAckSend.countDown()
            packetFuture.get(1, TimeUnit.SECONDS)
            startFuture.get(1, TimeUnit.SECONDS)
        } finally {
            releaseAckSend.countDown()
            connection.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun adjacentPureAcksAreCoalescedToTheNewestAcknowledgement() {
        val executor = Executors.newCachedThreadPool()
        val sentPackets = CopyOnWriteArrayList<ByteArray>()
        val firstAckSendEntered = CountDownLatch(1)
        val releaseFirstAckSend = CountDownLatch(1)
        val localAddress = ipv4("10.8.0.2")
        val remoteAddress = ipv4("203.0.113.10")
        val key = TcpTunnelKey(40_002, remoteAddress.hostAddress!!, 443)
        val connection = ProxyTcpTunnelConnection(
            key = key,
            localAddress = localAddress,
            remoteAddress = remoteAddress,
            clientOutput = ByteArrayOutputStream(),
            mtu = 1_382,
            packetSender = { packet ->
                sentPackets += packet
                if (sentPackets.size == 2) {
                    firstAckSendEntered.countDown()
                    releaseFirstAckSend.await(5, TimeUnit.SECONDS)
                }
            },
            onClosed = {},
        )

        try {
            val startFuture = executor.submit<Boolean> { connection.start(2_000) }
            assertTrue(awaitUntil(1_000) { sentPackets.isNotEmpty() })
            val syn = Ipv4TcpCodec.parse(sentPackets.first())!!
            connection.handlePacket(
                Ipv4TcpPacket(
                    sourceAddress = remoteAddress,
                    destinationAddress = localAddress,
                    sourcePort = 443,
                    destinationPort = key.localPort,
                    sequenceNumber = 1_000,
                    acknowledgementNumber = TcpSequence.advance(syn.sequenceNumber, 1),
                    flags = TcpFlags.SYN or TcpFlags.ACK,
                    windowSize = 65_535,
                    payload = ByteArray(0),
                    options = TcpOptions(windowScale = 6),
                )
            )
            assertTrue(firstAckSendEntered.await(1, TimeUnit.SECONDS))
            assertTrue(startFuture.get(1, TimeUnit.SECONDS))

            repeat(50) { index ->
                connection.handlePacket(
                    Ipv4TcpPacket(
                        sourceAddress = remoteAddress,
                        destinationAddress = localAddress,
                        sourcePort = 443,
                        destinationPort = key.localPort,
                        sequenceNumber = 1_001L + index * 100L,
                        acknowledgementNumber = syn.sequenceNumber,
                        flags = TcpFlags.ACK or TcpFlags.PSH,
                        windowSize = 65_535,
                        payload = ByteArray(100) { index.toByte() },
                    )
                )
            }

            releaseFirstAckSend.countDown()
            assertTrue(awaitUntil(1_000) { sentPackets.size >= 3 })
            Thread.sleep(100)

            assertTrue(
                "Only the blocked ACK and newest cumulative ACK should be sent",
                sentPackets.size <= 3,
            )
            val newestAck = Ipv4TcpCodec.parse(sentPackets.last())!!
            assertTrue(newestAck.acknowledgementNumber == 6_001L)
        } finally {
            releaseFirstAckSend.countDown()
            connection.close()
            executor.shutdownNow()
        }
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address
}
