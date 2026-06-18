package com.example.glorytun

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class UdpTunnelKey(
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int
)

class ProxyUdpTunnelAssociation(
    private val socket: DatagramSocket,
    private val localAddress: Inet4Address,
    private val mtu: Int,
    private val sourcePort: AtomicInteger,
    private val ipId: AtomicInteger,
    private val resolveIpv4: (String) -> Inet4Address?,
    private val packetSender: (ByteArray) -> Unit,
    private val registerAssociation: (UdpTunnelKey, ProxyUdpTunnelAssociation) -> Unit,
    private val unregisterAssociation: (UdpTunnelKey) -> Unit
) {
    private data class RemoteEndpoint(
        val address: Inet4Address,
        val port: Int
    )

    val localUdpPort: Int get() = socket.localPort

    private val active = AtomicBoolean(true)
    private val remoteToKey = ConcurrentHashMap<String, UdpTunnelKey>()
    private val keys = ConcurrentHashMap.newKeySet<UdpTunnelKey>()
    private var clientAddress: SocketAddress? = null
    private val readerThread = Thread({ readUdpLoop() }, "proxy-udp-assoc-${socket.localPort}")

    fun start() {
        readerThread.isDaemon = true
        readerThread.start()
    }

    fun waitForControlClose(input: InputStream) {
        val buffer = ByteArray(1)
        try {
            while (active.get() && input.read(buffer) >= 0) {
                // SOCKS5 UDP ASSOCIATE keeps this TCP control channel open.
            }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    fun sendToClient(packet: Ipv4UdpPacket) {
        if (!active.get()) return
        val target = clientAddress ?: return
        val response = buildSocksUdpPacket(
            hostAddress = packet.sourceAddress.address,
            port = packet.sourcePort,
            payload = packet.payload
        )
        try {
            socket.send(DatagramPacket(response, response.size, target))
        } catch (e: IOException) {
            Log.d(TAG, "UDP response send failed: ${e.message}")
            close()
        }
    }

    fun close() {
        if (!active.getAndSet(false)) return
        keys.forEach { unregisterAssociation(it) }
        keys.clear()
        socket.close()
        readerThread.interrupt()
    }

    private fun readUdpLoop() {
        val buffer = ByteArray(MAX_SOCKS_UDP_PACKET_BYTES)
        while (active.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            } catch (e: IOException) {
                if (active.get()) Log.d(TAG, "UDP associate receive failed: ${e.message}")
                break
            }

            clientAddress = packet.socketAddress
            handleClientDatagram(packet.data, packet.offset, packet.length)
        }
        close()
    }

    private fun handleClientDatagram(buffer: ByteArray, offset: Int, length: Int) {
        val request = parseSocksUdpPacket(buffer, offset, length) ?: return
        val key = keyFor(request.endpoint) ?: return
        val ipPacket = Ipv4UdpCodec.encode(
            sourceAddress = localAddress,
            destinationAddress = request.endpoint.address,
            sourcePort = key.localPort,
            destinationPort = key.remotePort,
            identification = ipId.incrementAndGet(),
            payload = request.payload
        )
        if (ipPacket.size > mtu) {
            Log.d(TAG, "Dropping UDP packet larger than tunnel MTU: ${ipPacket.size} > $mtu")
            return
        }
        packetSender(ipPacket)
    }

    private fun keyFor(endpoint: RemoteEndpoint): UdpTunnelKey? {
        val remoteKey = "${endpoint.address.hostAddress}:${endpoint.port}"
        return remoteToKey[remoteKey] ?: synchronized(remoteToKey) {
            remoteToKey[remoteKey] ?: allocateKey(endpoint)?.also { key ->
                remoteToKey[remoteKey] = key
                keys.add(key)
                registerAssociation(key, this)
            }
        }
    }

    private fun allocateKey(endpoint: RemoteEndpoint): UdpTunnelKey? {
        repeat(MAX_PORT_PROBES) {
            val localPort = sourcePort.updateAndGet { current ->
                if (current >= LAST_EPHEMERAL_PORT) FIRST_EPHEMERAL_PORT else current + 1
            }
            return UdpTunnelKey(
                localPort = localPort,
                remoteAddress = endpoint.address.hostAddress ?: return null,
                remotePort = endpoint.port
            )
        }
        return null
    }

    private fun parseSocksUdpPacket(buffer: ByteArray, offset: Int, length: Int): SocksUdpRequest? {
        if (length < SOCKS_UDP_MIN_HEADER_BYTES) return null
        var index = offset
        if (u8(buffer[index]) != 0 || u8(buffer[index + 1]) != 0) return null
        index += 2
        val fragment = u8(buffer[index++])
        if (fragment != 0) return null
        val addressType = u8(buffer[index++])

        val host = when (addressType) {
            SOCKS5_IPV4 -> {
                if (index + 4 + 2 > offset + length) return null
                val address = InetAddress.getByAddress(buffer.copyOfRange(index, index + 4)) as Inet4Address
                index += 4
                address
            }
            SOCKS5_DOMAIN -> {
                if (index >= offset + length) return null
                val hostLength = u8(buffer[index++])
                if (index + hostLength + 2 > offset + length) return null
                val hostName = String(buffer, index, hostLength, Charsets.UTF_8)
                index += hostLength
                resolveIpv4(hostName) ?: return null
            }
            SOCKS5_IPV6 -> return null
            else -> return null
        }

        val port = u16(buffer, index)
        index += 2
        val payloadLength = offset + length - index
        if (payloadLength < 0) return null

        return SocksUdpRequest(
            endpoint = RemoteEndpoint(host, port),
            payload = if (payloadLength == 0) ByteArray(0) else buffer.copyOfRange(index, index + payloadLength)
        )
    }

    private fun buildSocksUdpPacket(hostAddress: ByteArray, port: Int, payload: ByteArray): ByteArray {
        val response = ByteArray(4 + 4 + 2 + payload.size)
        response[0] = 0
        response[1] = 0
        response[2] = 0
        response[3] = SOCKS5_IPV4.toByte()
        hostAddress.copyInto(response, 4)
        writeU16(response, 8, port)
        payload.copyInto(response, 10)
        return response
    }

    private data class SocksUdpRequest(
        val endpoint: RemoteEndpoint,
        val payload: ByteArray
    )

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xff).toByte()
        buffer[offset + 1] = (value and 0xff).toByte()
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        (u8(buffer[offset]) shl 8) or u8(buffer[offset + 1])

    private fun u8(value: Byte): Int = value.toInt() and 0xff

    companion object {
        private const val TAG = "ProxyUdpTunnelAssociation"
        private const val MAX_SOCKS_UDP_PACKET_BYTES = 65_535
        private const val SOCKS_UDP_MIN_HEADER_BYTES = 10
        private const val FIRST_EPHEMERAL_PORT = 20_000
        private const val LAST_EPHEMERAL_PORT = 60_999
        private const val MAX_PORT_PROBES = 41_000
        private const val SOCKS5_IPV4 = 0x01
        private const val SOCKS5_DOMAIN = 0x03
        private const val SOCKS5_IPV6 = 0x04

        fun bindLoopback(port: Int = 0): DatagramSocket =
            DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }
}
