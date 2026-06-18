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
                if (result == MqvpnTunnel.ERR_AGAIN) {
                    // The native queue did not accept this frame; retain it for retry.
                    backpressured = true
                    continue
                }

                releaseFrame(frame)
                nextFrame++
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
