package com.example.glorytun

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val PAIR_BOND_TAG = "PairBondSession"

data class PairBondConfig(
    val serverHost: String,
    val serverPort: Int,
    val authKey: String,
) {
    init {
        require(serverHost.isNotBlank()) { "PairBond サーバーアドレスがありません" }
        require(serverPort in 1..65535) { "PairBond サーバーポートが不正です" }
        require(authKey.isNotBlank()) { "PairBond 認証キーがありません" }
    }

    companion object {
        fun fromIntent(intent: Intent): PairBondConfig = PairBondConfig(
            serverHost = intent.getStringExtra(MqvpnConfigFactory.EXTRA_SERVER_ADDRESS).orEmpty().trim(),
            serverPort = intent.getStringExtra(MqvpnConfigFactory.EXTRA_SERVER_PORT)
                ?.trim()
                ?.toIntOrNull()
                ?: MqvpnConfigFactory.DEFAULT_PORT.toInt(),
            authKey = intent.getStringExtra(MqvpnConfigFactory.EXTRA_AUTH_KEY).orEmpty().trim(),
        )
    }
}

/** Common proxy-facing view of direct and bonded Pair & Share TCP streams. */
interface PairShareTcpTunnel : Closeable {
    val id: Int
    fun awaitOpen(timeoutMillis: Long = 20_000L): Boolean
    fun errorMessage(): String?
    fun input(): InputStream
    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

/** Common proxy-facing view of direct and bonded Pair & Share UDP streams. */
interface PairShareUdpTunnel : Closeable {
    val id: Int
    var onDatagram: ((host: String, port: Int, payload: ByteArray) -> Unit)?
    fun awaitOpen(timeoutMillis: Long = 20_000L): Boolean
    fun errorMessage(): String?
    fun send(host: String, port: Int, payload: ByteArray)
}

/**
 * One logical client session over zero or more paired SIM paths.  Every
 * PairBond path is an encrypted TCP connection from a peer's cellular network
 * to the same relay session.  Byte ranges are scheduled independently and are
 * reassembled at the relay and at this device.
 */
class PairBondSession(
    context: Context,
    private val config: PairBondConfig,
) : Closeable {
    private val appContext = context.applicationContext
    private val repository = PairShareRepository(appContext)
    private val sessionId = PairShareCrypto.randomBytes(PairBondWire.SESSION_ID_BYTES)
    private val closed = AtomicBoolean(false)
    private val nextFlowId = AtomicInteger(1)
    private val paths = ConcurrentHashMap<String, ManagedPath>()
    private val tcpFlows = ConcurrentHashMap<Int, PairBondTcpStream>()
    private val udpFlows = ConcurrentHashMap<Int, PairBondUdpStream>()
    private val scheduler = PairBondPathScheduler()
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val maintenanceExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var lastRepositoryRefresh = 0L

    init {
        refreshPaths()
        maintenanceExecutor.scheduleAtFixedRate(
            { runCatching(::maintain).onFailure { Log.w(PAIR_BOND_TAG, "PairBond maintenance failed", it) } },
            0L,
            MAINTENANCE_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun matches(other: PairBondConfig): Boolean = config == other

    fun isFor(context: Context): Boolean = context.applicationContext === appContext

    @Synchronized
    fun refreshPaths() {
        if (closed.get()) return
        val byId = repository.peers().associateBy { it.id }
        paths.entries.toList().forEach { (id, managed) ->
            val peer = byId[id]
            val priority = peer?.let(repository::pathPriority) ?: PairBondPathPriority.DISABLED
            if (peer == null || priority == PairBondPathPriority.DISABLED) {
                paths.remove(id)?.close()
            } else {
                managed.peer = peer
                managed.priority = priority
            }
        }
        byId.values.forEach { peer ->
            val priority = repository.pathPriority(peer)
            if (priority == PairBondPathPriority.DISABLED) return@forEach
            if (peer.address.isNullOrBlank() || peer.port !in 1..65535) return@forEach
            val managed = paths.getOrPut(peer.id) { ManagedPath(peer, priority) }
            managed.peer = peer
            managed.priority = priority
            if (managed.transport == null && !managed.connecting.get()) connect(managed)
        }
        lastRepositoryRefresh = SystemClock.elapsedRealtime()
        publishStats()
    }

    fun openTcp(host: String, port: Int): PairBondTcpStream {
        require(host.isNotBlank() && port in 1..65535) { "PairBond TCP 宛先が不正です" }
        ensurePathAvailable()
        val stream = PairBondTcpStream(this, nextFlowId.getAndIncrement(), host, port)
        tcpFlows[stream.id] = stream
        sendOpen(stream)
        if (!stream.awaitOpen()) {
            closeTcp(stream.id)
            throw IOException(stream.errorMessage() ?: "PairBond TCP 接続を開始できません")
        }
        return stream
    }

    fun openUdp(): PairBondUdpStream {
        ensurePathAvailable()
        val stream = PairBondUdpStream(this, nextFlowId.getAndIncrement())
        udpFlows[stream.id] = stream
        val selected = selectPaths(redundant = false)
        stream.expectOpenResponses(selected.size)
        selected.forEach { path ->
            send(path, PairBondFrame(PairBondFrameType.OPEN_UDP, stream.id, 0L, ByteArray(0)))
        }
        if (!stream.awaitOpen()) {
            closeUdp(stream.id)
            throw IOException(stream.errorMessage() ?: "PairBond UDP 接続を開始できません")
        }
        return stream
    }

    internal fun sendTcp(stream: PairBondTcpStream, buffer: ByteArray, offset: Int, length: Int) {
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            val size = remaining.coerceAtMost(MAX_TCP_CHUNK_BYTES)
            val chunk = stream.enqueue(buffer.copyOfRange(currentOffset, currentOffset + size))
            sendChunk(stream, chunk, duplicate = chunk.offset == 0L)
            currentOffset += size
            remaining -= size
        }
    }

    internal fun closeTcp(id: Int) {
        val stream = tcpFlows.remove(id) ?: return
        selectPaths(redundant = false).forEach { path ->
            runCatching { send(path, PairBondFrame(PairBondFrameType.CLOSE_TCP, id, 0L, ByteArray(0))) }
        }
        stream.closeFromSession()
    }

    internal fun sendUdp(stream: PairBondUdpStream, host: String, port: Int, payload: ByteArray) {
        val path = selectPaths(redundant = false).firstOrNull()
            ?: throw IOException("利用可能な PairBond パスがありません")
        val sequence = stream.nextSequence.getAndIncrement()
        send(path, PairBondFrame(
            PairBondFrameType.UDP_DATA,
            stream.id,
            sequence,
            PairBondPayload.udpDatagram(host, port, payload),
        ))
    }

    internal fun closeUdp(id: Int) {
        val stream = udpFlows.remove(id) ?: return
        selectPaths(redundant = false).forEach { path ->
            runCatching { send(path, PairBondFrame(PairBondFrameType.CLOSE_UDP, id, 0L, ByteArray(0))) }
        }
        stream.closeFromSession()
    }

    private fun ensurePathAvailable() {
        val deadline = SystemClock.elapsedRealtime() + PATH_READY_TIMEOUT_MILLIS
        while (!closed.get() && readyPaths().isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            refreshPaths()
            Thread.sleep(50L)
        }
        if (closed.get() || readyPaths().isEmpty()) {
            throw IOException("利用可能なペアSIMパスがありません。各端末の共有とモバイル回線を確認してください")
        }
    }

    private fun sendOpen(stream: PairBondTcpStream) {
        val selected = selectPaths(redundant = true)
        if (selected.isEmpty()) {
            stream.markOpenFailure("利用可能な PairBond パスがありません")
            return
        }
        stream.expectOpenResponses(selected.size)
        val payload = PairBondPayload.tcpTarget(stream.host, stream.port)
        selected.forEach { path ->
            send(path, PairBondFrame(PairBondFrameType.OPEN_TCP, stream.id, 0L, payload))
        }
    }

    private fun sendChunk(
        stream: PairBondTcpStream,
        chunk: PairBondTcpStream.PendingChunk,
        duplicate: Boolean,
        excludedPathId: String? = null,
    ) {
        val selected = if (duplicate) selectPaths(redundant = true) else {
            selectPaths(redundant = false, excludedPathId = excludedPathId)
        }
        selected.forEach { path ->
            if (send(path, PairBondFrame(PairBondFrameType.TCP_DATA, stream.id, chunk.offset, chunk.payload))) {
                chunk.recordAttempt(path.peer.id)
            }
        }
    }

    private fun selectPaths(
        redundant: Boolean,
        excludedPathId: String? = null,
    ): List<ManagedPath> {
        val candidates = readyPaths()
        if (candidates.isEmpty()) return emptyList()
        return if (redundant) {
            scheduler.redundant(candidates.map(ManagedPath::snapshot))
                .mapNotNull { snapshot -> paths[snapshot.id] }
        } else {
            scheduler.select(candidates.map(ManagedPath::snapshot), excludedPathId)
                ?.let { snapshot -> paths[snapshot.id] }
                ?.let(::listOf)
                ?: emptyList()
        }
    }

    private fun readyPaths(): List<ManagedPath> = paths.values.filter { it.ready() }

    private fun connect(managed: ManagedPath) {
        if (!managed.connecting.compareAndSet(false, true)) return
        managed.stats.markConnecting()
        managed.lastAttemptMillis = SystemClock.elapsedRealtime()
        ioExecutor.execute {
            try {
                val clientPath = PairBondClientPath(
                    context = appContext,
                    peer = managed.peer,
                    config = config,
                    sessionId = sessionId,
                    initialPriority = managed.priority,
                    stats = managed.stats,
                    onFrame = { path, frame -> onFrame(managed, path, frame) },
                    onClosed = { path, error -> onPathClosed(managed, path, error) },
                )
                clientPath.connect()
                if (closed.get() || managed.priority == PairBondPathPriority.DISABLED) {
                    clientPath.close()
                } else {
                    managed.transport?.close()
                    managed.transport = clientPath
                    managed.lastPongMillis = SystemClock.elapsedRealtime()
                    managed.stats.markReady()
                    sendPathQuality(managed)
                    recoverFlows()
                }
            } catch (error: Throwable) {
                managed.stats.markOffline()
                Log.i(PAIR_BOND_TAG, "PairBond path unavailable: " + managed.peer.displayName + ": " + error.message)
            } finally {
                managed.connecting.set(false)
                publishStats()
            }
        }
    }

    private fun onPathClosed(
        managed: ManagedPath,
        path: PairBondClientPath,
        error: Throwable?,
    ) {
        if (managed.transport === path) {
            managed.transport = null
            managed.stats.markOffline()
            if (!closed.get()) recoverFlows()
            publishStats()
        }
        if (error != null && !closed.get()) {
            Log.i(PAIR_BOND_TAG, "PairBond path closed: " + error.message)
        }
    }

    private fun onFrame(managed: ManagedPath, path: PairBondClientPath, frame: PairBondFrame) {
        when (frame.type) {
            PairBondFrameType.OPEN_TCP_OK -> tcpFlows[frame.flowId]?.markOpen()
            PairBondFrameType.OPEN_TCP_FAIL -> tcpFlows[frame.flowId]?.markOpenFailure(
                PairBondPayload.parseMessage(frame.payload),
            )
            PairBondFrameType.TCP_DATA -> {
                val stream = tcpFlows[frame.flowId] ?: return
                val nextOffset = stream.onData(frame.sequence, frame.payload)
                send(path, PairBondFrame(PairBondFrameType.ACK, frame.flowId, nextOffset, ByteArray(0)))
            }
            PairBondFrameType.ACK -> {
                val stream = tcpFlows[frame.flowId] ?: return
                stream.acknowledge(frame.sequence).forEach { chunk ->
                    paths[chunk.lastPathId]?.stats?.acknowledge(chunk.payload.size, chunk.lastAttemptMillis)
                }
            }
            PairBondFrameType.CLOSE_TCP -> {
                tcpFlows.remove(frame.flowId)?.closeFromPeer()
            }
            PairBondFrameType.OPEN_UDP_OK -> udpFlows[frame.flowId]?.markOpen()
            PairBondFrameType.OPEN_UDP_FAIL -> udpFlows[frame.flowId]?.markOpenFailure(
                PairBondPayload.parseMessage(frame.payload),
            )
            PairBondFrameType.UDP_DATA -> {
                val datagram = PairBondPayload.parseUdpDatagram(frame.payload)
                udpFlows[frame.flowId]?.onDatagram(datagram.host, datagram.port, datagram.payload)
            }
            PairBondFrameType.CLOSE_UDP -> udpFlows.remove(frame.flowId)?.closeFromPeer()
            PairBondFrameType.PING -> send(path, PairBondFrame(PairBondFrameType.PONG, 0, frame.sequence, ByteArray(0)))
            PairBondFrameType.PONG -> {
                val elapsed = SystemClock.elapsedRealtime() - frame.sequence
                if (elapsed in 0..120_000) {
                    managed.lastPongMillis = SystemClock.elapsedRealtime()
                    managed.stats.updateRtt(elapsed.toInt())
                }
            }
        }
    }

    private fun recoverFlows() {
        if (readyPaths().isEmpty()) return
        tcpFlows.values.forEach { stream ->
            if (stream.isOpen()) {
                val existingPathIds = stream.pendingPathIds()
                val backup = selectPaths(redundant = false).firstOrNull()
                if (backup != null && backup.peer.id !in existingPathIds) {
                    runCatching {
                        send(
                            backup,
                            PairBondFrame(
                                PairBondFrameType.OPEN_TCP,
                                stream.id,
                                0L,
                                PairBondPayload.tcpTarget(stream.host, stream.port),
                            ),
                        )
                    }
                }
            } else {
                sendOpen(stream)
            }
        }
        udpFlows.values.forEach { stream ->
            if (!stream.isOpen()) {
                selectPaths(redundant = false).forEach { path ->
                    runCatching { send(path, PairBondFrame(PairBondFrameType.OPEN_UDP, stream.id, 0L, ByteArray(0))) }
                }
            }
        }
    }

    private fun maintain() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRepositoryRefresh >= REPOSITORY_REFRESH_MILLIS) refreshPaths()
        paths.values.forEach { managed ->
            val path = managed.transport
            if (path == null && !managed.connecting.get() && now - managed.lastAttemptMillis >= RECONNECT_INTERVAL_MILLIS) {
                connect(managed)
            } else if (path != null && path.isAlive()) {
                if (now - managed.lastPongMillis > PATH_STALE_MILLIS) {
                    path.close()
                } else {
                    send(path, PairBondFrame(PairBondFrameType.PING, 0, now, ByteArray(0)))
                    sendPathQuality(managed)
                }
            }
        }
        tcpFlows.values.forEach { stream ->
            stream.pendingForRetry(now, retryDelayMillis()).forEach { chunk ->
                stream.markRetransmission()
                paths[chunk.lastPathId]?.stats?.retransmitted()
                sendChunk(stream, chunk, duplicate = false, excludedPathId = chunk.lastPathId)
            }
        }
        publishStats()
    }

