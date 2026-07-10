package com.example.glorytun

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PairBond is the multipath relay protocol used only between the receiving
 * device and the BondVPN relay.  A paired device can forward these records,
 * but cannot inspect their target, stream data, or authentication key.
 */
data class PairBondFrame(
    val type: Int,
    val flowId: Int,
    val sequence: Long,
    val payload: ByteArray,
)

object PairBondFrameType {
    const val OPEN_TCP = 1
    const val OPEN_TCP_OK = 2
    const val OPEN_TCP_FAIL = 3
    const val TCP_DATA = 4
    const val CLOSE_TCP = 5
    const val OPEN_UDP = 6
    const val UDP_DATA = 7
    const val CLOSE_UDP = 8
    const val PING = 9
    const val PONG = 10
    const val ACK = 11
    const val PATH_QUALITY = 12
    const val OPEN_UDP_OK = 13
    const val OPEN_UDP_FAIL = 14
}

enum class PairBondPathPriority(val wireValue: Int, val displayName: String) {
    DISABLED(0, "使用しない"),
    ACTIVE(1, "自動ボンディング"),
    BACKUP_ONLY(2, "バックアップ専用");

    companion object {
        fun fromStored(value: String?): PairBondPathPriority =
            entries.firstOrNull { it.name == value } ?: DISABLED

        fun fromWire(value: Int): PairBondPathPriority =
            entries.firstOrNull { it.wireValue == value } ?: DISABLED
    }
}

data class PairBondPathQuality(
    val priority: PairBondPathPriority,
    val rttMillis: Int,
    val lossPermille: Int,
    val deliveryRateBps: Long,
)

data class PairBondPathSnapshot(
    val id: String,
    val priority: PairBondPathPriority,
    val ready: Boolean,
    val rttMillis: Int,
    val lossPermille: Int,
    val deliveryRateBps: Long,
    val inFlightBytes: Long,
)

object PairBondPayload {
    data class TcpTarget(val host: String, val port: Int)
    data class UdpDatagram(val host: String, val port: Int, val payload: ByteArray)

    fun tcpTarget(host: String, port: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            PairShareWire.writeString(output, host, MAX_HOST_BYTES)
            output.writeShort(port)
        }
    }.toByteArray()

    fun parseTcpTarget(payload: ByteArray): TcpTarget {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val host = PairShareWire.readString(input, MAX_HOST_BYTES)
        val port = input.readUnsignedShort()
        if (host.isBlank() || port !in 1..65535 || input.available() != 0) {
            throw IOException("不正な PairBond TCP 宛先です")
        }
        return TcpTarget(host, port)
    }

    fun udpDatagram(host: String, port: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                PairShareWire.writeString(output, host, MAX_HOST_BYTES)
                output.writeShort(port)
                PairShareWire.writeBytes(output, payload, PairBondFrameCodec.MAX_PAYLOAD_BYTES - 1024)
            }
        }.toByteArray()

    fun parseUdpDatagram(payload: ByteArray): UdpDatagram {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val host = PairShareWire.readString(input, MAX_HOST_BYTES)
        val port = input.readUnsignedShort()
        val data = PairShareWire.readBytes(input, PairBondFrameCodec.MAX_PAYLOAD_BYTES - 1024)
        if (host.isBlank() || port !in 1..65535 || input.available() != 0) {
            throw IOException("不正な PairBond UDP データです")
        }
        return UdpDatagram(host, port, data)
    }

    fun message(value: String): ByteArray =
        value.take(MAX_ERROR_BYTES).toByteArray(StandardCharsets.UTF_8)

    fun parseMessage(payload: ByteArray): String =
        String(payload.copyOf(MAX_ERROR_BYTES.coerceAtMost(payload.size)), StandardCharsets.UTF_8)

    fun pathQuality(quality: PairBondPathQuality): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeByte(quality.priority.wireValue)
            output.writeInt(quality.rttMillis.coerceIn(0, MAX_RTT_MILLIS))
            output.writeInt(quality.lossPermille.coerceIn(0, 1000))
            output.writeLong(quality.deliveryRateBps.coerceIn(0L, MAX_DELIVERY_RATE_BPS))
        }
    }.toByteArray()

    fun parsePathQuality(payload: ByteArray): PairBondPathQuality {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val priority = PairBondPathPriority.fromWire(input.readUnsignedByte())
        val rtt = input.readInt().coerceIn(0, MAX_RTT_MILLIS)
        val loss = input.readInt().coerceIn(0, 1000)
        val rate = input.readLong().coerceIn(0L, MAX_DELIVERY_RATE_BPS)
        if (input.available() != 0) throw IOException("不正な PairBond パス品質です")
        return PairBondPathQuality(priority, rtt, loss, rate)
    }

    private const val MAX_HOST_BYTES = 253
    private const val MAX_ERROR_BYTES = 512
    private const val MAX_RTT_MILLIS = 120_000
    private const val MAX_DELIVERY_RATE_BPS = 10_000_000_000L
}

