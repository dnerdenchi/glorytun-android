package com.example.glorytun

/** TCP sequence arithmetic in the unsigned 32-bit sequence space. */
internal object TcpSequence {
    private const val SEQUENCE_MASK = 0xffff_ffffL
    private const val SEQUENCE_MODULUS = 0x1_0000_0000L
    private const val HALF_SEQUENCE_SPACE = 0x8000_0000L

    fun normalize(sequence: Long): Long = sequence and SEQUENCE_MASK

    fun advance(sequence: Long, count: Int): Long = normalize(sequence + count)

    fun signedDistance(sequence: Long, base: Long): Long {
        val forward = (normalize(sequence) - normalize(base)) and SEQUENCE_MASK
        return if (forward < HALF_SEQUENCE_SPACE) forward else forward - SEQUENCE_MODULUS
    }

    fun isAcknowledged(segmentEnd: Long, acknowledgement: Long): Boolean =
        signedDistance(acknowledgement, segmentEnd) >= 0
}
