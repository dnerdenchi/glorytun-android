package com.example.glorytun

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.util.LinkedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

data class TcpTunnelKey(
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int
)

class ProxyTcpTunnelConnection(
    val key: TcpTunnelKey,
    private val localAddress: Inet4Address,
    private val remoteAddress: Inet4Address,
    private val clientOutput: OutputStream,
    mtu: Int,
    private val packetSender: (ByteArray) -> Unit,
    private val onClosed: (TcpTunnelKey) -> Unit
) {
    private data class PendingSegment(
        val startSequence: Long,
        val endSequence: Long,
        val packet: ByteArray,
        var sentAtMs: Long,
        var retries: Int = 0
    )

    private val lock = Object()
    private val active = AtomicBoolean(true)
    private val establishedLatch = CountDownLatch(1)
    private val closedLatch = CountDownLatch(1)
    private val ipId = AtomicInteger((System.nanoTime() and 0xffff).toInt())
    private val pendingSegments = LinkedList<PendingSegment>()
    private val retransmitThread = Thread({ retransmitLoop() }, "proxy-tcp-retransmit-${key.localPort}")

    private val maxSegmentSize = min(DEFAULT_MSS, max(256, mtu - IPV4_TCP_HEADER_BYTES))
    private val initialSequence = System.nanoTime() and 0x7fffffffL
    private var sendNext = initialSequence
    private var receiveNext = 0L
    private var receiveReassembler: TcpReceiveReassembler? = null
    private var pendingRemoteFinSequence: Long? = null
    private var established = false
    private var finSent = false
    private var remoteClosed = false

    fun start(timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        retransmitThread.isDaemon = true
        retransmitThread.start()
        synchronized(lock) {
            sendTrackedSegment(TcpFlags.SYN, ByteArray(0))
        }
        return establishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && active.get()
    }

    fun relayFrom(input: InputStream) {
        if (!active.get()) return

        val buffer = ByteArray(maxSegmentSize)
        try {
            while (active.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sendPayload(buffer.copyOf(read))
            }
        } catch (_: IOException) {
            // The local proxy client closed the socket.
        } finally {
            sendFin()
            closedLatch.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
            close()
        }
    }

    fun handlePacket(packet: Ipv4TcpPacket) {
        if (!active.get()) return

        val payloadsToWrite: List<ByteArray>
        var closeAfterWrite = false
        synchronized(lock) {
            if ((packet.flags and TcpFlags.RST) != 0) {
                closeLocked()
                return
            }

            if ((packet.flags and TcpFlags.ACK) != 0) {
                removeAcknowledgedSegments(packet.acknowledgementNumber)
            }

            if (!established && (packet.flags and TcpFlags.SYN) != 0 && (packet.flags and TcpFlags.ACK) != 0) {
                receiveNext = TcpReceiveReassembler.advance(packet.sequenceNumber, 1)
                receiveReassembler = TcpReceiveReassembler(receiveNext, RECEIVE_WINDOW)
                established = true
                sendAckLocked()
                establishedLatch.countDown()
                lock.notifyAll()
                return
            }

            if (!established) return

            payloadsToWrite = if (packet.hasPayload) {
                val reassembler = receiveReassembler ?: return
                val delivered = reassembler.accept(packet.sequenceNumber, packet.payload)
                receiveNext = reassembler.nextSequence
                sendAckLocked()
                delivered
            } else {
                emptyList()
            }

            if ((packet.flags and TcpFlags.FIN) != 0) {
                pendingRemoteFinSequence = TcpReceiveReassembler.advance(
                    packet.sequenceNumber,
                    packet.payload.size
                )
            }
            if (pendingRemoteFinSequence == receiveNext) {
                receiveNext = TcpReceiveReassembler.advance(receiveNext, 1)
                pendingRemoteFinSequence = null
                remoteClosed = true
                sendAckLocked()
                closeAfterWrite = true
            }
        }

        if (payloadsToWrite.isNotEmpty()) {
            try {
                payloadsToWrite.forEach(clientOutput::write)
                clientOutput.flush()
            } catch (_: IOException) {
                close()
            }
        }
        if (closeAfterWrite) {
            close()
        }
    }

    private fun sendPayload(payload: ByteArray) {
        var offset = 0
        while (offset < payload.size && active.get()) {
            val chunkSize = synchronized(lock) {
                waitForSendWindowLocked()
                min(maxSegmentSize, payload.size - offset)
            }
            val chunk = payload.copyOfRange(offset, offset + chunkSize)
            synchronized(lock) {
                sendTrackedSegment(TcpFlags.ACK or TcpFlags.PSH, chunk)
            }
            offset += chunkSize
        }
    }

    private fun sendFin() {
        synchronized(lock) {
            if (!active.get() || finSent || !established) return
            finSent = true
            sendTrackedSegment(TcpFlags.ACK or TcpFlags.FIN, ByteArray(0))
        }
    }

    private fun sendAckLocked() {
        sendSegmentLocked(TcpFlags.ACK, ByteArray(0), track = false)
    }

    private fun sendTrackedSegment(flags: Int, payload: ByteArray) {
        sendSegmentLocked(flags, payload, track = true)
    }

    private fun sendSegmentLocked(flags: Int, payload: ByteArray, track: Boolean) {
        val packet = Ipv4TcpCodec.encode(
            sourceAddress = localAddress,
            destinationAddress = remoteAddress,
            sourcePort = key.localPort,
            destinationPort = key.remotePort,
            sequenceNumber = sendNext,
            acknowledgementNumber = receiveNext,
            flags = flags,
            windowSize = RECEIVE_WINDOW,
            identification = ipId.incrementAndGet(),
            payload = payload
        )
        val sequenceLength = payload.size + if ((flags and (TcpFlags.SYN or TcpFlags.FIN)) != 0) 1 else 0
        if (track && sequenceLength > 0) {
            pendingSegments.add(PendingSegment(sendNext, sendNext + sequenceLength, packet, System.currentTimeMillis()))
        }
        sendNext += sequenceLength
        packetSender(packet)
    }

    private fun removeAcknowledgedSegments(acknowledgement: Long) {
        var changed = false
        while (pendingSegments.isNotEmpty() && pendingSegments.first.endSequence <= acknowledgement) {
            pendingSegments.removeFirst()
            changed = true
        }
        if (changed) lock.notifyAll()
    }

    private fun waitForSendWindowLocked() {
        while (active.get() && pendingBytesLocked() >= SEND_WINDOW_LIMIT) {
            lock.wait(SEND_WINDOW_WAIT_MS)
        }
    }

    private fun pendingBytesLocked(): Long {
        return pendingSegments.sumOf { it.endSequence - it.startSequence }
    }

    private fun retransmitLoop() {
        while (active.get()) {
            try {
                Thread.sleep(RETRANSMIT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }

            val resend = mutableListOf<PendingSegment>()
            synchronized(lock) {
                val now = System.currentTimeMillis()
                for (segment in pendingSegments) {
                    if (now - segment.sentAtMs < RETRANSMIT_AFTER_MS) continue
                    if (segment.retries >= MAX_RETRIES) {
                        Log.w(TAG, "TCP tunnel ${key.localPort} timed out waiting for ACK")
                        closeLocked()
                        return@synchronized
                    }
                    segment.retries++
                    segment.sentAtMs = now
                    resend.add(segment)
                }
            }

            resend.forEach { packetSender(it.packet) }
        }
    }

    fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        if (!active.getAndSet(false)) return
        pendingSegments.clear()
        establishedLatch.countDown()
        closedLatch.countDown()
        lock.notifyAll()
        try {
            clientOutput.close()
        } catch (_: IOException) {
        }
        onClosed(key)
    }

    companion object {
        private const val TAG = "ProxyTcpTunnelConnection"
        private const val IPV4_TCP_HEADER_BYTES = 40
        private const val DEFAULT_MSS = 1200
        private const val RECEIVE_WINDOW = 65535
        private const val SEND_WINDOW_LIMIT = 64 * 1024
        private const val SEND_WINDOW_WAIT_MS = 100L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CLOSE_WAIT_MS = 30_000L
        private const val RETRANSMIT_INTERVAL_MS = 500L
        private const val RETRANSMIT_AFTER_MS = 1_000L
        private const val MAX_RETRIES = 12
    }
}