/**
 * Initial authenticated handshake.  The auth key is never sent over a paired
 * device; it is only used to authenticate and derive the relay record key.
 */
object PairBondWire {
    const val MAGIC = 0x42565042 // BVPB
    const val VERSION = 1
    const val SESSION_ID_BYTES = 16
    const val NONCE_BYTES = 32
    const val PROOF_BYTES = 32

    private const val ACCEPTED = 1
    private const val REJECTED = 0
    private val HELLO_LABEL = "BondVPN PairBond hello v1\u0000".toByteArray(StandardCharsets.UTF_8)
    private val ACCEPTED_LABEL = "BondVPN PairBond accepted v1\u0000".toByteArray(StandardCharsets.UTF_8)
    private val SESSION_LABEL = "BondVPN PairBond session v1\u0000".toByteArray(StandardCharsets.UTF_8)

    data class ServerHello(
        val serverNonce: ByteArray,
        val proof: ByteArray,
    )

    fun writeClientHello(
        output: DataOutputStream,
        sessionId: ByteArray,
        pathId: String,
        clientNonce: ByteArray,
        authKey: String,
    ) {
        require(sessionId.size == SESSION_ID_BYTES)
        require(clientNonce.size == NONCE_BYTES)
        val key = authKey.trim().toByteArray(StandardCharsets.UTF_8)
        require(key.isNotEmpty())
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.write(sessionId)
        PairShareWire.writeString(output, pathId, 256)
        output.write(clientNonce)
        output.write(PairShareCrypto.hmac(key, HELLO_LABEL, sessionId, pathId.toByteArray(StandardCharsets.UTF_8), clientNonce))
        output.flush()
    }

    fun readServerHello(
        input: DataInputStream,
        sessionId: ByteArray,
        pathId: String,
        clientNonce: ByteArray,
        authKey: String,
    ): ServerHello {
        return when (input.readUnsignedByte()) {
            ACCEPTED -> {
                val serverNonce = PairShareWire.readExact(input, NONCE_BYTES)
                val proof = PairShareWire.readExact(input, PROOF_BYTES)
                val expected = PairShareCrypto.hmac(
                    authKey.trim().toByteArray(StandardCharsets.UTF_8),
                    ACCEPTED_LABEL,
                    sessionId,
                    pathId.toByteArray(StandardCharsets.UTF_8),
                    clientNonce,
                    serverNonce,
                )
                if (!PairShareCrypto.constantTimeEquals(proof, expected)) {
                    throw IOException("PairBond リレーの認証に失敗しました")
                }
                ServerHello(serverNonce, proof)
            }
            REJECTED -> throw IOException(PairShareWire.readString(input, 512))
            else -> throw IOException("PairBond リレーから不正な応答を受け取りました")
        }
    }

