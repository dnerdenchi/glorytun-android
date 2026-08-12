package com.example.glorytun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairShareNetworkTest {
    @Test
    fun physicalWifiWithLanAddressIsAccepted() {
        assertTrue(
            PairShareNetwork.isLanWifiCandidate(
                hasWifiTransport = true,
                isNotVpn = true,
                hasLocalIpv4 = true,
            ),
        )
    }

    @Test
    fun vpnThatInheritsWifiTransportIsRejected() {
        assertFalse(
            PairShareNetwork.isLanWifiCandidate(
                hasWifiTransport = true,
                isNotVpn = false,
                hasLocalIpv4 = true,
            ),
        )
    }
}
