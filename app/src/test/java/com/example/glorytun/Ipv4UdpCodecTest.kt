package com.example.glorytun

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class Ipv4UdpCodecTest {
    @Test
    fun encodeAndParseUdpPacketRoundTrip() {
        val source = InetAddress.getByName("10.8.0.2") as Inet4Address
        val destination = InetAddress.getByName("8.8.8.8") as Inet4Address
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00)

        val encoded = Ipv4UdpCodec.encode(
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = 24000,
            destinationPort = 53,
            identification = 77,
            payload = payload
        )

        val decoded = Ipv4UdpCodec.parse(encoded)
        assertNotNull(decoded)
        decoded!!
        assertEquals(source, decoded.sourceAddress)
        assertEquals(destination, decoded.destinationAddress)
        assertEquals(24000, decoded.sourcePort)
        assertEquals(53, decoded.destinationPort)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun parseRejectsNonUdpIpv4Packet() {
        val packet = ByteArray(20)
        packet[0] = 0x45
        packet[9] = 6
        packet[2] = 0
        packet[3] = 20

        assertEquals(null, Ipv4UdpCodec.parse(packet))
    }
}
