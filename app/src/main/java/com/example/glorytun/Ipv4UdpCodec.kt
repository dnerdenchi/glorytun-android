package com.example.glorytun

import java.net.Inet4Address
import java.net.InetAddress

data class Ipv4UdpPacket(
    val sourceAddress: Inet4Address,
    val destinationAddress: Inet4Address,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray
)

object Ipv4UdpCodec {
    private const val IPV4_HEADER_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8
    private const val IP_PROTOCOL_UDP = 17
    private const val DEFAULT_TTL = 64

    fun encode(
        sourceAddress: Inet4Address,
        destinationAddress: Inet4Address,
        sourcePort: Int,
        destinationPort: Int,
        identification: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = UDP_HEADER_LENGTH + payload.size
        val totalLength = IPV4_HEADER_LENGTH + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, identification and 0xffff)
        writeU16(packet, 6, 0x4000)
        packet[8] = DEFAULT_TTL.toByte()
        packet[9] = IP_PROTOCOL_UDP.toByte()
        sourceAddress.address.copyInto(packet, 12)
        destinationAddress.address.copyInto(packet, 16)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER_LENGTH))

        val udpOffset = IPV4_HEADER_LENGTH
        writeU16(packet, udpOffset, sourcePort)
        writeU16(packet, udpOffset + 2, destinationPort)
        writeU16(packet, udpOffset + 4, udpLength)
        writeU16(packet, udpOffset + 6, 0)
        payload.copyInto(packet, udpOffset + UDP_HEADER_LENGTH)
        writeU16(
            packet,
            udpOffset + 6,
            udpChecksum(sourceAddress, destinationAddress, packet, udpOffset, udpLength)
        )

        return packet
    }

    fun parse(packet: ByteArray, length: Int = packet.size): Ipv4UdpPacket? {
        if (length < IPV4_HEADER_LENGTH) return null
        val version = (u8(packet[0]) ushr 4) and 0x0f
        val ihl = (u8(packet[0]) and 0x0f) * 4
        if (version != 4 || ihl < IPV4_HEADER_LENGTH || length < ihl + UDP_HEADER_LENGTH) return null
        val totalLength = u16(packet, 2)
        if (totalLength < ihl + UDP_HEADER_LENGTH || totalLength > length) return null
        if (u8(packet[9]) != IP_PROTOCOL_UDP) return null

        val udpOffset = ihl
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > totalLength) return null
        val payloadOffset = udpOffset + UDP_HEADER_LENGTH
        val payloadLength = udpLength - UDP_HEADER_LENGTH

        return Ipv4UdpPacket(
            sourceAddress = InetAddress.getByAddress(packet.copyOfRange(12, 16)) as Inet4Address,
            destinationAddress = InetAddress.getByAddress(packet.copyOfRange(16, 20)) as Inet4Address,
            sourcePort = u16(packet, udpOffset),
            destinationPort = u16(packet, udpOffset + 2),
            payload = if (payloadLength == 0) ByteArray(0) else packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        )
    }

    private fun udpChecksum(
        sourceAddress: Inet4Address,
        destinationAddress: Inet4Address,
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        val pseudo = ByteArray(12 + udpLength)
        sourceAddress.address.copyInto(pseudo, 0)
        destinationAddress.address.copyInto(pseudo, 4)
        pseudo[8] = 0
        pseudo[9] = IP_PROTOCOL_UDP.toByte()
        writeU16(pseudo, 10, udpLength)
        packet.copyInto(pseudo, 12, udpOffset, udpOffset + udpLength)
        pseudo[12 + 6] = 0
        pseudo[12 + 7] = 0
        val value = checksum(pseudo, 0, pseudo.size)
        return if (value == 0) 0xffff else value
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((u8(buffer[index]) shl 8) or u8(buffer[index + 1])).toLong()
            index += 2
        }
        if (index < end) {
            sum += (u8(buffer[index]) shl 8).toLong()
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xffffL) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xffff
    }

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xff).toByte()
        buffer[offset + 1] = (value and 0xff).toByte()
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        (u8(buffer[offset]) shl 8) or u8(buffer[offset + 1])

    private fun u8(value: Byte): Int = value.toInt() and 0xff
}
