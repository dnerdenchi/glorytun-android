package com.example.glorytun

internal class TcpReceiveReassembler(
    initialSequence: Long,
    private val maxBufferedBytes: Int
) {
    private val pending = mutableMapOf<Long, ByteArray>()

    var nextSequence: Long = TcpSequence.normalize(initialSequence)
        private set

    private var bufferedBytes = 0

    fun accept(sequence: Long, payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()

        val normalizedSequence = TcpSequence.normalize(sequence)
        val distance = TcpSequence.signedDistance(normalizedSequence, nextSequence)
        if (distance < 0) {
            val alreadyDelivered = (-distance).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (alreadyDelivered >= payload.size) return emptyList()
            return accept(nextSequence, payload.copyOfRange(alreadyDelivered, payload.size))
        }

        if (distance > 0) {
            if (distance + payload.size > maxBufferedBytes) return emptyList()
            val existing = pending[normalizedSequence]
            if (existing == null || existing.size < payload.size) {
                val projectedBytes = bufferedBytes - (existing?.size ?: 0) + payload.size
                if (projectedBytes > maxBufferedBytes) return emptyList()
                pending[normalizedSequence] = payload.copyOf()
                bufferedBytes = projectedBytes
            }
            return emptyList()
        }

        val delivered = mutableListOf(payload)
        nextSequence = TcpSequence.advance(nextSequence, payload.size)
        while (true) {
            val nextPayload = pending.remove(nextSequence) ?: break
            bufferedBytes -= nextPayload.size
            delivered += nextPayload
            nextSequence = TcpSequence.advance(nextSequence, nextPayload.size)
        }
        return delivered
    }

}
