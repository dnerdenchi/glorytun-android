package com.mqvpn.sdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QueuedTunPacketSenderTest {
    @Test
    fun customPacketsAreRetriedInOrderWhenNativeQueueIsBackpressured() = runBlocking {
        val attempts = mutableListOf<Int>()
        val completed = CompletableDeferred<Unit>()
        var writable = true
        var waits = 0

        val sender = QueuedTunPacketSender(
            sendPacket = { frame, _ ->
                val marker = frame.first().toInt()
                attempts += marker
                if (attempts.size == 1) {
                    writable = false
                    MqvpnTunnel.ERR_AGAIN
                } else {
                    if (attempts == listOf(1, 1, 2)) completed.complete(Unit)
                    0
                }
            },
            isWritable = { writable },
            waitForNextCheck = {
                waits++
                writable = true
            },
        )
        sender.start(this)

        assertTrue(sender.enqueue(byteArrayOf(1)))
        assertTrue(sender.enqueue(byteArrayOf(2)))
        withTimeout(1_000) { completed.await() }
        sender.stop()

        assertEquals(listOf(1, 1, 2), attempts)
        assertEquals(1, waits)
    }

    @Test
    fun blockedProducerIsReleasedWhenSenderFails() {
        val sendStarted = CountDownLatch(1)
        val failSend = CountDownLatch(1)
        val producerBlocked = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producer = Executors.newSingleThreadExecutor()
        val sender = QueuedTunPacketSender(
            sendPacket = { _, _ ->
                sendStarted.countDown()
                failSend.await()
                error("native sender failed")
            },
            isWritable = { true },
            waitForNextCheck = {},
            capacity = 1,
            onQueueSaturated = { producerBlocked.countDown() },
        )

        try {
            sender.start(scope)
            assertTrue(sender.enqueue(byteArrayOf(1)))
            assertTrue(sendStarted.await(1, TimeUnit.SECONDS))
            assertTrue(sender.enqueue(byteArrayOf(2)))

            val blockedEnqueue = producer.submit<Boolean> {
                sender.enqueue(byteArrayOf(3))
            }
            assertTrue(producerBlocked.await(1, TimeUnit.SECONDS))
            failSend.countDown()

            assertFalse(blockedEnqueue.get(1, TimeUnit.SECONDS))
        } finally {
            failSend.countDown()
            sender.stop()
            scope.cancel()
            producer.shutdownNow()
        }
    }
}