    private fun sendPathQuality(managed: ManagedPath) {
        val path = managed.transport ?: return
        val quality = managed.stats.quality(managed.priority)
        send(path, PairBondFrame(
            PairBondFrameType.PATH_QUALITY,
            0,
            0L,
            PairBondPayload.pathQuality(quality),
        ))
    }

    private fun retryDelayMillis(): Long {
        val rtt = paths.values.mapNotNull { it.stats.currentRttMillis() }.minOrNull() ?: DEFAULT_RETRY_DELAY_MILLIS
        return max(DEFAULT_RETRY_DELAY_MILLIS, rtt.toLong() * 3L)
    }

    private fun send(path: ManagedPath, frame: PairBondFrame): Boolean =
        path.transport?.let { send(it, frame) } ?: false

    private fun send(path: PairBondClientPath, frame: PairBondFrame): Boolean {
        return runCatching {
            path.send(frame)
            true
        }.onFailure {
            path.stats.markOffline()
        }.getOrDefault(false)
    }

    private fun publishStats() {
        val snapshot = paths.values.map { managed ->
            managed.stats.snapshot(managed.priority, managed.ready())
        }
        PairShareCoordinator.updatePeerStats(snapshot)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        maintenanceExecutor.shutdownNow()
        paths.values.forEach(ManagedPath::close)
        paths.clear()
        tcpFlows.values.forEach(PairBondTcpStream::closeFromSession)
        tcpFlows.clear()
        udpFlows.values.forEach(PairBondUdpStream::closeFromSession)
        udpFlows.clear()
        ioExecutor.shutdownNow()
    }

