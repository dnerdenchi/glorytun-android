package com.mqvpn.sdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, ordered sender for packet producers that cannot call suspend APIs.
 * Producers block only when the bounded queue is full, propagating native
 * backpressure instead of silently dropping packets.
 */
internal class QueuedTunPacketSender(
    private val sendPacket: suspend (ByteArray, Int) -> Int,
    private val isWritable: suspend () -> Boolean,
    private val waitForNextCheck: suspend () -> Unit,
    capacity: Int = DEFAULT_CAPACITY,
    private val onQueueSaturated: () -> Unit = {},
) {
    private val accepting = AtomicBoolean(false)
    private val frames = Channel<Pair<ByteArray, Int>>(capacity)
    private val flowController = TunPacketFlowController(
        sendPacket = sendPacket,
        isWritable = isWritable,
        waitForNextCheck = waitForNextCheck,
    )

    private var senderJob: Job? = null

    fun start(scope: CoroutineScope) {
        check(accepting.compareAndSet(false, true)) { "Packet sender already started" }
        senderJob = scope.launch(Dispatchers.Default) {
            val batch = ArrayList<Pair<ByteArray, Int>>(MAX_BATCH_SIZE)
            try {
                while (isActive) {
                    val first = frames.receiveCatching().getOrNull() ?: break
                    batch.add(first)
                    while (batch.size < MAX_BATCH_SIZE) {
                        val next = frames.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }

                    val pending = batch.toList()
                    batch.clear()
                    flowController.sendBatch(pending) {}
                }
            } finally {
                accepting.set(false)
                frames.close()
            }
        }
    }

    fun enqueue(packet: ByteArray, length: Int = packet.size): Boolean {
        require(length in 0..packet.size) { "Invalid packet length: $length" }
        if (!accepting.get()) return false

        val frame = packet.copyOf(length)
        val queued = frames.trySend(frame to frame.size)
        if (queued.isSuccess) return true
        if (queued.isClosed) return false

        onQueueSaturated()
        return runBlocking {
            runCatching {
                frames.send(frame to frame.size)
                true
            }.getOrDefault(false)
        }
    }

    fun stop() {
        accepting.set(false)
        frames.close()
        senderJob?.cancel()
        senderJob = null
    }

    companion object {
        private const val DEFAULT_CAPACITY = 192
        private const val MAX_BATCH_SIZE = 64
    }
}
