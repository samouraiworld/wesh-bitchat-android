package com.bitchat.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeshBluetoothAvailabilityTest {
    @Test
    fun `Android 11 reports scan unavailable while system Location is off`() {
        assertFalse(
            weshBluetoothScanAvailable(
                sdkInt = 30,
                adapterEnabled = true,
                modernPermissionsGranted = true,
                legacyLocationGranted = true,
                legacyLocationEnabled = false,
            )
        )
    }

    @Test
    fun `Android 11 requires both granted location permissions and its switch`() {
        assertFalse(
            weshBluetoothScanAvailable(
                sdkInt = 30,
                adapterEnabled = true,
                modernPermissionsGranted = true,
                legacyLocationGranted = false,
                legacyLocationEnabled = true,
            )
        )
        assertTrue(
            weshBluetoothScanAvailable(
                sdkInt = 30,
                adapterEnabled = true,
                modernPermissionsGranted = true,
                legacyLocationGranted = true,
                legacyLocationEnabled = true,
            )
        )
    }

    @Test
    fun `modern Android scan availability follows Nearby Devices instead of Location`() {
        assertTrue(
            weshBluetoothScanAvailable(
                sdkInt = 31,
                adapterEnabled = true,
                modernPermissionsGranted = true,
                legacyLocationGranted = false,
                legacyLocationEnabled = false,
            )
        )
        assertFalse(
            weshBluetoothScanAvailable(
                sdkInt = 31,
                adapterEnabled = true,
                modernPermissionsGranted = false,
                legacyLocationGranted = true,
                legacyLocationEnabled = true,
            )
        )
    }
}
