package com.example.glorytun

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PairShareSession"

private object PairSharePayload {
    data class TcpTarget(val host: String, val port: Int)
    data class UdpDatagram(val host: String, val port: Int, val payload: ByteArray)

    fun tcpTarget(host: String, port: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            PairShareWire.writeString(output, host, MAX_HOST_BYTES)
            output.writeShort(port)
        }
    }.toByteArray()

    fun parseTcpTarget(payload: ByteArray): TcpTarget {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val host = PairShareWire.readString(input, MAX_HOST_BYTES)
        val port = input.readUnsignedShort()
        if (host.isBlank() || port !in 1..65535 || input.available() != 0) {
            throw IOException("不正な TCP 接続要求です")
        }
        return TcpTarget(host, port)
    }

    fun udpDatagram(host: String, port: Int, data: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            PairShareWire.writeString(output, host, MAX_HOST_BYTES)
            output.writeShort(port)
            PairShareWire.writeBytes(output, data, PairShareFrameCodec.MAX_PAYLOAD_BYTES - 1024)
        }
    }.toByteArray()

    fun parseUdpDatagram(payload: ByteArray): UdpDatagram {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val host = PairShareWire.readString(input, MAX_HOST_BYTES)
        val port = input.readUnsignedShort()
        val data = PairShareWire.readBytes(input, PairShareFrameCodec.MAX_PAYLOAD_BYTES - 1024)
        if (host.isBlank() || port !in 1..65535 || input.available() != 0) {
            throw IOException("不正な UDP データです")
        }
        return UdpDatagram(host, port, data)
    }

    fun message(value: String): ByteArray = value
        .take(MAX_ERROR_BYTES)
        .toByteArray(StandardCharsets.UTF_8)

    fun parseMessage(payload: ByteArray): String =
        String(payload.copyOf(MAX_ERROR_BYTES.coerceAtMost(payload.size)), StandardCharsets.UTF_8)

    private const val MAX_HOST_BYTES = 253
    private const val MAX_ERROR_BYTES = 512
}

class PairShareTcpStream internal constructor(
    private val client: PairShareClient,
    val id: Int,
) : Closeable {
    private val opened = CountDownLatch(1)
    private val inbound = PipedInputStream(INBOUND_BUFFER_BYTES)
    private val inboundWriter = PipedOutputStream(inbound)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var openError: String? = null

    @Volatile
    private var openedSuccessfully = false

    fun awaitOpen(timeoutMillis: Long = OPEN_TIMEOUT_MILLIS): Boolean {
        if (!opened.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            openError = "ペア端末への接続がタイムアウトしました"
            return false
        }
        return openedSuccessfully
    }

    fun errorMessage(): String? = openError

    fun input(): InputStream = inbound

    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        if (closed.get()) throw IOException("Pair & Share TCP ストリームは閉じられています")
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            val amount = remaining.coerceAtMost(MAX_TCP_CHUNK_BYTES)
            client.sendTcpData(id, buffer.copyOfRange(currentOffset, currentOffset + amount))
            currentOffset += amount
            remaining -= amount
        }
    }

    internal fun markOpen(success: Boolean, error: String? = null) {
        openedSuccessfully = success
        openError = error
        opened.countDown()
        if (!success) closeInbound()
    }

    internal fun onData(data: ByteArray) {
        if (closed.get()) return
        try {
            inboundWriter.write(data)
            inboundWriter.flush()
        } catch (_: IOException) {
            close()
        }
    }

    internal fun closeFromPeer() {
        if (closed.compareAndSet(false, true)) {
            closeInbound()
            opened.countDown()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.closeTcp(id)
            closeInbound()
            opened.countDown()
        }
    }

    private fun closeInbound() {
        runCatching { inboundWriter.close() }
        runCatching { inbound.close() }
    }

    companion object {
        private const val OPEN_TIMEOUT_MILLIS = 20_000L
        private const val INBOUND_BUFFER_BYTES = 256 * 1024
        private const val MAX_TCP_CHUNK_BYTES = 16 * 1024
    }
}

