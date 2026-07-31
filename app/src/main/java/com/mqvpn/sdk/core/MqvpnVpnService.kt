// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.core

import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.mqvpn.sdk.core.internal.PathManager
import com.mqvpn.sdk.core.internal.TunnelCallbacks
import com.mqvpn.sdk.core.internal.UdpReaderPool
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnClosePolicy
import com.mqvpn.sdk.core.model.MqvpnError
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.ReconnectInfo
import com.mqvpn.sdk.core.model.TunnelInfo
import com.mqvpn.sdk.core.model.VpnStats
import com.mqvpn.sdk.network.NetworkMonitor
import com.mqvpn.sdk.runtime.MqvpnPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Abstract VPN service base class for mqvpn Android SDK.
 *
 * Subclasses implement [onCreateTun] and [onVpnStateChanged].
 * The SDK manages all internal components (executor, tunnel, I/O, paths).
 *
 * Thread safety: All JNI calls are serialized on the executor thread.
 * [onCreateTun] is called from the executor thread (NOT the UI thread).
 */
abstract class MqvpnVpnService : VpnService(), TunnelCallbacks {

    /** Binder for local (in-process) binding from MqvpnManager. */
    inner class LocalBinder : Binder() {
        fun getService(): MqvpnVpnService = this@MqvpnVpnService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    internal var manager: MqvpnManager? = null

    private lateinit var executor: MqvpnPoller
    private var tunnel: MqvpnTunnel? = null
    private var tunnelBridge: TunnelBridge? = null
    private var udpReaderPool: UdpReaderPool? = null
    private var pathManager: PathManager? = null
    private var networkMonitor: NetworkMonitor? = null
    private var customPacketSender: QueuedTunPacketSender? = null
    private var currentConfig: MqvpnConfig? = null
    private var currentTunPfd: ParcelFileDescriptor? = null
    @Volatile private var nativeClientLeaseHeld = false
    @Volatile private var tunnelRequested = false
    @Volatile private var serviceClosing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val telemetryPollGate = TelemetryPollGate(TELEMETRY_POLL_INTERVAL_MS)

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Using official mqvpn native engine ${MqvpnSdk.getVersion()}")
        executor = MqvpnPoller(scope,
            tickFn = {
                val t = tunnel
                val result = t?.tick() ?: 0
                // JNI telemetry allocates arrays; sample it independently of the hot engine tick.
                if (t != null && telemetryPollGate.shouldPoll(SystemClock.elapsedRealtime())) {
                    val stats = t.getStats()
                    val paths = t.getPaths()
                    val reorderStats = t.getReorderStats()
                    manager?.updateStats(stats)
                    manager?.updatePaths(paths)
                    manager?.updateReorderStats(reorderStats)
                    onStatsUpdated(stats)
                    onPathsUpdated(paths)
                }
                result
            },
            interestFn = {
                val i = tunnel?.getInterest()
                if (i != null) intArrayOf(i.nextTimerMs, if (i.tunReadable) 1 else 0, if (i.isIdle) 1 else 0)
                else intArrayOf(0, 0, 0)
            })
        executor.start()
    }

    override fun onDestroy() {
        serviceClosing = true
        tunnelRequested = false
        runBlocking {
            withTimeoutOrNull(2000) {
                executor.call { cleanup() }
            }
        }
        scope.cancel()
        executor.stop()
        super.onDestroy()
    }

    override fun onRevoke() {
        serviceClosing = true
        tunnelRequested = false
        runBlocking {
            withTimeoutOrNull(2000) {
                executor.call { cleanup() }
            }
        }
        scope.cancel()
        executor.stop()
        stopSelf()
    }

    // --- Public API ---

    /**
     * Start VPN tunnel. Called from app code.
     * connect() is deferred until first network path is available.
     */
    protected fun startTunnel(config: MqvpnConfig) {
        currentConfig = config
        tunnelRequested = true
        telemetryPollGate.reset()
        scope.launch(Dispatchers.IO) {
            val acquired = runInterruptible {
                MqvpnNativeClientLease.processWide.acquire(
                    this@MqvpnVpnService,
                    NATIVE_CLIENT_LEASE_TIMEOUT_MS,
                )
            }
            if (!acquired) {
                if (isActive && tunnelRequested && !serviceClosing) {
                    executor.enqueue {
                        if (!tunnelRequested || serviceClosing) return@enqueue
                        tunnelRequested = false
                        Log.e(TAG, "Timed out waiting for the previous mqvpn client to stop")
                        emitState(
                            MqvpnState.Error(
                                MqvpnError.EngineError(
                                    NATIVE_CLIENT_BUSY_ERROR,
                                    "Previous mqvpn session is still stopping",
                                )
                            )
                        )
                    }
                }
                return@launch
            }
            nativeClientLeaseHeld = true

            if (!isActive || !tunnelRequested || serviceClosing) {
                releaseNativeClientLease()
                return@launch
            }

            executor.enqueue {
                if (!tunnelRequested || serviceClosing) {
                    releaseNativeClientLease()
                    return@enqueue
                }
                initializeTunnel(config)
            }
        }
    }

