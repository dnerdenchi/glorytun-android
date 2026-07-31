// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.getSystemService
import java.util.concurrent.ConcurrentHashMap

internal data class NetworkCapabilityFlags(
    val hasInternet: Boolean,
    val hasValidated: Boolean,
    val hasNotVpn: Boolean,
    val hasWifi: Boolean,
    val hasCellular: Boolean,
    val hasEthernet: Boolean,
    val hasNotMetered: Boolean,
)

/**
 * Monitors WiFi / Cellular / Ethernet availability via ConnectivityManager.
 *
 * Uses NET_CAPABILITY_VALIDATED to filter out captive portals and
 * unvalidated networks that would cause packet loss if used as VPN paths.
 */
class NetworkMonitor(private val context: Context) {

    private val cm = context.getSystemService<ConnectivityManager>()!!

    private val _activeNetworks = ConcurrentHashMap<Network, NetworkPath>()
    val activeNetworks: Map<Network, NetworkPath> get() = _activeNetworks

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var cellularRequestCallback: ConnectivityManager.NetworkCallback? = null

    fun start(listener: (NetworkEvent) -> Unit) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleCapabilities(network, cm.getNetworkCapabilities(network), listener)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                handleCapabilities(network, capabilities, listener)
            }

            override fun onLost(network: Network) {
                val path = _activeNetworks.remove(network) ?: return
                Log.d(TAG, "Lost: $path")
                listener(NetworkEvent.Lost(path))
            }
        }

        callback = cb
        cm.registerNetworkCallback(request, cb)
        cm.allNetworks.forEach { network ->
            handleCapabilities(network, cm.getNetworkCapabilities(network), listener)
        }
        keepCellularNetworkAvailable()
    }

    /** Remove a network so the next onCapabilitiesChanged treats it as new. */
    fun removeNetwork(network: Network) {
        _activeNetworks.remove(network)
    }

    fun stop() {
        callback?.let { cm.unregisterNetworkCallback(it) }
        cellularRequestCallback?.let { requestCallback ->
            runCatching { cm.unregisterNetworkCallback(requestCallback) }
                .onFailure { Log.w(TAG, "Failed to release cellular network request", it) }
        }
        callback = null
        cellularRequestCallback = null
        _activeNetworks.clear()
    }

    /** Networks currently used by mqvpn, ordered from least to most costly. */
    fun preferredNetworks(): Array<Network> = _activeNetworks.values
        .sortedWith(
            compareBy<NetworkPath> { preferenceRank(it.type, it.isMetered) }
                .thenBy { it.name }
        )
        .map { it.network }
        .toTypedArray()

    /**
     * A passive callback does not keep a secondary cellular network up while
     * Wi-Fi is the default. Hold one cellular request for the tunnel lifetime
     * so multipath failover does not wait for the modem to reconnect.
     */
    private fun keepCellularNetworkAvailable() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val requestCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Cellular standby network available: $network")
            }

            override fun onUnavailable() {
                Log.i(TAG, "Cellular standby network unavailable")
            }
        }

        runCatching { cm.requestNetwork(request, requestCallback) }
            .onSuccess { cellularRequestCallback = requestCallback }
            .onFailure { Log.w(TAG, "Unable to keep cellular standby network", it) }
    }

    private fun handleCapabilities(
        network: Network,
        capabilities: NetworkCapabilities?,
        listener: (NetworkEvent) -> Unit,
    ) {
        val flags = capabilities?.toFlags()
        if (flags == null || !isUsablePath(flags)) {
            val removed = _activeNetworks.remove(network)
            if (removed != null) {
                Log.d(TAG, "Lost unusable path: $removed")
                listener(NetworkEvent.Lost(removed))
            }
            return
        }

        val type = classifyTransport(flags)
        val path = NetworkPath(
            network = network,
            type = type,
            name = networkName(network, type),
            isMetered = !flags.hasNotMetered,
        )
        val isNew = _activeNetworks.put(network, path) == null
        if (isNew) {
            Log.d(TAG, "Available: $path")
            listener(NetworkEvent.Available(path))
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        internal fun isUsablePath(flags: NetworkCapabilityFlags): Boolean {
            return flags.hasInternet &&
                flags.hasValidated &&
                flags.hasNotVpn &&
                (flags.hasWifi || flags.hasCellular || flags.hasEthernet)
        }

        internal fun classifyTransport(flags: NetworkCapabilityFlags): PathType = when {
            flags.hasWifi -> PathType.WIFI
            flags.hasCellular -> PathType.CELLULAR
            flags.hasEthernet -> PathType.ETHERNET
            else -> PathType.OTHER
        }

        internal fun networkName(network: Network, type: PathType): String =
            "${type.name.lowercase()}-${network.networkHandle and 0xFFF}"

        internal fun preferenceRank(type: PathType, isMetered: Boolean): Int {
            val meteredRank = if (isMetered) 10 else 0
            val transportRank = when (type) {
                PathType.WIFI -> 0
                PathType.ETHERNET -> 1
                PathType.CELLULAR -> 2
                PathType.OTHER -> 3
            }
            return meteredRank + transportRank
        }

        private fun NetworkCapabilities.toFlags() = NetworkCapabilityFlags(
            hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            hasNotVpn = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            hasWifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasCellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            hasEthernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            hasNotMetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}