class PairShareUdpStream internal constructor(
    private val client: PairShareClient,
    val id: Int,
) : Closeable {
    private val opened = CountDownLatch(1)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var openedSuccessfully = false

    @Volatile
    private var openError: String? = null

    @Volatile
    var onDatagram: ((host: String, port: Int, payload: ByteArray) -> Unit)? = null

    fun awaitOpen(timeoutMillis: Long = 20_000L): Boolean {
        if (!opened.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            openError = "UDP 共有接続がタイムアウトしました"
            return false
        }
        return openedSuccessfully
    }

    fun errorMessage(): String? = openError

    fun send(host: String, port: Int, payload: ByteArray) {
        if (closed.get()) throw IOException("Pair & Share UDP ストリームは閉じられています")
        client.sendUdpData(id, host, port, payload)
    }

    internal fun markOpen(success: Boolean, error: String? = null) {
        openedSuccessfully = success
        openError = error
        opened.countDown()
    }

    internal fun onDatagram(host: String, port: Int, payload: ByteArray) {
        if (!closed.get()) onDatagram?.invoke(host, port, payload)
    }

    internal fun closeFromPeer() {
        if (closed.compareAndSet(false, true)) opened.countDown()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.closeUdp(id)
            opened.countDown()
        }
    }
}

/** One authenticated, multiplexed LAN session from a receiving device to a sharing device. */
class PairShareClient(
    context: Context,
    private val peer: PairSharePeer,
) : Closeable {
    private val appContext = context.applicationContext
    private val tcpStreams = ConcurrentHashMap<Int, PairShareTcpStream>()
    private val udpStreams = ConcurrentHashMap<Int, PairShareUdpStream>()
    private val nextStreamId = AtomicInteger(1)
    private val closed = AtomicBoolean(false)

    private lateinit var socket: Socket
    private lateinit var codec: PairShareFrameCodec

    init {
        connect()
    }

    fun matches(other: PairSharePeer): Boolean = peer.id == other.id &&
        peer.sharedKey == other.sharedKey && peer.address == other.address && peer.port == other.port

    fun isAlive(): Boolean = !closed.get() && ::socket.isInitialized && socket.isConnected && !socket.isClosed

    fun openTcp(host: String, port: Int): PairShareTcpStream {
        require(host.isNotBlank() && port in 1..65535) { "接続先が不正です" }
        val id = nextStreamId.getAndIncrement()
        val stream = PairShareTcpStream(this, id)
        tcpStreams[id] = stream
        try {
            send(PairShareFrameType.OPEN_TCP, id, PairSharePayload.tcpTarget(host, port))
            if (!stream.awaitOpen()) {
                throw IOException(stream.errorMessage() ?: "TCP 共有接続を開始できません")
            }
            return stream
        } catch (error: Exception) {
            tcpStreams.remove(id)
            stream.close()
            throw error
        }
    }

    fun openUdp(): PairShareUdpStream {
        val id = nextStreamId.getAndIncrement()
        val stream = PairShareUdpStream(this, id)
        udpStreams[id] = stream
        try {
            send(PairShareFrameType.OPEN_UDP, id)
            if (!stream.awaitOpen()) {
                throw IOException(stream.errorMessage() ?: "UDP 共有接続を開始できません")
            }
            return stream
        } catch (error: Exception) {
            udpStreams.remove(id)
            stream.close()
            throw error
        }
    }

    internal fun sendTcpData(id: Int, payload: ByteArray) {
        send(PairShareFrameType.TCP_DATA, id, payload)
    }

    internal fun sendUdpData(id: Int, host: String, port: Int, payload: ByteArray) {
        send(PairShareFrameType.UDP_DATA, id, PairSharePayload.udpDatagram(host, port, payload))
    }

    internal fun closeTcp(id: Int) {
        tcpStreams.remove(id)
        if (isAlive()) runCatching { send(PairShareFrameType.CLOSE_TCP, id) }
    }

    internal fun closeUdp(id: Int) {
        udpStreams.remove(id)
        if (isAlive()) runCatching { send(PairShareFrameType.CLOSE_UDP, id) }
    }

    private fun connect() {
        val endpointHost = peer.address ?: throw IOException("ペア端末が同じ Wi-Fi 上で見つかりません")
        val endpointPort = peer.port.takeIf { it in 1..65535 }
            ?: throw IOException("ペア端末の接続先が不正です")
        val wifi = PairShareNetwork.activeWifi(appContext)
            ?: throw IOException("Pair & Share には同じ Wi-Fi 接続が必要です")
        val rawSocket = wifi.socketFactory.createSocket()
        rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        rawSocket.connect(InetSocketAddress(endpointHost, endpointPort), CONNECT_TIMEOUT_MILLIS)
        socket = rawSocket

        try {
            val input = DataInputStream(rawSocket.getInputStream())
            val output = DataOutputStream(rawSocket.getOutputStream())
            val repository = PairShareRepository(appContext)
            val localId = repository.deviceId()
            val pairKey = PairShareCrypto.fromBase64(peer.sharedKey)
            val clientNonce = PairShareCrypto.randomBytes(PairShareWire.NONCE_BYTES)

            PairShareWire.writeHeader(output, PairShareWire.HELLO_SESSION)
            PairShareWire.writeString(output, localId)
            PairShareWire.writeString(output, peer.id)
            PairShareWire.writeBytes(output, clientNonce, PairShareWire.NONCE_BYTES)
            output.write(
                PairShareCrypto.handshakeProof(
                    pairKey,
                    "session-request",
                    localId.toByteArray(StandardCharsets.UTF_8),
                    peer.id.toByteArray(StandardCharsets.UTF_8),
                    clientNonce,
                ),
            )
            output.flush()

            when (input.readUnsignedByte()) {
                PairShareWire.SESSION_ACCEPTED -> Unit
                PairShareWire.SESSION_REJECTED -> throw IOException(PairShareWire.readString(input))
                else -> throw IOException("ペア端末から不正な応答を受け取りました")
            }
            val serverNonce = PairShareWire.readBytes(input, PairShareWire.NONCE_BYTES)
            if (serverNonce.size != PairShareWire.NONCE_BYTES) throw IOException("不正なセッション nonce です")
            val responseProof = PairShareWire.readExact(input, PairShareWire.PROOF_BYTES)
            val expectedProof = PairShareCrypto.handshakeProof(
                pairKey,
                "session-accepted",
                localId.toByteArray(StandardCharsets.UTF_8),
                peer.id.toByteArray(StandardCharsets.UTF_8),
                clientNonce,
                serverNonce,
            )
            if (!PairShareCrypto.constantTimeEquals(responseProof, expectedProof)) {
                throw IOException("ペア端末の認証に失敗しました")
            }

            val sessionKey = PairShareCrypto.deriveSessionKey(pairKey, clientNonce, serverNonce)
            codec = PairShareFrameCodec(
                input = input,
                output = output,
                key = sessionKey,
                inboundLabel = "host-to-client",
                outboundLabel = "client-to-host",
            )
            rawSocket.soTimeout = 0
            Thread(::readLoop, "pair-share-client-${peer.id.take(8)}").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            closed.set(true)
            throw error
        }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val frame = codec.read()
                when (frame.type) {
                    PairShareFrameType.OPEN_TCP_OK -> tcpStreams[frame.streamId]?.markOpen(true)
                    PairShareFrameType.OPEN_TCP_FAIL -> tcpStreams[frame.streamId]?.markOpen(
                        false,
                        PairSharePayload.parseMessage(frame.payload),
                    )
                    PairShareFrameType.TCP_DATA -> tcpStreams[frame.streamId]?.onData(frame.payload)
                    PairShareFrameType.CLOSE_TCP -> tcpStreams.remove(frame.streamId)?.closeFromPeer()
                    PairShareFrameType.OPEN_UDP_OK -> udpStreams[frame.streamId]?.markOpen(true)
                    PairShareFrameType.OPEN_UDP_FAIL -> udpStreams[frame.streamId]?.markOpen(
                        false,
                        PairSharePayload.parseMessage(frame.payload),
                    )
                    PairShareFrameType.UDP_DATA -> {
                        val datagram = PairSharePayload.parseUdpDatagram(frame.payload)
                        udpStreams[frame.streamId]?.onDatagram(
                            datagram.host,
                            datagram.port,
                            datagram.payload,
                        )
                    }
                    PairShareFrameType.CLOSE_UDP -> udpStreams.remove(frame.streamId)?.closeFromPeer()
                    PairShareFrameType.PING -> send(PairShareFrameType.PONG, frame.streamId)
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) Log.i(TAG, "Pair & Share client session closed: ${error.message}")
        } finally {
            close()
        }
    }

    private fun send(type: Int, streamId: Int, payload: ByteArray = ByteArray(0)) {
        if (!isAlive()) throw IOException("ペア端末との接続が切れています")
        codec.send(type, streamId, payload)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        tcpStreams.values.forEach(PairShareTcpStream::closeFromPeer)
        udpStreams.values.forEach(PairShareUdpStream::closeFromPeer)
        tcpStreams.clear()
        udpStreams.clear()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 12_000
        private const val HANDSHAKE_TIMEOUT_MILLIS = 20_000
    }
}

