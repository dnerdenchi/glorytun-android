package com.mqvpn.sdk.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TunPacketFlowControllerTest {
    @Test
    fun backpressureWaitsBeforeSendingRemainingFrames() = runBlocking {
        val sent = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        var writable = true
        var waits = 0

        val controller = TunPacketFlowController(
            sendPacket = { _, length ->
                sent += length
                if (sent.size == 1) {
                    writable = false
                    MqvpnTunnel.ERR_AGAIN
                } else {
                    0
                }
            },
            isWritable = { writable },
            waitForNextCheck = {
                waits++
                writable = true
            }
        )
        val frames = listOf(
            ByteArray(10) to 10,
            ByteArray(20) to 20,
            ByteArray(30) to 30
        )

        controller.sendBatch(frames) { released += it.size }

        assertEquals(listOf(10, 10, 20, 30), sent)
        assertEquals(listOf(10, 20, 30), released)
        assertEquals(1, waits)
    }

    @Test
    fun backpressureAtBatchEndRetriesBeforeTheNextBatch() = runBlocking {
        val sent = mutableListOf<Int>()
        var writable = true
        var waits = 0

        val controller = TunPacketFlowController(
            sendPacket = { _, length ->
                sent += length
                if (sent.size == 1) {
                    writable = false
                    MqvpnTunnel.ERR_AGAIN
                } else {
                    0
                }
            },
            isWritable = { writable },
            waitForNextCheck = {
                waits++
                writable = true
            }
        )

        controller.sendBatch(listOf(ByteArray(10) to 10)) {}
        controller.sendBatch(listOf(ByteArray(20) to 20)) {}

        assertEquals(listOf(10, 10, 20), sent)
        assertEquals(1, waits)
    }
}
