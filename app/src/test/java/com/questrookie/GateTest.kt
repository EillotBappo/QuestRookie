package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {
    @Test
    fun localInstallsNeverWait() {
        assertNull(downloadBlock(needsNetwork = false, wifiOnly = true, onWifi = false, chargingOnly = true, charging = false))
    }

    @Test
    fun wifiOnlyHoldsDownloadsOffWifi() {
        assertEquals("Waiting for Wi-Fi", downloadBlock(true, wifiOnly = true, onWifi = false, chargingOnly = false, charging = true))
        assertNull(downloadBlock(true, wifiOnly = true, onWifi = true, chargingOnly = false, charging = false))
    }

    @Test
    fun chargingOnlyHoldsDownloadsOffCharger() {
        assertEquals("Waiting for charger", downloadBlock(true, wifiOnly = false, onWifi = false, chargingOnly = true, charging = false))
        assertNull(downloadBlock(true, wifiOnly = false, onWifi = false, chargingOnly = true, charging = true))
    }

    @Test
    fun noRulesMeansNoWait() {
        assertNull(downloadBlock(true, wifiOnly = false, onWifi = false, chargingOnly = false, charging = false))
    }

    @Test
    fun hoursFormatting() {
        assertEquals("45 min", formatHours(45 * 60_000L))
        // Decimal separator follows the locale (2,5 h in South Africa), so only check the number and unit.
        formatHours(150 * 60_000L).let { assertTrue(it, it.startsWith("2")); assertTrue(it, it.endsWith(" h")) }
    }
}