    private class ManagedPath(
        initialPeer: PairSharePeer,
        initialPriority: PairBondPathPriority,
    ) : Closeable {
        @Volatile var peer: PairSharePeer = initialPeer
        @Volatile var priority: PairBondPathPriority = initialPriority
        @Volatile var transport: PairBondClientPath? = null
        @Volatile var lastAttemptMillis: Long = 0L
        @Volatile var lastPongMillis: Long = 0L
        val connecting = AtomicBoolean(false)
        val stats = PairBondPathStats(initialPeer.id)

        fun ready(): Boolean = priority != PairBondPathPriority.DISABLED && transport?.isAlive() == true

        fun snapshot(): PairBondPathSnapshot = stats.schedulerSnapshot(peer.id, priority, ready())

        override fun close() {
            transport?.close()
            transport = null
            stats.markOffline()
        }
    }

    companion object {
        private const val MAX_TCP_CHUNK_BYTES = 16 * 1024
        private const val PATH_READY_TIMEOUT_MILLIS = 20_000L
        private const val MAINTENANCE_INTERVAL_MILLIS = 1_000L
        private const val REPOSITORY_REFRESH_MILLIS = 4_000L
        private const val RECONNECT_INTERVAL_MILLIS = 3_000L
        private const val DEFAULT_RETRY_DELAY_MILLIS = 1_200L
        private const val PATH_STALE_MILLIS = 15_000L
    }
}

