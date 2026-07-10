package com.example.glorytun

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Original Pair & Share pairing/session cryptography. No Speedify protocol is reused. */
object PairShareCrypto {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val ECDH_ALGORITHM = "ECDH"
    private const val CURVE = "secp256r1"
    private val random = SecureRandom()

    fun generateEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(CURVE))
        generateKeyPair()
    }

    fun decodePublicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(encoded))

    fun sharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance(ECDH_ALGORITHM).run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    fun derivePairKey(
        sharedSecret: ByteArray,
        code: String,
        hostId: String,
        clientId: String,
    ): ByteArray = hmac(
        sharedSecret,
        "BondVPN Pair & Share pairing v1\u0000".toByteArray(StandardCharsets.UTF_8),
        code.toByteArray(StandardCharsets.UTF_8),
        hostId.toByteArray(StandardCharsets.UTF_8),
        clientId.toByteArray(StandardCharsets.UTF_8),
    )

    fun deriveSessionKey(pairKey: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        hmac(
            pairKey,
            "BondVPN Pair & Share session v1\u0000".toByteArray(StandardCharsets.UTF_8),
            clientNonce,
            serverNonce,
        )

    fun handshakeProof(key: ByteArray, label: String, vararg values: ByteArray): ByteArray = hmac(
        key,
        label.toByteArray(StandardCharsets.UTF_8),
        *values,
    )

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun hmac(key: ByteArray, vararg pieces: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(key, HMAC_ALGORITHM))
            pieces.forEach(::update)
            doFinal()
        }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)

    /** A short visual check shown during manual pairing. */
    fun verificationWords(pairKey: ByteArray): String {
        val digest = hmac(pairKey, "BondVPN Pair & Share words".toByteArray(StandardCharsets.UTF_8))
        val words = arrayOf(
            "あお", "あか", "いし", "うみ", "えだ", "おと", "かぜ", "かわ",
            "きり", "くも", "こえ", "さくら", "しお", "そら", "たき", "つき",
            "てら", "とり", "なみ", "にじ", "はな", "ひかり", "ふね", "ほし",
            "まど", "みち", "むし", "もり", "やま", "ゆき", "よる", "りんご",
        )
        return listOf(0, 1, 2).joinToString("・") { index ->
            words[(digest[index].toInt() and 0xff) % words.size]
        }
    }
}

/** Bounded binary codec used before a pair-specific encrypted session is established. */
object PairShareWire {
    const val MAGIC = 0x42565053 // "BVPS"
    const val VERSION = 1

    const val HELLO_PAIR = 1
    const val HELLO_SESSION = 2

    const val PAIR_PENDING = 10
    const val PAIR_ACCEPTED = 11
    const val PAIR_REJECTED = 12

    const val SESSION_ACCEPTED = 20
    const val SESSION_REJECTED = 21

    const val MAX_STRING_BYTES = 4 * 1024
    const val MAX_PUBLIC_KEY_BYTES = 4 * 1024
    const val NONCE_BYTES = 32
    const val PROOF_BYTES = 32

    fun writeHeader(output: DataOutputStream, helloType: Int) {
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.writeByte(helloType)
    }

    fun readHeader(input: DataInputStream): Int {
        if (input.readInt() != MAGIC) throw IOException("Pair & Share ではない接続です")
        if (input.readUnsignedByte() != VERSION) throw IOException("未対応の Pair & Share バージョンです")
        return input.readUnsignedByte()
    }

    fun writeString(output: DataOutputStream, value: String, maxBytes: Int = MAX_STRING_BYTES) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "文字列が長すぎます" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    fun readString(input: DataInputStream, maxBytes: Int = MAX_STRING_BYTES): String {
        val bytes = readBytes(input, maxBytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun writeBytes(output: DataOutputStream, value: ByteArray, maxBytes: Int = MAX_PUBLIC_KEY_BYTES) {
        require(value.size <= maxBytes) { "データが大きすぎます" }
        output.writeInt(value.size)
        output.write(value)
    }

    fun readBytes(input: DataInputStream, maxBytes: Int): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > maxBytes) throw IOException("不正なデータ長です")
        return ByteArray(size).also(input::readFully)
    }

    fun readExact(input: DataInputStream, size: Int): ByteArray {
        if (size < 0 || size > MAX_PUBLIC_KEY_BYTES) throw IOException("不正なデータ長です")
        return ByteArray(size).also(input::readFully)
    }
}

data class PairShareFrame(
    val type: Int,
    val streamId: Int,
    val payload: ByteArray,
)

/**
 * Length-delimited AES-GCM records. Nonces are direction-specific and derived
 * from a monotonic counter, so they are never transported or reused.
 */
class PairShareFrameCodec(
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

    fun send(type: Int, streamId: Int, payload: ByteArray = ByteArray(0)) {
        require(type in 0..255) { "不正なフレーム種別です" }
        require(streamId >= 0) { "不正なストリーム ID です" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "フレームが大きすぎます" }

        val plain = ByteArrayOutputStream(payload.size + FRAME_HEADER_BYTES)
        DataOutputStream(plain).use { frame ->
            frame.writeByte(type)
            frame.writeInt(streamId)
            frame.writeInt(payload.size)
            frame.write(payload)
        }
        synchronized(writeLock) {
            // Several relay readers can send concurrently. Keep counter allocation,
            // encryption, and output serialization in one critical section so a GCM
            // nonce can never be reused for this session key and direction.
            val encrypted = crypt(
                Cipher.ENCRYPT_MODE,
                plain.toByteArray(),
                outboundPrefix,
                outboundCounter++,
            )
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
    }

    fun read(): PairShareFrame {
        val encryptedSize = try {
            input.readInt()
        } catch (error: EOFException) {
            throw IOException("Pair & Share 接続が閉じられました", error)
        }
        if (encryptedSize < GCM_TAG_BYTES || encryptedSize > MAX_ENCRYPTED_FRAME_BYTES) {
            throw IOException("不正な暗号化フレーム長です")
        }
        val encrypted = ByteArray(encryptedSize)
        input.readFully(encrypted)
        val plain = crypt(
            Cipher.DECRYPT_MODE,
            encrypted,
            inboundPrefix,
            inboundCounter++,
        )
        if (plain.size < FRAME_HEADER_BYTES) throw IOException("短すぎる Pair & Share フレームです")
        val frame = DataInputStream(ByteArrayInputStream(plain))
        val type = frame.readUnsignedByte()
        val streamId = frame.readInt()
        val payloadSize = frame.readInt()
        if (streamId < 0 || payloadSize < 0 || payloadSize > MAX_PAYLOAD_BYTES) {
            throw IOException("不正な Pair & Share フレームです")
        }
        if (frame.available() != payloadSize) throw IOException("Pair & Share フレームの長さが一致しません")
        return PairShareFrame(type, streamId, ByteArray(payloadSize).also(frame::readFully))
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
        private const val FRAME_HEADER_BYTES = 9
        const val MAX_PAYLOAD_BYTES = 70 * 1024
        private const val MAX_ENCRYPTED_FRAME_BYTES = MAX_PAYLOAD_BYTES + FRAME_HEADER_BYTES + GCM_TAG_BYTES
    }
}

/** Frame types for the encrypted, multiplexed peer relay. */
object PairShareFrameType {
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
    const val OPEN_UDP_OK = 11
    const val OPEN_UDP_FAIL = 12
}