/** Runs on the sharing device after PairShareService authenticates the incoming peer. */
internal class PairShareHostSession(
    private val socket: Socket,
    private val codec: PairShareFrameCodec,
    private val sharingAllowed: () -> Boolean,
    speedLimitMbps: () -> Int,
    private val onClosed: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val tcpRelays = ConcurrentHashMap<Int, PairShareHostTcpRelay>()
    private val udpRelays = ConcurrentHashMap<Int, PairShareHostUdpRelay>()
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val rateLimiter = PairShareRateLimiter(speedLimitMbps)

    fun run() {
        try {
            while (!closed.get()) {
                val frame = codec.read()
                when (frame.type) {
                    PairShareFrameType.OPEN_TCP -> openTcp(frame)
                    PairShareFrameType.TCP_DATA -> tcpRelays[frame.streamId]?.write(frame.payload)
                    PairShareFrameType.CLOSE_TCP -> tcpRelays.remove(frame.streamId)?.close()
                    PairShareFrameType.OPEN_UDP -> openUdp(frame)
                    PairShareFrameType.UDP_DATA -> {
                        val datagram = PairSharePayload.parseUdpDatagram(frame.payload)
                        udpRelays[frame.streamId]?.send(datagram.host, datagram.port, datagram.payload)
                    }
                    PairShareFrameType.CLOSE_UDP -> udpRelays.remove(frame.streamId)?.close()
                    PairShareFrameType.PING -> codec.send(PairShareFrameType.PONG, frame.streamId)
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) Log.i(TAG, "Pair & Share host session closed: ${error.message}")
        } finally {
            close()
        }
    }

    private fun openTcp(frame: PairShareFrame) {
        if (!sharingAllowed()) {
            codec.send(
                PairShareFrameType.OPEN_TCP_FAIL,
                frame.streamId,
                PairSharePayload.message("この端末は現在、共有を許可していません"),
            )
            return
        }
        val target = runCatching { PairSharePayload.parseTcpTarget(frame.payload) }
            .getOrElse { error ->
                codec.send(
                    PairShareFrameType.OPEN_TCP_FAIL,
                    frame.streamId,
                    PairSharePayload.message(error.message ?: "接続先が不正です"),
                )
                return
            }
        val relay = PairShareHostTcpRelay(
            streamId = frame.streamId,
            codec = codec,
            rateLimiter = rateLimiter,
            executor = ioExecutor,
            onClosed = { id -> tcpRelays.remove(id) },
        )
        val started = runCatching { relay.start(target.host, target.port) }
        if (started.isFailure) {
            relay.close()
            codec.send(
                PairShareFrameType.OPEN_TCP_FAIL,
                frame.streamId,
                PairSharePayload.message(started.exceptionOrNull()?.message ?: "接続できません"),
            )
            return
        }
        tcpRelays[frame.streamId] = relay
        codec.send(PairShareFrameType.OPEN_TCP_OK, frame.streamId)
    }

    private fun openUdp(frame: PairShareFrame) {
        if (!sharingAllowed()) {
            codec.send(
                PairShareFrameType.OPEN_UDP_FAIL,
                frame.streamId,
                PairSharePayload.message("この端末は現在、共有を許可していません"),
            )
            return
        }
        val relay = runCatching {
            PairShareHostUdpRelay(
                streamId = frame.streamId,
                codec = codec,
                rateLimiter = rateLimiter,
                executor = ioExecutor,
                onClosed = { id -> udpRelays.remove(id) },
            ).also(PairShareHostUdpRelay::start)
        }
        if (relay.isFailure) {
            codec.send(
                PairShareFrameType.OPEN_UDP_FAIL,
                frame.streamId,
                PairSharePayload.message(relay.exceptionOrNull()?.message ?: "UDP を開始できません"),
            )
            return
        }
        udpRelays[frame.streamId] = relay.getOrThrow()
        codec.send(PairShareFrameType.OPEN_UDP_OK, frame.streamId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tcpRelays.values.forEach(PairShareHostTcpRelay::close)
        udpRelays.values.forEach(PairShareHostUdpRelay::close)
        tcpRelays.clear()
        udpRelays.clear()
        ioExecutor.shutdownNow()
        runCatching { socket.close() }
        onClosed()
    }
}

private class PairShareHostTcpRelay(
    private val streamId: Int,
    private val codec: PairShareFrameCodec,
    private val rateLimiter: PairShareRateLimiter,
    private val executor: ExecutorService,
    private val onClosed: (Int) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private lateinit var socket: Socket
    private lateinit var output: OutputStream

    fun start(host: String, port: Int) {
        val address = resolvePublicAddress(host)
        val rawSocket = Socket()
        try {
            rawSocket.tcpNoDelay = true
            rawSocket.connect(InetSocketAddress(address, port), REMOTE_CONNECT_TIMEOUT_MILLIS)
            socket = rawSocket
            output = rawSocket.getOutputStream()
            executor.execute(::readLoop)
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    fun write(data: ByteArray) {
        if (closed.get()) return
        rateLimiter.acquire(data.size)
        synchronized(this) {
            if (closed.get()) return
            output.write(data)
            output.flush()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = socket.getInputStream()
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    rateLimiter.acquire(read)
                    codec.send(PairShareFrameType.TCP_DATA, streamId, buffer.copyOf(read))
                }
            }
        } catch (_: IOException) {
            // A remote close is normal for a proxied TCP stream.
        } finally {
            if (!closed.get()) runCatching { codec.send(PairShareFrameType.CLOSE_TCP, streamId) }
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        onClosed(streamId)
    }

    companion object {
        private const val REMOTE_CONNECT_TIMEOUT_MILLIS = 15_000
    }
}

private class PairShareHostUdpRelay(
    private val streamId: Int,
    private val codec: PairShareFrameCodec,
    private val rateLimiter: PairShareRateLimiter,
    private val executor: ExecutorService,
    private val onClosed: (Int) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val socket = DatagramSocket().apply { soTimeout = UDP_RECEIVE_TIMEOUT_MILLIS }

    fun start() {
        executor.execute(::readLoop)
    }

    fun send(host: String, port: Int, data: ByteArray) {
        if (closed.get()) return
        val address = resolvePublicAddress(host)
        rateLimiter.acquire(data.size)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    private fun readLoop() {
        val buffer = ByteArray(65_535)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (!PairShareNetwork.isPublicInternetAddress(packet.address)) continue
                val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                rateLimiter.acquire(payload.size)
                codec.send(
                    PairShareFrameType.UDP_DATA,
                    streamId,
                    PairSharePayload.udpDatagram(packet.address.hostAddress, packet.port, payload),
                )
            }
        } catch (_: IOException) {
            // Closing the association interrupts receive().
        } finally {
            if (!closed.get()) runCatching { codec.send(PairShareFrameType.CLOSE_UDP, streamId) }
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket.close()
        onClosed(streamId)
    }

    companion object {
        private const val UDP_RECEIVE_TIMEOUT_MILLIS = 500
    }
}

/** Per-device token bucket; zero Mbps deliberately means no artificial cap. */
private class PairShareRateLimiter(
    private val limitMbps: () -> Int,
) {
    private var observedLimit = -1
    private var availableBytes = 0.0
    private var lastRefillNanos = System.nanoTime()

    fun acquire(bytes: Int) {
        while (true) {
            val sleepMillis = synchronized(this) {
                val limit = limitMbps().coerceIn(0, PairShareRepository.MAX_SPEED_LIMIT_MBPS)
                if (limit == 0) return
                val rateBytesPerSecond = limit * GlorytunConstants.MBPS_TO_BYTES_PER_SEC
                val now = System.nanoTime()
                if (limit != observedLimit) {
                    observedLimit = limit
                    availableBytes = rateBytesPerSecond.toDouble()
                    lastRefillNanos = now
                }
                val elapsedSeconds = (now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0
                availableBytes = (availableBytes + elapsedSeconds * rateBytesPerSecond)
                    .coerceAtMost(rateBytesPerSecond.toDouble())
                lastRefillNanos = now
                if (availableBytes >= bytes) {
                    availableBytes -= bytes
                    return
                }
                val missing = bytes - availableBytes
                ((missing / rateBytesPerSecond) * 1000.0).toLong().coerceIn(1L, 250L)
            }
            Thread.sleep(sleepMillis)
        }
    }
}

private fun resolvePublicAddress(host: String): InetAddress {
    if (host.length > 253) throw IOException("接続先ホスト名が長すぎます")
    return InetAddress.getAllByName(host)
        .firstOrNull(PairShareNetwork::isPublicInternetAddress)
        ?: throw IOException("プライベートアドレスには共有接続からアクセスできません")
}

/** Bridges one local SOCKS5 TCP stream into an authenticated Pair & Share stream. */
class PairShareTcpProxyConnection(
    private val stream: PairShareTcpStream,
    private val clientOutput: OutputStream,
    private val onClosed: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun relayFrom(clientInput: InputStream) {
        val downlinkThread = Thread({
            try {
                val buffer = ByteArray(16 * 1024)
                val remoteInput = stream.input()
                while (!closed.get()) {
                    val read = remoteInput.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        synchronized(clientOutput) {
                            clientOutput.write(buffer, 0, read)
                            clientOutput.flush()
                        }
                    }
                }
            } catch (_: IOException) {
                // Local SOCKS client can disappear independently of the paired stream.
            } finally {
                close()
            }
        }, "pair-share-proxy-downlink-${stream.id}").apply {
            isDaemon = true
            start()
        }

        try {
            val buffer = ByteArray(16 * 1024)
            while (!closed.get()) {
                val read = clientInput.read(buffer)
                if (read < 0) break
                if (read > 0) stream.write(buffer, 0, read)
            }
        } finally {
            close()
            downlinkThread.interrupt()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stream.close()
        onClosed()
    }
}

/** SOCKS5 UDP ASSOCIATE support for the Pair & Share receive path. */
class PairShareUdpAssociation private constructor(
    private val context: Context,
    private val socket: DatagramSocket,
    private val allowedAddress: InetAddress,
    private val onClosed: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private lateinit var stream: PairShareUdpStream

    @Volatile
    private var clientEndpoint: InetSocketAddress? = null

    val localUdpPort: Int
        get() = socket.localPort

    fun start() {
        stream = PairShareCoordinator.openUdp(context)
        stream.onDatagram = datagram@{ host, port, payload ->
            val destination = clientEndpoint ?: return@datagram
            val encoded = PairShareSocksUdp.encode(host, port, payload)
            runCatching {
                socket.send(DatagramPacket(encoded, encoded.size, destination.address, destination.port))
            }
        }
        Thread(::readLoop, "pair-share-proxy-udp-${socket.localPort}").apply {
            isDaemon = true
            start()
        }
    }

    fun waitForControlClose(controlInput: InputStream) {
        try {
            while (controlInput.read() >= 0) {
                // SOCKS5 UDP uses this TCP control channel only as a lifetime signal.
            }
        } catch (_: IOException) {
            // Treat control transport loss as association close.
        } finally {
            close()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(65_535)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (packet.address != allowedAddress) continue
                val candidate = InetSocketAddress(packet.address, packet.port)
                val existing = clientEndpoint
                if (existing == null) clientEndpoint = candidate
                else if (existing != candidate) continue

                val datagram = PairShareSocksUdp.parse(packet.data, packet.offset, packet.length) ?: continue
                stream.send(datagram.host, datagram.port, datagram.payload)
            }
        } catch (_: IOException) {
            // Closing the socket is the normal stop path.
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        if (::stream.isInitialized) stream.close()
        onClosed()
    }

    companion object {
        fun bind(
            context: Context,
            allowedAddress: InetAddress,
            onClosed: () -> Unit,
        ): PairShareUdpAssociation {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 500
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            }
            return PairShareUdpAssociation(context, socket, allowedAddress, onClosed)
        }
    }
}

private object PairShareSocksUdp {
    data class Datagram(val host: String, val port: Int, val payload: ByteArray)

    fun parse(buffer: ByteArray, offset: Int, length: Int): Datagram? {
        if (length < 7) return null
        var index = offset
        val end = offset + length
        if (u8(buffer[index++]) != 0 || u8(buffer[index++]) != 0) return null
        if (u8(buffer[index++]) != 0) return null // Fragmentation is not supported by SOCKS5 UDP.
        val addressType = u8(buffer[index++])
        val host = when (addressType) {
            SOCKS5_IPV4 -> {
                if (index + 4 > end) return null
                InetAddress.getByAddress(buffer.copyOfRange(index, index + 4)).hostAddress.also { index += 4 }
            }
            SOCKS5_DOMAIN -> {
                if (index >= end) return null
                val nameLength = u8(buffer[index++])
                if (nameLength == 0 || index + nameLength > end) return null
                String(buffer, index, nameLength, StandardCharsets.UTF_8).also { index += nameLength }
            }
            SOCKS5_IPV6 -> {
                if (index + 16 > end) return null
                InetAddress.getByAddress(buffer.copyOfRange(index, index + 16)).hostAddress.also { index += 16 }
            }
            else -> return null
        }
        if (index + 2 > end) return null
        val port = (u8(buffer[index++]) shl 8) or u8(buffer[index++])
        if (port !in 1..65535) return null
        return Datagram(host, port, buffer.copyOfRange(index, end))
    }

    fun encode(host: String, port: Int, payload: ByteArray): ByteArray {
        val address = InetAddress.getByName(host)
        val type = if (address.address.size == 4) SOCKS5_IPV4 else SOCKS5_IPV6
        val bytes = ByteArrayOutputStream(payload.size + address.address.size + 6)
        bytes.write(byteArrayOf(0, 0, 0, type.toByte()))
        bytes.write(address.address)
        bytes.write((port ushr 8) and 0xff)
        bytes.write(port and 0xff)
        bytes.write(payload)
        return bytes.toByteArray()
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff
    private const val SOCKS5_IPV4 = 0x01
    private const val SOCKS5_DOMAIN = 0x03
    private const val SOCKS5_IPV6 = 0x04
}
