package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRateCalculatorTest {
    @Test
    fun rxOnlyTrafficProducesDownloadRate() {
        val calculator = TrafficRateCalculator()

        calculator.update(
            nowMs = 1_000L,
            wifiActive = true,
            simActive = false,
            wifiTx = 0L,
            wifiRx = 10_000L,
            simTx = 0L,
            simRx = 0L
        )

        val rates = calculator.update(
            nowMs = 2_000L,
            wifiActive = true,
            simActive = false,
            wifiTx = 0L,
            wifiRx = 112_400L,
            simTx = 0L,
            simRx = 0L
        )

        assertEquals(100f, rates.wifiKBs, 0.001f)
    }

    @Test
    fun inactiveNetworkClearsItsBaseline() {
        val calculator = TrafficRateCalculator()

        calculator.update(
            nowMs = 1_000L,
            wifiActive = true,
            simActive = false,
            wifiTx = 0L,
            wifiRx = 10_000L,
            simTx = 0L,
            simRx = 0L
        )
        calculator.update(
            nowMs = 2_000L,
            wifiActive = false,
            simActive = false,
            wifiTx = 0L,
            wifiRx = 10_000L,
            simTx = 0L,
            simRx = 0L
        )

        val rates = calculator.update(
            nowMs = 3_000L,
            wifiActive = true,
            simActive = false,
            wifiTx = 0L,
            wifiRx = 112_400L,
            simTx = 0L,
            simRx = 0L
        )

        assertEquals(0f, rates.wifiKBs, 0.001f)
    }

    @Test
    fun simOnlyTrafficProducesRate() {
        val calculator = TrafficRateCalculator()

        calculator.update(
            nowMs = 1_000L,
            wifiActive = false,
            simActive = true,
            wifiTx = 0L,
            wifiRx = 0L,
            simTx = 0L,
            simRx = 8_000L
        )

        val rates = calculator.update(
            nowMs = 3_000L,
            wifiActive = false,
            simActive = true,
            wifiTx = 0L,
            wifiRx = 0L,
            simTx = 0L,
            simRx = 212_800L
        )

        assertEquals(100f, rates.simKBs, 0.001f)
    }
}