class PairBondTcpStream internal constructor(
    private val session: PairBondSession,
    override val id: Int,
    val host: String,
    val port: Int,
) : PairShareTcpTunnel {
    internal data class PendingChunk(
        val offset: Long,
        val payload: ByteArray,
        @Volatile var lastPathId: String? = null,
        @Volatile var lastAttemptMillis: Long = 0L,
        @Volatile var attempts: Int = 0,
    ) {
        fun recordAttempt(pathId: String) {
            lastPathId = pathId
            lastAttemptMillis = SystemClock.elapsedRealtime()
            attempts += 1
        }
    }

    private val opened = CountDownLatch(1)
    private val inbound = PipedInputStream(INBOUND_BUFFER_BYTES)
    private val inboundWriter = PipedOutputStream(inbound)
    private val closed = AtomicBoolean(false)
    private val outboundOffset = AtomicLong(0L)
    private val pending = ConcurrentSkipListMap<Long, PendingChunk>()
    private val reassembler = PairBondOrderedReassembler()
    private val openResponses = AtomicInteger(0)

    @Volatile private var expectedOpenResponses = 1
    @Volatile private var openSuccessful = false
    @Volatile private var openError: String? = null
    @Volatile private var retransmissions = 0L

    override fun awaitOpen(timeoutMillis: Long): Boolean {
        if (!opened.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            openError = "PairBond TCP 接続の開始がタイムアウトしました"
            return false
        }
        return openSuccessful
    }

    override fun errorMessage(): String? = openError

    override fun input(): InputStream = inbound

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (closed.get()) throw IOException("PairBond TCP ストリームは閉じられています")
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        session.sendTcp(this, buffer, offset, length)
    }

    internal fun expectOpenResponses(count: Int) {
        expectedOpenResponses = count.coerceAtLeast(1)
    }

    internal fun markOpen() {
        if (closed.get()) return
        openSuccessful = true
        opened.countDown()
    }

    internal fun markOpenFailure(error: String) {
        if (closed.get() || openSuccessful) return
        openError = error
        if (openResponses.incrementAndGet() >= expectedOpenResponses) {
            opened.countDown()
            closeInbound()
        }
    }

    internal fun isOpen(): Boolean = openSuccessful && !closed.get()

    internal fun enqueue(payload: ByteArray): PendingChunk {
        val offset = outboundOffset.getAndAdd(payload.size.toLong())
        return PendingChunk(offset, payload).also { pending[offset] = it }
    }

    internal fun acknowledge(nextOffset: Long): List<PendingChunk> {
        if (nextOffset < 0L) return emptyList()
        val acknowledged = mutableListOf<PendingChunk>()
        pending.entries.toList().forEach { entry ->
            val end = entry.key + entry.value.payload.size
            if (end <= nextOffset) {
                if (pending.remove(entry.key, entry.value)) acknowledged += entry.value
            }
        }
        return acknowledged
    }

    internal fun onData(offset: Long, data: ByteArray): Long {
        if (closed.get()) return reassembler.nextOffset
        reassembler.offer(offset, data).forEach { contiguous ->
            try {
                inboundWriter.write(contiguous)
                inboundWriter.flush()
            } catch (_: IOException) {
                close()
                return reassembler.nextOffset
            }
        }
        return reassembler.nextOffset
    }

    internal fun pendingForRetry(nowMillis: Long, delayMillis: Long): List<PendingChunk> =
        pending.values.filter { chunk ->
            chunk.lastAttemptMillis == 0L || nowMillis - chunk.lastAttemptMillis >= delayMillis
        }

    internal fun pendingPathIds(): Set<String> =
        pending.values.mapNotNull { it.lastPathId }.toSet()

    internal fun markRetransmission() {
        retransmissions += 1L
    }

    internal fun retransmissions(): Long = retransmissions

    internal fun closeFromPeer() {
        if (closed.compareAndSet(false, true)) {
            opened.countDown()
            closeInbound()
        }
    }

    internal fun closeFromSession() {
        if (closed.compareAndSet(false, true)) {
            opened.countDown()
            closeInbound()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            session.closeTcp(id)
            opened.countDown()
            closeInbound()
        }
    }

    private fun closeInbound() {
        runCatching { inboundWriter.close() }
        runCatching { inbound.close() }
    }

    companion object {
        private const val INBOUND_BUFFER_BYTES = 512 * 1024
    }
}