    fun sessionKey(
        authKey: String,
        sessionId: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = PairShareCrypto.hmac(
        authKey.trim().toByteArray(StandardCharsets.UTF_8),
        SESSION_LABEL,
        sessionId,
        clientNonce,
        serverNonce,
    )
}

/** AES-GCM record codec with independent counters for each TCP relay path. */
class PairBondFrameCodec(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val key: ByteArray,
    inboundLabel: String,
    outboundLabel: String,
) {
    private val writeLock = Any()
    private val inboundPrefix = PairShareCrypto.hmac(
        key,
        inboundLabel.toByteArray(StandardCharsets.UTF_8),
    ).copyOfRange(0, NONCE_PREFIX_BYTES)
    private val outboundPrefix = PairShareCrypto.hmac(
        key,
        outboundLabel.toByteArray(StandardCharsets.UTF_8),
    ).copyOfRange(0, NONCE_PREFIX_BYTES)

    private var inboundCounter = 0L
    private var outboundCounter = 0L

    fun send(frame: PairBondFrame) {
        require(frame.type in 0..255)
        require(frame.flowId >= 0)
        require(frame.sequence >= 0L)
        require(frame.payload.size <= MAX_PAYLOAD_BYTES)

        val plain = ByteArrayOutputStream(frame.payload.size + FRAME_HEADER_BYTES)
        DataOutputStream(plain).use { data ->
            data.writeByte(frame.type)
            data.writeInt(frame.flowId)
            data.writeLong(frame.sequence)
            data.writeInt(frame.payload.size)
            data.write(frame.payload)
        }
        synchronized(writeLock) {
            val encrypted = crypt(Cipher.ENCRYPT_MODE, plain.toByteArray(), outboundPrefix, outboundCounter++)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
    }

    fun read(): PairBondFrame {
        val encryptedSize = try {
            input.readInt()
        } catch (error: EOFException) {
            throw IOException("PairBond パスが閉じられました", error)
        }
        if (encryptedSize < GCM_TAG_BYTES || encryptedSize > MAX_ENCRYPTED_FRAME_BYTES) {
            throw IOException("不正な PairBond 暗号化フレーム長です")
        }
        val encrypted = ByteArray(encryptedSize)
        input.readFully(encrypted)
        val plain = crypt(Cipher.DECRYPT_MODE, encrypted, inboundPrefix, inboundCounter++)
        if (plain.size < FRAME_HEADER_BYTES) throw IOException("短すぎる PairBond フレームです")
        val data = DataInputStream(ByteArrayInputStream(plain))
        val type = data.readUnsignedByte()
        val flowId = data.readInt()
        val sequence = data.readLong()
        val payloadSize = data.readInt()
        if (flowId < 0 || sequence < 0L || payloadSize !in 0..MAX_PAYLOAD_BYTES) {
            throw IOException("不正な PairBond フレームです")
        }
        if (data.available() != payloadSize) throw IOException("PairBond フレーム長が一致しません")
        return PairBondFrame(type, flowId, sequence, ByteArray(payloadSize).also(data::readFully))
    }

    private fun crypt(mode: Int, data: ByteArray, prefix: ByteArray, counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        prefix.copyInto(nonce, 0)
        ByteBuffer.wrap(nonce, NONCE_PREFIX_BYTES, Long.SIZE_BYTES).putLong(counter)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            doFinal(data)
        }
    }

    companion object {
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val NONCE_PREFIX_BYTES = 4
        private const val FRAME_HEADER_BYTES = 17
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_ENCRYPTED_FRAME_BYTES = MAX_PAYLOAD_BYTES + FRAME_HEADER_BYTES + GCM_TAG_BYTES
    }
}

/**
 * Smooth weighted scheduling. Backup paths are selected only when no active
 * path is healthy; they are still kept connected so failover does not wait for
 * a new pairing handshake.
 */
class PairBondPathScheduler {
    private val currentWeights = mutableMapOf<String, Double>()

