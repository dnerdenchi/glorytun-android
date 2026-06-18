package com.mqvpn.sdk.core

internal class TunPacketFlowController(
    private val sendPacket: suspend (ByteArray, Int) -> Int,
    private val isWritable: suspend () -> Boolean,
    private val waitForNextCheck: suspend () -> Unit,
) {
    private var backpressured = false

    suspend fun sendBatch(
        frames: List<Pair<ByteArray, Int>>,
        releaseFrame: (ByteArray) -> Unit,
    ) {
        var nextFrame = 0
        try {
            while (nextFrame < frames.size) {
                awaitWritable()

                val (frame, length) = frames[nextFrame]
                val result = sendPacket(frame, length)
                releaseFrame(frame)
                nextFrame++

                backpressured = result == MqvpnTunnel.ERR_AGAIN
            }
        } finally {
            while (nextFrame < frames.size) {
                releaseFrame(frames[nextFrame].first)
                nextFrame++
            }
        }
    }

    private suspend fun awaitWritable() {
        while (backpressured) {
            if (isWritable()) {
                backpressured = false
            } else {
                waitForNextCheck()
            }
        }
    }
}
