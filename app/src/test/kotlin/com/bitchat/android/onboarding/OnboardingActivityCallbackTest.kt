package com.bitchat.android.onboarding

import android.app.Application
import com.bitchat.android.MainActivity
import com.bitchat.android.MainViewModel
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
    fun `legacy Android still requires its disabled system switch before scanning`() {
        val fixture = fixture()

        fixture.locationDisabled()

        assertEquals(OnboardingState.LOCATION_CHECK, fixture.model.onboardingState.value)
    }

    @Test
    fun `revoked Bluetooth permission returns to its explanation instead of an enable loop`() {
        val fixture = fixture(requiredGranted = false)

        fixture.bluetoothDisabled()

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, fixture.model.onboardingState.value)
    }

    @Test
    fun `Bluetooth off with all permissions granted retains an enable action`() {
        val fixture = fixture(requiredGranted = true)

        fixture.bluetoothDisabled()

        // Some tablets turn Bluetooth off on every sleep: the page waits out a short grace period.
        assertEquals(OnboardingState.COMPLETE, fixture.model.onboardingState.value)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertEquals(OnboardingState.BLUETOOTH_CHECK, fixture.model.onboardingState.value)
    }

    @Test
    fun `unsupported Bluetooth retains its blocked fallback instead of a generic error`() {
        val fixture = fixture(bluetooth = BluetoothStatus.NOT_SUPPORTED)

        fixture.bluetoothDisabled()

        assertEquals(OnboardingState.BLUETOOTH_CHECK, fixture.model.onboardingState.value)
        assertEquals(BluetoothStatus.NOT_SUPPORTED, fixture.model.bluetoothStatus.value)
    }

    @Test
    @Config(sdk = [30])
    fun `legacy unavailable system switch retains its blocked fallback`() {
        val fixture = fixture(location = LocationStatus.NOT_AVAILABLE)

        fixture.locationDisabled()

        assertEquals(OnboardingState.LOCATION_CHECK, fixture.model.onboardingState.value)
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
        requiredGranted: Boolean = true
    ): Fixture {
        // Attach only: onCreate would construct the unrelated mesh/UI graph. Existing callback
        // bodies below read injected state only; no location, radio, or persisted app state is read.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.COMPLETE) }
        val locationManager = mock<LocationStatusManager>()
        whenever(locationManager.checkLocationStatus()).thenReturn(location)
        val bluetoothManager = mock<BluetoothStatusManager>()
        whenever(bluetoothManager.checkBluetoothStatus()).thenReturn(bluetooth)
        val permissionManager = mock<PermissionManager>()
        whenever(permissionManager.areRequiredPermissionsGranted()).thenReturn(requiredGranted)
        whenever(permissionManager.isFirstTimeLaunch()).thenReturn(false)
        set(activity, "mainViewModel\$delegate", lazyOf(model))
        set(activity, "locationStatusManager", locationManager)
        set(activity, "bluetoothStatusManager", bluetoothManager)
        set(activity, "permissionManager", permissionManager)
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

        private fun invoke(method: String, value: String) {
            MainActivity::class.java.getDeclaredMethod(method, String::class.java)
                .apply { isAccessible = true }.invoke(activity, value)
        }
    }
}
