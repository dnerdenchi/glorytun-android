package com.example.glorytun

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Wi-Fi-only transport helpers. Pair & Share is intentionally LAN-scoped. */
object PairShareNetwork {
    fun activeWifi(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                localIpv4(context, network) != null
        }
    }

    /**
     * Returns an underlying cellular network rather than the app's VPN default
     * network. PairBond uses this to make each paired device a distinct SIM
     * egress path.
     */
    fun activeCellular(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
    }

    fun openCellularSocket(context: Context, host: String, port: Int): Socket {
        require(port in 1..65535)
        val network = activeCellular(context)
            ?: throw java.io.IOException("利用可能なモバイル回線がありません")
        val address = InetAddress.getAllByName(host)
            .firstOrNull(::isPublicInternetAddress)
            ?: throw java.io.IOException("PairBond サーバーはパブリックアドレスである必要があります")
        val socket = network.socketFactory.createSocket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), CELLULAR_CONNECT_TIMEOUT_MILLIS)
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    fun localIpv4(context: Context, network: Network): Inet4Address? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    fun wifiNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    /** Prevent a paired device from using this feature as an internal-network pivot. */
    fun isPublicInternetAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        // IPv6 unique-local addresses (fc00::/7) are not covered consistently by isSiteLocalAddress.
        if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
        // IPv4 carrier-grade NAT and documentation/reserved ranges are not valid public egress targets.
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false
            if (first == 100 && second in 64..127) return false
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 168) return false
        }
        return true
    }

    private const val CELLULAR_CONNECT_TIMEOUT_MILLIS = 15_000
}