    @Synchronized
    fun select(
        candidates: Collection<PairBondPathSnapshot>,
        excludedId: String? = null,
    ): PairBondPathSnapshot? {
        val ready = candidates.filter { it.ready && it.priority != PairBondPathPriority.DISABLED }
        val active = ready.filter { it.priority == PairBondPathPriority.ACTIVE }
        val pool = (if (active.isNotEmpty()) active else ready.filter {
            it.priority == PairBondPathPriority.BACKUP_ONLY
        }).filter { it.id != excludedId }
        if (pool.isEmpty()) return null

        val weights = pool.associateWith(::weight)
        val total = weights.values.sum().coerceAtLeast(1.0)
        val selected = pool.maxByOrNull { candidate ->
            val next = (currentWeights[candidate.id] ?: 0.0) + (weights[candidate] ?: 1.0)
            currentWeights[candidate.id] = next
            next
        } ?: return null
        currentWeights[selected.id] = (currentWeights[selected.id] ?: 0.0) - total
        currentWeights.keys.retainAll(candidates.map { it.id }.toSet())
        return selected
    }

    @Synchronized
    fun redundant(
        candidates: Collection<PairBondPathSnapshot>,
        count: Int = 2,
    ): List<PairBondPathSnapshot> {
        val ready = candidates.filter { it.ready && it.priority == PairBondPathPriority.ACTIVE }
        val pool = if (ready.isNotEmpty()) ready else candidates.filter {
            it.ready && it.priority == PairBondPathPriority.BACKUP_ONLY
        }
        return pool.sortedByDescending(::weight).take(count.coerceAtLeast(1))
    }

    private fun weight(candidate: PairBondPathSnapshot): Double {
        val rate = candidate.deliveryRateBps.coerceAtLeast(256_000L).toDouble()
        val rtt = candidate.rttMillis.coerceAtLeast(20).toDouble()
        val lossFactor = (1.0 - candidate.lossPermille.coerceIn(0, 950) / 1000.0).coerceAtLeast(0.05)
        val congestionFactor = 1.0 / (1.0 + candidate.inFlightBytes.coerceAtLeast(0L) / 65_536.0)
        return (rate / rtt) * lossFactor * congestionFactor
    }
}

/** Reassembles only contiguous byte ranges and discards duplicate chunks. */
class PairBondOrderedReassembler(
    initialOffset: Long = 0L,
    private val maxBufferedBytes: Int = 4 * 1024 * 1024,
) {
    private val pending = TreeMap<Long, ByteArray>()
    private var bufferedBytes = 0

    var nextOffset: Long = initialOffset
        private set

    @Synchronized
    fun offer(offset: Long, payload: ByteArray): List<ByteArray> {
        require(offset >= 0L)
        if (payload.isEmpty()) return emptyList()
        val end = offset + payload.size
        if (end < offset || end <= nextOffset) return emptyList()

        val effectiveOffset: Long
        val effectivePayload: ByteArray
        if (offset < nextOffset) {
            val consumed = (nextOffset - offset).toInt()
            effectiveOffset = nextOffset
            effectivePayload = payload.copyOfRange(consumed, payload.size)
        } else {
            effectiveOffset = offset
            effectivePayload = payload
        }
        if (pending.containsKey(effectiveOffset)) return emptyList()
        if (bufferedBytes + effectivePayload.size > maxBufferedBytes) {
            throw IOException("PairBond の順序待ちバッファが上限を超えました")
        }
        pending[effectiveOffset] = effectivePayload
        bufferedBytes += effectivePayload.size

        val contiguous = mutableListOf<ByteArray>()
        while (true) {
            val next = pending.remove(nextOffset) ?: break
            bufferedBytes -= next.size
            nextOffset += next.size
            contiguous += next
        }
        return contiguous
    }
}