class PairBondUdpStream internal constructor(
    private val session: PairBondSession,
    override val id: Int,
) : PairShareUdpTunnel {
    private val opened = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val openResponses = AtomicInteger(0)

    @Volatile private var expectedOpenResponses = 1
    @Volatile private var openSuccessful = false
    @Volatile private var openError: String? = null
    internal val nextSequence = AtomicLong(1L)

    override var onDatagram: ((host: String, port: Int, payload: ByteArray) -> Unit)? = null

    override fun awaitOpen(timeoutMillis: Long): Boolean {
        if (!opened.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            openError = "PairBond UDP 接続の開始がタイムアウトしました"
            return false
        }
        return openSuccessful
    }

    override fun errorMessage(): String? = openError

    override fun send(host: String, port: Int, payload: ByteArray) {
        if (closed.get()) throw IOException("PairBond UDP ストリームは閉じられています")
        session.sendUdp(this, host, port, payload)
    }

    internal fun expectOpenResponses(count: Int) {
        expectedOpenResponses = count.coerceAtLeast(1)
    }

    internal fun markOpen() {
        if (closed.get()) return
        openSuccessful = true
        opened.countDown()
    }

    internal fun markOpenFailure(error: String) {
        if (closed.get() || openSuccessful) return
        openError = error
        if (openResponses.incrementAndGet() >= expectedOpenResponses) opened.countDown()
    }

    internal fun isOpen(): Boolean = openSuccessful && !closed.get()

    internal fun onDatagram(host: String, port: Int, payload: ByteArray) {
        if (!closed.get()) onDatagram?.invoke(host, port, payload)
    }

    internal fun closeFromPeer() {
        if (closed.compareAndSet(false, true)) opened.countDown()
    }

    internal fun closeFromSession() {
        if (closed.compareAndSet(false, true)) opened.countDown()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            session.closeUdp(id)
            opened.countDown()
        }
    }
}

