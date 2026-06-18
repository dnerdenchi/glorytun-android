package com.example.glorytun

internal class TcpReceiveReassembler(
    initialSequence: Long,
    private val maxBufferedBytes: Int
) {
    private val pending = mutableMapOf<Long, ByteArray>()

    var nextSequence: Long = normalize(initialSequence)
        private set

    private var bufferedBytes = 0

    fun accept(sequence: Long, payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()

        val normalizedSequence = normalize(sequence)
        val distance = signedDistance(normalizedSequence, nextSequence)
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
        nextSequence = advance(nextSequence, payload.size)
        while (true) {
            val nextPayload = pending.remove(nextSequence) ?: break
            bufferedBytes -= nextPayload.size
            delivered += nextPayload
            nextSequence = advance(nextSequence, nextPayload.size)
        }
        return delivered
    }

    companion object {
        private const val SEQUENCE_MASK = 0xffff_ffffL
        private const val SEQUENCE_MODULUS = 0x1_0000_0000L
        private const val HALF_SEQUENCE_SPACE = 0x8000_0000L

        fun advance(sequence: Long, count: Int): Long = normalize(sequence + count)

        private fun normalize(sequence: Long): Long = sequence and SEQUENCE_MASK

        private fun signedDistance(sequence: Long, base: Long): Long {
            val forward = (sequence - base) and SEQUENCE_MASK
            return if (forward < HALF_SEQUENCE_SPACE) forward else forward - SEQUENCE_MODULUS
        }
    }
}
