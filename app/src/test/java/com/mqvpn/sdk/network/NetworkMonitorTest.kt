package com.mqvpn.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {
    @Test
    fun usableWifiPathRequiresNotVpnCapability() {
        val vpnBackedWifi = flags(hasNotVpn = false, hasWifi = true)

        assertFalse(NetworkMonitor.isUsablePath(vpnBackedWifi))
    }

    @Test
    fun validatedPhysicalNetworksAreUsablePaths() {
        assertTrue(NetworkMonitor.isUsablePath(flags(hasWifi = true)))
        assertTrue(NetworkMonitor.isUsablePath(flags(hasCellular = true)))
        assertTrue(NetworkMonitor.isUsablePath(flags(hasEthernet = true)))
    }

    @Test
    fun validatedOtherTransportIsNotUsedAsMqvpnPath() {
        val otherTransport = flags()

        assertFalse(NetworkMonitor.isUsablePath(otherTransport))
    }

    @Test
    fun transportClassificationPrefersPhysicalPathTypes() {
        assertEquals(PathType.WIFI, NetworkMonitor.classifyTransport(flags(hasWifi = true)))
        assertEquals(PathType.CELLULAR, NetworkMonitor.classifyTransport(flags(hasCellular = true)))
        assertEquals(PathType.ETHERNET, NetworkMonitor.classifyTransport(flags(hasEthernet = true)))
    }

    private fun flags(
        hasInternet: Boolean = true,
        hasValidated: Boolean = true,
        hasNotVpn: Boolean = true,
        hasWifi: Boolean = false,
        hasCellular: Boolean = false,
        hasEthernet: Boolean = false,
        hasNotMetered: Boolean = true,
    ) = NetworkCapabilityFlags(
        hasInternet = hasInternet,
        hasValidated = hasValidated,
        hasNotVpn = hasNotVpn,
        hasWifi = hasWifi,
        hasCellular = hasCellular,
        hasEthernet = hasEthernet,
        hasNotMetered = hasNotMetered,
    )
}
