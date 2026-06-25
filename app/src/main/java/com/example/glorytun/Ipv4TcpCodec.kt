package com.example.glorytun

import java.net.Inet4Address
import java.net.InetAddress

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

data class Ipv4TcpPacket(
    val sourceAddress: Inet4Address,
    val destinationAddress: Inet4Address,
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgementNumber: Long,
    val flags: Int,
    val windowSize: Int,
    val payload: ByteArray,
    val options: TcpOptions = TcpOptions()
) {
    val hasPayload: Boolean get() = payload.isNotEmpty()
}

data class TcpOptions(
    val maxSegmentSize: Int? = null,
    val sackPermitted: Boolean = false,
    val windowScale: Int? = null
)

object Ipv4TcpCodec {
    private const val IPV4_HEADER_LENGTH = 20
    private const val TCP_HEADER_LENGTH = 20
    private const val IP_PROTOCOL_TCP = 6
    private const val DEFAULT_TTL = 64
    private const val TCP_OPTION_EOL = 0
    private const val TCP_OPTION_NOP = 1
    private const val TCP_OPTION_MSS = 2
    private const val TCP_OPTION_WINDOW_SCALE = 3
    private const val TCP_OPTION_SACK_PERMITTED = 4

    fun encode(
        sourceAddress: Inet4Address,
        destinationAddress: Inet4Address,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        flags: Int,
        windowSize: Int,
        identification: Int,
        payload: ByteArray = ByteArray(0),
        options: TcpOptions = TcpOptions()
    ): ByteArray {
        val encodedOptions = encodeOptions(options)
        val tcpHeaderLength = TCP_HEADER_LENGTH + encodedOptions.size
        val tcpLength = tcpHeaderLength + payload.size
        val totalLength = IPV4_HEADER_LENGTH + tcpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, identification and 0xffff)
        writeU16(packet, 6, 0x4000)
        packet[8] = DEFAULT_TTL.toByte()
        packet[9] = IP_PROTOCOL_TCP.toByte()
        sourceAddress.address.copyInto(packet, 12)
        destinationAddress.address.copyInto(packet, 16)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER_LENGTH))

        val tcpOffset = IPV4_HEADER_LENGTH
        writeU16(packet, tcpOffset, sourcePort)
        writeU16(packet, tcpOffset + 2, destinationPort)
        writeU32(packet, tcpOffset + 4, sequenceNumber)
        writeU32(packet, tcpOffset + 8, acknowledgementNumber)
        packet[tcpOffset + 12] = ((tcpHeaderLength / 4) shl 4).toByte()
        packet[tcpOffset + 13] = (flags and 0x3f).toByte()
        writeU16(packet, tcpOffset + 14, windowSize)
        writeU16(packet, tcpOffset + 16, 0)
        writeU16(packet, tcpOffset + 18, 0)
        encodedOptions.copyInto(packet, tcpOffset + TCP_HEADER_LENGTH)
        payload.copyInto(packet, tcpOffset + tcpHeaderLength)
        writeU16(
            packet,
            tcpOffset + 16,
            tcpChecksum(sourceAddress, destinationAddress, packet, tcpOffset, tcpLength)
        )

        return packet
    }

    fun parse(packet: ByteArray, length: Int = packet.size): Ipv4TcpPacket? {
        if (length < IPV4_HEADER_LENGTH) return null
        val version = (u8(packet[0]) ushr 4) and 0x0f
        val ihl = (u8(packet[0]) and 0x0f) * 4
        if (version != 4 || ihl < IPV4_HEADER_LENGTH || length < ihl + TCP_HEADER_LENGTH) return null
        val totalLength = u16(packet, 2)
        if (totalLength < ihl + TCP_HEADER_LENGTH || totalLength > length) return null
        if (u8(packet[9]) != IP_PROTOCOL_TCP) return null

        val tcpOffset = ihl
        val dataOffset = ((u8(packet[tcpOffset + 12]) ushr 4) and 0x0f) * 4
        if (dataOffset < TCP_HEADER_LENGTH || totalLength < tcpOffset + dataOffset) return null
        val payloadOffset = tcpOffset + dataOffset
        val payloadLength = totalLength - payloadOffset

        return Ipv4TcpPacket(
            sourceAddress = InetAddress.getByAddress(packet.copyOfRange(12, 16)) as Inet4Address,
            destinationAddress = InetAddress.getByAddress(packet.copyOfRange(16, 20)) as Inet4Address,
            sourcePort = u16(packet, tcpOffset),
            destinationPort = u16(packet, tcpOffset + 2),
            sequenceNumber = u32(packet, tcpOffset + 4),
            acknowledgementNumber = u32(packet, tcpOffset + 8),
            flags = u8(packet[tcpOffset + 13]) and 0x3f,
            windowSize = u16(packet, tcpOffset + 14),
            payload = if (payloadLength == 0) ByteArray(0) else packet.copyOfRange(payloadOffset, payloadOffset + payloadLength),
            options = parseOptions(packet, tcpOffset + TCP_HEADER_LENGTH, dataOffset - TCP_HEADER_LENGTH)
        )
    }

    fun ipv4TotalLengthPrefix(buffer: ByteArray, count: Int): Int? {
        if (count < IPV4_HEADER_LENGTH) return null
        val version = (u8(buffer[0]) ushr 4) and 0x0f
        if (version != 4) return null
        return u16(buffer, 2)
    }

    private fun tcpChecksum(
        sourceAddress: Inet4Address,
        destinationAddress: Inet4Address,
        packet: ByteArray,
        tcpOffset: Int,
        tcpLength: Int
    ): Int {
        val pseudo = ByteArray(12 + tcpLength)
        sourceAddress.address.copyInto(pseudo, 0)
        destinationAddress.address.copyInto(pseudo, 4)
        pseudo[8] = 0
        pseudo[9] = IP_PROTOCOL_TCP.toByte()
        writeU16(pseudo, 10, tcpLength)
        packet.copyInto(pseudo, 12, tcpOffset, tcpOffset + tcpLength)
        pseudo[12 + 16] = 0
        pseudo[12 + 17] = 0
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun encodeOptions(options: TcpOptions): ByteArray {
        val bytes = mutableListOf<Byte>()
        options.maxSegmentSize?.let { mss ->
            bytes += TCP_OPTION_MSS.toByte()
            bytes += 4
            bytes += ((mss ushr 8) and 0xff).toByte()
            bytes += (mss and 0xff).toByte()
        }
        if (options.sackPermitted) {
            bytes += TCP_OPTION_SACK_PERMITTED.toByte()
            bytes += 2
        }
        options.windowScale?.let { scale ->
            bytes += TCP_OPTION_WINDOW_SCALE.toByte()
            bytes += 3
            bytes += scale.coerceIn(0, 14).toByte()
        }
        while (bytes.size % 4 != 0) {
            bytes += TCP_OPTION_EOL.toByte()
        }
        return bytes.toByteArray()
    }

    private fun parseOptions(packet: ByteArray, offset: Int, length: Int): TcpOptions {
        var index = offset
        val end = offset + length
        var maxSegmentSize: Int? = null
        var sackPermitted = false
        var windowScale: Int? = null

        while (index < end) {
            when (val kind = u8(packet[index++])) {
                TCP_OPTION_EOL -> break
                TCP_OPTION_NOP -> continue
                else -> {
                    if (index >= end) break
                    val optionLength = u8(packet[index++])
                    if (optionLength < 2 || index + optionLength - 2 > end) break
                    val dataStart = index
                    when (kind) {
                        TCP_OPTION_MSS -> if (optionLength == 4) {
                            maxSegmentSize = u16(packet, dataStart)
                        }
                        TCP_OPTION_SACK_PERMITTED -> if (optionLength == 2) {
                            sackPermitted = true
                        }
                        TCP_OPTION_WINDOW_SCALE -> if (optionLength == 3) {
                            windowScale = u8(packet[dataStart]).coerceIn(0, 14)
                        }
                    }
                    index += optionLength - 2
                }
            }
        }

        return TcpOptions(
            maxSegmentSize = maxSegmentSize,
            sackPermitted = sackPermitted,
            windowScale = windowScale
        )
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

    private fun writeU32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 24) and 0xff).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xff).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xff).toByte()
        buffer[offset + 3] = (value and 0xff).toByte()
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        (u8(buffer[offset]) shl 8) or u8(buffer[offset + 1])

    private fun u32(buffer: ByteArray, offset: Int): Long =
        ((u8(buffer[offset]).toLong() shl 24) or
            (u8(buffer[offset + 1]).toLong() shl 16) or
            (u8(buffer[offset + 2]).toLong() shl 8) or
            u8(buffer[offset + 3]).toLong()) and 0xffffffffL

    private fun u8(value: Byte): Int = value.toInt() and 0xff
}
