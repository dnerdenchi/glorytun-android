package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Guards the byte-level contract between the Android Kotlin implementation and
 * the Python relay that runs on the BondVPN server.
 */
class PairBondPythonInteropTest {
    @Test
    fun kotlinClientCompletesEncryptedPingWithPythonRelay() {
        assumeTrue(hasPython())
        val relayScript = findRelayScript()
        assumeTrue(relayScript.isFile)
        val config = Files.createTempFile("pairbond-interop", ".conf")
        Files.write(config, "[Auth]\nKey = interop-test-key\n".toByteArray())
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            "python",
            relayScript.absolutePath,
            "--host",
            "127.0.0.1",
            "--port",
            port.toString(),
            "--mqvpn-config",
            config.toAbsolutePath().toString(),
        )
            .redirectErrorStream(true)
            .start()

        var socket: Socket? = null
        try {
            val connectedSocket = waitForServer(port)
            socket = connectedSocket
            val input = DataInputStream(connectedSocket.getInputStream())
            val output = DataOutputStream(connectedSocket.getOutputStream())
            val sessionId = ByteArray(PairBondWire.SESSION_ID_BYTES) { 0x31 }
            val clientNonce = ByteArray(PairBondWire.NONCE_BYTES) { 0x52 }
            val authKey = "interop-test-key"

            PairBondWire.writeClientHello(output, sessionId, "test-sim", clientNonce, authKey)
            val serverHello = PairBondWire.readServerHello(
                input,
                sessionId,
                "test-sim",
                clientNonce,
                authKey,
            )
            val codec = PairBondFrameCodec(
                input = input,
                output = output,
                key = PairBondWire.sessionKey(authKey, sessionId, clientNonce, serverHello.serverNonce),
                inboundLabel = "server-to-client",
                outboundLabel = "client-to-server",
            )
            codec.send(PairBondFrame(PairBondFrameType.PING, 0, 123L, ByteArray(0)))

            val response = codec.read()
            assertEquals(PairBondFrameType.PONG, response.type)
            assertEquals(123L, response.sequence)
        } finally {
            runCatching { socket?.close() }
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            Files.deleteIfExists(config)
        }
    }

    private fun hasPython(): Boolean = runCatching {
        ProcessBuilder("python", "--version").start().waitFor(3, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun findRelayScript(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(6) {
            val candidate = File(current, "server/pairbond-relay/pairbond_relay.py")
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return candidate
        }
        return File("server/pairbond-relay/pairbond_relay.py")
    }

    private fun waitForServer(port: Int): Socket {
        var lastError: Throwable? = null
        repeat(50) {
            try {
                return Socket().apply {
                    connect(InetSocketAddress("127.0.0.1", port), 200)
                    soTimeout = 5_000
                }
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Python PairBond relay did not start", lastError)
    }
}
