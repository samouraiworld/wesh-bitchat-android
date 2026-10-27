package com.bitchat.android.onboarding

import android.app.Application
import com.bitchat.android.MainActivity
import com.bitchat.android.MainViewModel
import com.bitchat.android.shouldApplyBluetoothGraceResult
import com.bitchat.android.shouldBlockOnBluetoothRadio
import com.bitchat.android.shouldBlockOnSystemLocationSwitch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingActivityCallbackTest {
    @Test
    fun `modern Wesh ignores a disabled system location callback`() {
        val fixture = fixture()

        fixture.locationDisabled()

        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        verify(fixture.locationManager, never()).checkLocationStatus()
    }

    @Test
    fun `modern Wesh ignores an unavailable system location callback`() {
        val fixture = fixture(location = LocationStatus.NOT_AVAILABLE)

        fixture.locationDisabled()

        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        verify(fixture.locationManager, never()).checkLocationStatus()
    }

    @Test
    @Config(sdk = [30])
    fun `legacy Android records its disabled scan switch without hiding Wesh`() {
        val fixture = fixture()

        fixture.locationDisabled()

        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        assertEquals(LocationStatus.DISABLED, fixture.model.locationStatus.value)
    }

    @Test
    fun `revoked Bluetooth permission returns to its explanation instead of an enable loop`() {
        val fixture = fixture(requiredGranted = false)

        fixture.bluetoothDisabled()

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, fixture.model.onboardingState.value)
    }

    @Test
    fun `Bluetooth off keeps the Wesh shell and Settings accessible`() {
        val fixture = fixture(requiredGranted = true)

        fixture.bluetoothDisabled()

        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
    }

    @Test
    @Config(sdk = [30])
    fun `Android 11 checks the BLE scan switch without gating Settings or Internet`() {
        val fixture = fixture(location = LocationStatus.DISABLED)

        fixture.onboardingComplete()

        assertEquals(OnboardingState.INITIALIZING, fixture.model.onboardingState.value)
        assertEquals(LocationStatus.DISABLED, fixture.model.locationStatus.value)
        verify(fixture.locationManager).checkLocationStatus()
    }

    @Test
    fun `Bluetooth off does not consult the obsolete Location gate on modern Android`() {
        val fixture = fixture()

        fixture.bluetoothDisabled()

        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        verify(fixture.locationManager, never()).checkLocationStatus()
    }

    @Test
    fun `Bluetooth state cannot overwrite a newer permission gate`() {
        val fixture = fixture(requiredGranted = true)

        fixture.model.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
        fixture.bluetoothDisabled()

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, fixture.model.onboardingState.value)
    }

    @Test
    fun `Bluetooth grace result requires both complete state and current permissions`() {
        assertEquals(true, shouldApplyBluetoothGraceResult(OnboardingState.COMPLETE, true))
        assertEquals(false, shouldApplyBluetoothGraceResult(OnboardingState.COMPLETE, false))
        assertEquals(false, shouldApplyBluetoothGraceResult(OnboardingState.PERMISSION_EXPLANATION, true))
    }

    @Test
    fun `unsupported Bluetooth does not hide Wesh Settings or Internet`() {
        val fixture = fixture(bluetooth = BluetoothStatus.NOT_SUPPORTED)

        fixture.bluetoothDisabled()

        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        assertEquals(BluetoothStatus.NOT_SUPPORTED, fixture.model.bluetoothStatus.value)
    }

    @Test
    fun `only the upstream shell treats an enabled BLE transport as a radio gate`() {
        assertEquals(false, shouldBlockOnBluetoothRadio(weshPolicy = true, bleTransportEnabled = true))
        assertEquals(true, shouldBlockOnBluetoothRadio(weshPolicy = false, bleTransportEnabled = true))
        assertEquals(false, shouldBlockOnBluetoothRadio(weshPolicy = false, bleTransportEnabled = false))
    }

    @Test
    fun `only the upstream shell treats legacy Location as a navigation gate`() {
        assertEquals(false, shouldBlockOnSystemLocationSwitch(weshPolicy = true, requiredForBleScan = true))
        assertEquals(true, shouldBlockOnSystemLocationSwitch(weshPolicy = false, requiredForBleScan = true))
        assertEquals(false, shouldBlockOnSystemLocationSwitch(weshPolicy = false, requiredForBleScan = false))
    }

    @Test
    @Config(sdk = [30])
    fun `legacy unavailable system switch remains visible without blocking Wesh`() {
        val fixture = fixture(location = LocationStatus.NOT_AVAILABLE)

        fixture.locationDisabled()

        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        assertEquals(LocationStatus.NOT_AVAILABLE, fixture.model.locationStatus.value)
    }

    @Test
    fun `a required permission failure returns to its explained step`() {
        val fixture = fixture(requiredGranted = false)

        fixture.onboardingFailed()

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, fixture.model.onboardingState.value)
    }

    private fun fixture(
        location: LocationStatus = LocationStatus.DISABLED,
        bluetooth: BluetoothStatus = BluetoothStatus.DISABLED,
        bluetoothStatuses: List<BluetoothStatus> = listOf(bluetooth),
        requiredGranted: Boolean = true
    ): Fixture {
        // Attach only: onCreate would construct the unrelated mesh/UI graph. Existing callback
        // bodies below read injected state only; no location, radio, or persisted app state is read.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.COMPLETE) }
        val locationManager = mock<LocationStatusManager>()
        whenever(locationManager.checkLocationStatus()).thenReturn(location)
        val bluetoothManager = mock<BluetoothStatusManager>()
        whenever(bluetoothManager.checkBluetoothStatus()).thenReturn(
            bluetoothStatuses.first(),
            *bluetoothStatuses.drop(1).toTypedArray(),
        )
        val permissionManager = mock<PermissionManager>()
        whenever(permissionManager.areRequiredPermissionsGranted()).thenReturn(requiredGranted)
        whenever(permissionManager.isFirstTimeLaunch()).thenReturn(false)
        val batteryManager = mock<BatteryOptimizationManager>()
        whenever(batteryManager.isBatteryOptimizationSupported()).thenReturn(false)
        set(activity, "mainViewModel\$delegate", lazyOf(model))
        set(activity, "locationStatusManager", locationManager)
        set(activity, "bluetoothStatusManager", bluetoothManager)
        set(activity, "permissionManager", permissionManager)
        set(activity, "batteryOptimizationManager", batteryManager)
        return Fixture(activity, model, locationManager)
    }

    private fun set(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private class Fixture(
        val activity: MainActivity,
        val model: MainViewModel,
        val locationManager: LocationStatusManager
    ) {
        fun locationDisabled() = invoke("handleLocationDisabled", "Synthetic system switch unavailable")

        fun bluetoothDisabled() = invoke(
            "handleBluetoothDisabled",
            "Bluetooth permissions are required before enabling Bluetooth. Please grant permissions first."
        )

        fun onboardingFailed() = invoke("handleOnboardingFailed", "Synthetic required permission missing")

        fun onboardingComplete() {
            MainActivity::class.java.getDeclaredMethod("handleOnboardingComplete")
                .apply { isAccessible = true }.invoke(activity)
        }

        private fun invoke(method: String, value: String) {
            MainActivity::class.java.getDeclaredMethod(method, String::class.java)
                .apply { isAccessible = true }.invoke(activity, value)
        }
    }
}