    private fun initializeTunnel(config: MqvpnConfig) {
        Log.i(
            TAG,
            "Starting official mqvpn config: scheduler=${config.scheduler}, " +
                "hybrid=${config.hybridEnabled}/${config.hybridTcpMode}, " +
                "reorder=${config.reorderEnabled}/${config.reorderProfile}/${config.reorderPorts}",
        )
        val t = try {
            MqvpnTunnel.create(config, this)
        } catch (error: Throwable) {
            tunnelRequested = false
            releaseNativeClientLease()
            Log.e(TAG, "Failed to create mqvpn client", error)
            emitState(
                MqvpnState.Error(
                    MqvpnError.EngineError(
                        NATIVE_CLIENT_CREATE_ERROR,
                        error.message ?: "Failed to create mqvpn client",
                    )
                )
            )
            return
        }
        tunnel = t

        try {
            customPacketSender = QueuedTunPacketSender(
                sendPacket = { frame, length ->
                    executor.call { tunnel?.onTunPacket(frame, 0, length) ?: 0 }
                },
                isWritable = {
                    executor.call { tunnel?.getInterest()?.tunReadable == true }
                },
                waitForNextCheck = { delay(CUSTOM_PACKET_RETRY_MS) },
            ).also { it.start(scope) }

            val pool = UdpReaderPool(executor)
            udpReaderPool = pool

            val monitor = NetworkMonitor(this)
            networkMonitor = monitor

            val pm = PathManager(
                executor, t, pool, monitor,
                protector = { fd -> protect(fd) },
                serverHost = config.serverAddress,
                serverPort = config.serverPort,
            )
            pathManager = pm

            monitor.start { event ->
                scope.launch(Dispatchers.IO) {
                    pm.handleEvent(event)
                    onUnderlyingNetworksChanged(monitor.preferredNetworks())
                }
            }

            emitState(MqvpnState.Connecting)
        } catch (error: Throwable) {
            tunnelRequested = false
            Log.e(TAG, "Failed to initialize mqvpn client", error)
            cleanup()
            emitState(
                MqvpnState.Error(
                    MqvpnError.EngineError(
                        NATIVE_CLIENT_CREATE_ERROR,
                        error.message ?: "Failed to initialize mqvpn client",
                    )
                )
            )
        }
    }

    /**
     * Stop VPN tunnel. Called by [MqvpnManager.disconnect].
     * Do NOT call from onDestroy — cleanup runs automatically.
     */
    internal fun stopTunnel() {
        tunnelRequested = false
        executor.enqueue { cleanup() }
    }

    // --- Internal cleanup (idempotent) ---

    private fun cleanup() {
        tunnelRequested = false
        val activeTunnel = tunnel ?: run {
            releaseNativeClientLease()
            return
        }
        runCatching { networkMonitor?.stop() }
            .onFailure { Log.w(TAG, "Network monitor stop failed", it) }
        runCatching { customPacketSender?.stop() }
            .onFailure { Log.w(TAG, "Packet sender stop failed", it) }
        runCatching { tunnelBridge?.stop() }
            .onFailure { Log.w(TAG, "Tunnel bridge stop failed", it) }
        runCatching { udpReaderPool?.stopAll() }
            .onFailure { Log.w(TAG, "UDP reader stop failed", it) }
        runCatching { activeTunnel.disconnect() }
            .onFailure { Log.w(TAG, "mqvpn disconnect failed", it) }
        runCatching { activeTunnel.tick() }
            .onFailure { Log.w(TAG, "mqvpn final tick failed", it) }
        val nativeClientDestroyed = runCatching { activeTunnel.destroy() }
            .onFailure { Log.e(TAG, "mqvpn native client destroy failed", it) }
            .isSuccess
        runCatching { pathManager?.closeAllFds() }
            .onFailure { Log.w(TAG, "Path fd cleanup failed", it) }
        runCatching { currentTunPfd?.close() }
            .onFailure { Log.w(TAG, "TUN fd close failed", it) }
        tunnel = null
        tunnelBridge = null
        udpReaderPool = null
        pathManager = null
        networkMonitor = null
        customPacketSender = null
        currentTunPfd = null
        if (nativeClientDestroyed) releaseNativeClientLease()
        emitState(MqvpnState.Disconnected)
    }

    private fun releaseNativeClientLease() {
        if (!nativeClientLeaseHeld) return
        nativeClientLeaseHeld = false
        MqvpnNativeClientLease.processWide.release(this)
    }

    // --- TunnelCallbacks implementation ---