private class PairBondClientPath(
    private val context: Context,
    private val peer: PairSharePeer,
    private val config: PairBondConfig,
    private val sessionId: ByteArray,
    private val initialPriority: PairBondPathPriority,
    val stats: PairBondPathStats,
    private val onFrame: (PairBondClientPath, PairBondFrame) -> Unit,
    private val onClosed: (PairBondClientPath, Throwable?) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    @Volatile private var pairClient: PairShareClient? = null
    @Volatile private var stream: PairShareTcpStream? = null
    @Volatile private var codec: PairBondFrameCodec? = null

    fun connect() {
        val client = PairShareClient(context, peer)
        try {
            val bondStream = client.openBondPath(config.serverHost, config.serverPort)
            val input = DataInputStream(BufferedInputStream(bondStream.input(), IO_BUFFER_BYTES))
            val rawOutput = object : OutputStream() {
                override fun write(value: Int) {
                    bondStream.write(byteArrayOf(value.toByte()))
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    bondStream.write(bytes, offset, length)
                }
            }
            val output = DataOutputStream(BufferedOutputStream(rawOutput, IO_BUFFER_BYTES))
            val clientNonce = PairShareCrypto.randomBytes(PairBondWire.NONCE_BYTES)
            PairBondWire.writeClientHello(output, sessionId, peer.id, clientNonce, config.authKey)
            val hello = PairBondWire.readServerHello(
                input,
                sessionId,
                peer.id,
                clientNonce,
                config.authKey,
            )
            pairClient = client
            stream = bondStream
            codec = PairBondFrameCodec(
                input = input,
                output = output,
                key = PairBondWire.sessionKey(config.authKey, sessionId, clientNonce, hello.serverNonce),
                inboundLabel = "server-to-client",
                outboundLabel = "client-to-server",
            ).also { recordCodec ->
                recordCodec.send(
                    PairBondFrame(
                        PairBondFrameType.PATH_QUALITY,
                        0,
                        0L,
                        PairBondPayload.pathQuality(stats.quality(initialPriority)),
                    ),
                )
            }
            Thread(::readLoop, "pair-bond-" + peer.id.take(8)).apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            runCatching { client.close() }
            throw error
        }
    }

    fun isAlive(): Boolean = !closed.get() && codec != null

    fun send(frame: PairBondFrame) {
        val current = codec ?: throw IOException("PairBond パスは未接続です")
        if (closed.get()) throw IOException("PairBond パスは閉じられています")
        current.send(frame)
        stats.sent(frame.payload.size.toLong())
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                val frame = codec?.read() ?: break
                stats.received(frame.payload.size.toLong())
                onFrame(this, frame)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            closeInternal(failure)
        }
    }

    override fun close() {
        closeInternal(null)
    }

    private fun closeInternal(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream?.close() }
        runCatching { pairClient?.close() }
        onClosed(this, error)
    }

    companion object {
        private const val IO_BUFFER_BYTES = 96 * 1024
    }
}

