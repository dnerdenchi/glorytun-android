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
    }

    /** Remove a network so the next onCapabilitiesChanged treats it as new. */
    fun removeNetwork(network: Network) {
        _activeNetworks.remove(network)
    }

    fun stop() {
        callback?.let { cm.unregisterNetworkCallback(it) }
        callback = null
        _activeNetworks.clear()
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
