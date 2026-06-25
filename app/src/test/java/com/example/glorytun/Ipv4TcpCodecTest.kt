package com.example.glorytun

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class Ipv4TcpCodecTest {
    @Test
    fun encodeAndParseTcpPacketRoundTrip() {
        val source = InetAddress.getByName("10.8.0.2") as Inet4Address
        val destination = InetAddress.getByName("93.184.216.34") as Inet4Address
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray()

        val encoded = Ipv4TcpCodec.encode(
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = 23456,
            destinationPort = 443,
            sequenceNumber = 1234L,
            acknowledgementNumber = 9876L,
            flags = TcpFlags.ACK or TcpFlags.PSH,
            windowSize = 65535,
            identification = 42,
            payload = payload
        )

        assertEquals(encoded.size, Ipv4TcpCodec.ipv4TotalLengthPrefix(encoded, encoded.size))

        val decoded = Ipv4TcpCodec.parse(encoded)
        assertNotNull(decoded)
        decoded!!
        assertEquals(source, decoded.sourceAddress)
        assertEquals(destination, decoded.destinationAddress)
        assertEquals(23456, decoded.sourcePort)
        assertEquals(443, decoded.destinationPort)
        assertEquals(1234L, decoded.sequenceNumber)
        assertEquals(9876L, decoded.acknowledgementNumber)
        assertEquals(TcpFlags.ACK or TcpFlags.PSH, decoded.flags)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun parseRejectsNonTcpIpv4Packet() {
        val packet = ByteArray(20)
        packet[0] = 0x45
        packet[9] = 17
        packet[2] = 0
        packet[3] = 20

        assertEquals(null, Ipv4TcpCodec.parse(packet))
    }

    @Test
    fun encodeAndParseTcpOptionsWithPayload() {
        val source = InetAddress.getByName("10.8.0.2") as Inet4Address
        val destination = InetAddress.getByName("93.184.216.34") as Inet4Address
        val payload = byteArrayOf(1, 2, 3, 4)

        val encoded = Ipv4TcpCodec.encode(
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = 23456,
            destinationPort = 443,
            sequenceNumber = 1234L,
            acknowledgementNumber = 0L,
            flags = TcpFlags.SYN,
            windowSize = 65535,
            identification = 43,
            payload = payload,
            options = TcpOptions(
                maxSegmentSize = 1200,
                sackPermitted = true,
                windowScale = 6
            )
        )

        val decoded = Ipv4TcpCodec.parse(encoded)
        assertNotNull(decoded)
        decoded!!
        assertEquals(TcpFlags.SYN, decoded.flags)
        assertEquals(1200, decoded.options.maxSegmentSize)
        assertEquals(true, decoded.options.sackPermitted)
        assertEquals(6, decoded.options.windowScale)
        assertArrayEquals(payload, decoded.payload)
    }
}
