// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.core.internal

import android.net.Network
import android.system.Os
import android.util.Log
import com.mqvpn.sdk.core.MqvpnTunnel
import com.mqvpn.sdk.network.NetworkEvent
import com.mqvpn.sdk.network.NetworkMonitor
import com.mqvpn.sdk.network.PathBinder
import com.mqvpn.sdk.runtime.MqvpnExecutor
import java.util.concurrent.ConcurrentHashMap

internal inline fun callNativeRateLimitSafely(call: () -> Int): Int? = try {
    call()
} catch (_: UnsatisfiedLinkError) {
    null
}

/**
 * Bridges [NetworkEvent]s from NetworkMonitor to libmqvpn path management.
 *
 * Execution context:
 * - handleEvent() runs on IO dispatcher (caller provides)
 * - Socket creation (blocking I/O) happens on calling thread
 * - tunnel.addPathFd/connect/removePath are serialized via executor.call
 */
internal class PathManager(
    private val executor: MqvpnExecutor,
    private val tunnel: MqvpnTunnel,
    private val udpReaderPool: UdpReaderPool,
    private val networkMonitor: NetworkMonitor,
    private val protector: (Int) -> Boolean,
    private val serverHost: String,
    private val serverPort: Int,
    private val bindUdp: (Network, String, Int, (Int) -> Boolean) -> Int =
        { network, host, port, prot ->
            PathBinder.bindAndDetachUdp(network, host, port, prot)
        },
) {
    private var connected = false
    private val pathHandles = ConcurrentHashMap<Network, Long>() // network → pathHandle
    private val pathFds = ConcurrentHashMap<Long, Int>() // pathHandle → fd
    private val pathRateLimits = ConcurrentHashMap<Long, Long>()

    /**
     * Handle a network event. Must be called from IO dispatcher.
     *
     * Available: [IO] bindSocket → [executor] addPathFd + connect → startReader
     * Lost: [executor] removePath → stopReader → close(fd)
     */
    suspend fun handleEvent(event: NetworkEvent) {
        when (event) {
            is NetworkEvent.Available -> handleAvailable(event)
            is NetworkEvent.Lost -> handleLost(event)
        }
    }

    private suspend fun handleAvailable(event: NetworkEvent.Available) {
        val network = event.path.network
        val name = event.path.name

        // Step 1: Create socket (blocking I/O, runs on IO thread)
        val fd = bindUdp(network, serverHost, serverPort, protector)
        if (fd < 0) {
            Log.e(TAG, "Failed to bind socket for $name, will retry on next event")
            networkMonitor.removeNetwork(network)
            return
        }

        // Step 2: Add path + connect via executor (thread-safe)
        val handle = executor.call {
            // onLost can fire on a binder thread while bind was running on IO.
            // If the network is no longer in NetworkMonitor's active set, abort
            // — adding a path bound to an already-dead Network would leak a slot
            // (Lost wouldn't fire again for this Network).
            if (!networkMonitor.activeNetworks.containsKey(network)) {
                Log.i(TAG, "Network $name lost during bind, discarding fd")
                return@call ABORT_LOST_DURING_BIND
            }
            val h = tunnel.addPathFd(fd, name)
            if (h < 0) {
                Log.e(TAG, "addPathFd failed for $name: $h")
                return@call h
            }
            pathHandles[network] = h
            pathFds[h] = fd

            if (!connected) {
                tunnel.setServerAddr(serverHost, serverPort)
                tunnel.connect()
                connected = true
            }
            h
        }

        if (handle < 0) {
            closeFdSafe(fd)
            return
        }

        // Step 3: Start UDP reader
        udpReaderPool.startReader(fd, handle, name, tunnel)
        Log.i(TAG, "Path added: $name (handle=$handle, fd=$fd)")
    }

    private suspend fun handleLost(event: NetworkEvent.Lost) {
        val network = event.path.network
        val name = event.path.name

        // Step 1: Remove path via executor (must happen before fd close)
        val (handle, fd) = executor.call {
            val h = pathHandles.remove(network) ?: return@call Pair(-1L, -1)
            val f = pathFds.remove(h) ?: return@call Pair(h, -1)
            tunnel.removePath(h)
            Pair(h, f)
        }

        if (handle < 0) return
        pathRateLimits.remove(handle)

        // Step 2: Stop reader (shutdown, NOT close)
        udpReaderPool.stopReader(handle)

        // Step 3: Close fd (last — after C and reader are done)
        if (fd >= 0) closeFdSafe(fd)
        Log.i(TAG, "Path removed: $name (handle=$handle)")
    }

    /** Close all remaining fds. Called from executor during cleanup. */
    fun closeAllFds() {
        for ((_, fd) in pathFds) {
            closeFdSafe(fd)
        }
        pathFds.clear()
        pathHandles.clear()
        pathRateLimits.clear()
        connected = false
    }

    /**
     * Applies the same cap to both directions of each path.
     * Downlink is shaped before packets enter libmqvpn; uplink is shaped
     * inside libmqvpn before each UDP send.
     */
    fun updatePathRateLimits(rateLimits: Map<Long, Long>) {
        pathRateLimits.keys.retainAll(rateLimits.keys)
        rateLimits.forEach { (handle, rate) ->
            val normalized = rate.coerceAtLeast(0L)
            pathRateLimits[handle] = normalized
            if (pathFds.containsKey(handle)) applyPathRateLimit(handle, normalized)
        }
    }

    private fun applyPathRateLimit(handle: Long, bytesPerSecond: Long) {
        udpReaderPool.updateRateLimit(handle, bytesPerSecond)
        executor.enqueue {
            val result = callNativeRateLimitSafely {
                tunnel.setPathRateLimit(handle, bytesPerSecond)
            }
            if (result == null) {
                Log.e(TAG, "Native engine does not support per-path rate limits; uplink cap skipped")
            } else if (result != 0) {
                Log.w(TAG, "Native rate limit failed for path=$handle: $result")
            }
        }
    }

    private fun closeFdSafe(fd: Int) {
        try {
            val fdObj = java.io.FileDescriptor()
            val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(fdObj, fd)
            Os.close(fdObj)
        } catch (e: Exception) {
            Log.w(TAG, "close fd=$fd failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PathManager"

        /**
         * Sentinel returned from the executor block in [handleAvailable] when
         * the network was lost during the IO bind step. Must not collide with
         * any negative error code returned by [MqvpnTunnel.addPathFd] (xquic /
         * JNI errors are small negatives in the 0..-32 range).
         */
        private const val ABORT_LOST_DURING_BIND = -1000L
    }
}