private class PairBondPathStats(
    private val peerId: String,
) {
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)
    private val retransmissions = AtomicLong(0L)
    private val deliveryRateBps = AtomicLong(0L)

    @Volatile private var status = "待機中"
    @Volatile private var rttMillis: Int? = null
    @Volatile private var lastSampleNanos = System.nanoTime()
    @Volatile private var lastSampleTx = 0L
    @Volatile private var lastSampleRx = 0L
    @Volatile private var latestTxBps = 0L
    @Volatile private var latestRxBps = 0L

    fun markConnecting() {
        status = "接続中"
    }

    fun markReady() {
        status = "利用可能"
    }

    fun markOffline() {
        status = "オフライン"
    }

    fun sent(bytes: Long) {
        txBytes.addAndGet(bytes.coerceAtLeast(0L))
    }

    fun received(bytes: Long) {
        rxBytes.addAndGet(bytes.coerceAtLeast(0L))
    }

    fun retransmitted() {
        retransmissions.incrementAndGet()
    }

    fun acknowledge(bytes: Int, sentAtMillis: Long) {
        val elapsed = (SystemClock.elapsedRealtime() - sentAtMillis).coerceAtLeast(1L)
        val instant = (bytes.coerceAtLeast(0).toLong() * 1000L) / elapsed
        val previous = deliveryRateBps.get()
        deliveryRateBps.set(if (previous == 0L) instant else (previous * 3L + instant) / 4L)
    }

    fun updateRtt(value: Int) {
        val previous = rttMillis
        rttMillis = if (previous == null) value else (previous * 3 + value) / 4
    }

    fun currentRttMillis(): Int? = rttMillis

    fun quality(priority: PairBondPathPriority): PairBondPathQuality = PairBondPathQuality(
        priority = priority,
        rttMillis = rttMillis ?: 1_000,
        lossPermille = lossPermille(),
        deliveryRateBps = deliveryRateBps.get(),
    )

    fun schedulerSnapshot(id: String, priority: PairBondPathPriority, ready: Boolean): PairBondPathSnapshot =
        PairBondPathSnapshot(
            id = id,
            priority = priority,
            ready = ready,
            rttMillis = rttMillis ?: 1_000,
            lossPermille = lossPermille(),
            deliveryRateBps = deliveryRateBps.get(),
            inFlightBytes = 0L,
        )

    fun snapshot(priority: PairBondPathPriority, ready: Boolean): PairSharePeerStats {
        val now = System.nanoTime()
        val elapsed = (now - lastSampleNanos).coerceAtLeast(1L)
        val currentTx = txBytes.get()
        val currentRx = rxBytes.get()
        latestTxBps = ((currentTx - lastSampleTx).coerceAtLeast(0L) * 1_000_000_000L) / elapsed
        latestRxBps = ((currentRx - lastSampleRx).coerceAtLeast(0L) * 1_000_000_000L) / elapsed
        lastSampleNanos = now
        lastSampleTx = currentTx
        lastSampleRx = currentRx
        val renderedStatus = when {
            priority == PairBondPathPriority.DISABLED -> "使用しない"
            !ready -> status
            priority == PairBondPathPriority.BACKUP_ONLY -> "待機中（バックアップ）"
            lossPermille() >= 50 || (rttMillis ?: 0) > 1_500 -> "劣化中"
            else -> "ボンディング中"
        }
        return PairSharePeerStats(
            peerId = peerId,
            status = renderedStatus,
            txBytes = currentTx,
            rxBytes = currentRx,
            txBytesPerSecond = latestTxBps,
            rxBytesPerSecond = latestRxBps,
            rttMillis = rttMillis,
            lossPermille = lossPermille(),
            retransmissions = retransmissions.get(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun lossPermille(): Int {
        val attemptedChunks = (txBytes.get() / (16 * 1024)).coerceAtLeast(1L)
        return ((retransmissions.get() * 1000L) / attemptedChunks).coerceIn(0L, 1000L).toInt()
    }
}