    override fun onNativeTunnelConfigReady(
        assignedIp: ByteArray, prefix: Int,
        assignedIp6: ByteArray?, prefix6: Int,
        serverIp: ByteArray, serverPrefix: Int,
        mtu: Int, hasV6: Boolean,
    ) {
        val info = TunnelInfo(
            assignedIp = formatIp4(assignedIp),
            prefix = prefix,
            serverIp = formatIp4(serverIp),
            serverPrefix = serverPrefix,
            mtu = mtu,
            assignedIp6 = assignedIp6?.let { formatIp6(it) },
            prefix6 = prefix6,
            hasV6 = hasV6,
        )

        val tunPfd = try {
            onCreateTun(info, currentConfig!!)
        } catch (e: Exception) {
            Log.e(TAG, "onCreateTun failed", e)
            emitState(MqvpnState.Error(
                MqvpnError.TunCreationFailed(e.message ?: "VPN permission denied")))
            executor.enqueue { tunnel?.disconnect() }
            return
        }

        tunnel?.setTunActive(true, tunPfd.fd)

        // On reconnect: stop old TunnelBridge, close old TUN
        tunnelBridge?.stop()
        currentTunPfd?.close()
        currentTunPfd = tunPfd

        onTunFdReady(tunPfd, mtu)
        if (useDefaultTunnelIo()) {
            tunnelBridge = TunnelBridge(executor, tunnel!!)
            tunnelBridge?.startTunReader(tunPfd, mtu, scope)
            tunnelBridge?.startSender(scope)
        }

        emitState(MqvpnState.Connected(info))
    }

    override fun onNativeTunnelClosed(errorCode: Int) {
        val closeState = MqvpnClosePolicy.stateForClose(
            errorCode = errorCode,
            reconnectEnabled = currentConfig?.reconnect == true,
            reconnectIntervalSec = currentConfig?.reconnectIntervalSec ?: DEFAULT_RECONNECT_INTERVAL_SEC,
        )
        if (closeState != null) emitState(closeState)
    }

    override fun onNativeStateChanged(oldState: Int, newState: Int) {
        // Map native states to MqvpnState
        when (newState) {
            1, 2 -> emitState(MqvpnState.Connecting)
            5 -> {} // RECONNECTING — handled by onNativeReconnectScheduled
            6 -> {} // CLOSED — handled by onNativeTunnelClosed or cleanup
        }
    }

    override fun onNativePathEvent(pathHandle: Long, newStatus: Int) {
        // Poll after tick() returns; calling JNI again from this callback would re-enter native code.
        telemetryPollGate.requestImmediatePoll()
    }

    override fun onNativeLog(level: Int, message: String) {
        onLog(level, message)
    }

    override fun onNativeReconnectScheduled(delaySec: Int) {
        emitState(MqvpnState.Reconnecting(ReconnectInfo(delaySec)))
        onReconnectScheduled(delaySec)
    }

    /**
     * Emit state to both Manager (StateFlow → UI) and app callback.
     */
    private fun emitState(newState: MqvpnState) {
        manager?.updateState(newState)
        onVpnStateChanged(newState)
    }

    // --- Abstract methods (app implements) ---

    /**
     * Create TUN device using VpnService.Builder.
     *
     * Called from the executor thread (NOT the UI thread).
     * Called once per connection, and again on reconnect.
     *
     * @return ParcelFileDescriptor for the TUN device.
     * @throws Exception if TUN creation fails (triggers Error state).
     */
    abstract fun onCreateTun(info: TunnelInfo, config: MqvpnConfig): ParcelFileDescriptor

    /** State change notification. Update UI from here. */
    abstract fun onVpnStateChanged(newState: MqvpnState)

    // --- Optional callbacks ---

    open fun onLog(level: Int, message: String) {}
    open fun onReconnectScheduled(delaySec: Int) {}
    open fun onStatsUpdated(stats: VpnStats) {}
    open fun onPathsUpdated(paths: List<PathInfo>) {}
    protected open fun onUnderlyingNetworksChanged(networks: Array<Network>) {}

    /**
     * Subclasses that do not use an Android TUN fd can provide their own
     * packet I/O and feed packets with [sendTunPacket].
     */
    protected open fun useDefaultTunnelIo(): Boolean = true

    protected open fun onTunFdReady(tunPfd: ParcelFileDescriptor, mtu: Int) {}

    protected fun currentUnderlyingNetworks(): Array<Network> =
        networkMonitor?.preferredNetworks() ?: emptyArray()

    protected fun sendTunPacket(packet: ByteArray, length: Int = packet.size): Boolean =
        customPacketSender?.enqueue(packet, length) == true

    protected fun setPathRateLimits(rateLimits: Map<Long, Long>) {
        pathManager?.updatePathRateLimits(rateLimits)
    }

    // --- Helpers ---

    private fun formatIp4(bytes: ByteArray): String =
        InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"

    private fun formatIp6(bytes: ByteArray): String =
        InetAddress.getByAddress(bytes).hostAddress ?: "::"

    companion object {
        private const val TAG = "MqvpnVpnService"
        private const val DEFAULT_RECONNECT_INTERVAL_SEC = 5
        private const val CUSTOM_PACKET_RETRY_MS = 2L
        private const val NATIVE_CLIENT_LEASE_TIMEOUT_MS = 10_000L
        private const val NATIVE_CLIENT_BUSY_ERROR = -1001
        private const val NATIVE_CLIENT_CREATE_ERROR = -1002
        private const val TELEMETRY_POLL_INTERVAL_MS = 500L
    }
}
