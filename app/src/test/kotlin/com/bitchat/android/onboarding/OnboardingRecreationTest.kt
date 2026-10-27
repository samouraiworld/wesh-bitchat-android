package com.bitchat.android.onboarding

import android.app.Application
import android.content.res.Resources
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.MainViewModel
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.WeshQrIdentity
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingRecreationTest {
    private val fixtures = mutableListOf<Fixture>()

    @After
    fun cleanUp() {
        fixtures.forEach { it.destroy() }
        WeshQrIdentity.resetForTesting()
    }

    @Test
    fun `destroying the permission owner cannot lose an already recorded completion`() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val permissions = mock<PermissionManager>()
        whenever(permissions.areRequiredPermissionsGranted()).thenReturn(true)
        var completed = 0
        val coordinator = OnboardingCoordinator(
            owner.get(), permissions, { completed++ }, { error("Unexpected permission failure") },
        )

        coordinator.startOnboarding()
        verify(permissions).markOnboardingComplete()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        owner.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))

        assertEquals("An Activity replacement must not drop accepted onboarding", 1, completed)
    }

    @Test
    fun `destroying the initialization owner is cancellation rather than a setup error`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.INITIALIZING) }
        val owner = fixture(model)

        owner.initialize()
        advance(50)
        owner.destroy()

        assertEquals(OnboardingState.INITIALIZING, model.onboardingState.value)
        assertFalse("Cancellation must not become a visible setup failure", model.errorMessage.value.isNotEmpty())
        verify(owner.mesh, never()).startServices()
    }

    @Test
    fun `a replacement owner resumes an interrupted initialization and completes`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.INITIALIZING) }
        val mesh = mock<MeshService>()
        val oldOwner = fixture(model, mesh = mesh)
        oldOwner.initialize()
        advance(50)
        oldOwner.destroy()
        val replacement = fixture(model, mesh = mesh)

        replacement.resumeAfterAttach()
        advance(1_600)

        assertEquals(OnboardingState.COMPLETE, model.onboardingState.value)
        assertEquals("", model.errorMessage.value)
        verify(mesh, times(1)).startServices()
    }

    @Test
    fun `recreation after service attachment resumes on the retained backend without a false error`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.INITIALIZING) }
        val mesh = mock<MeshService>()
        val oldOwner = fixture(model, mesh = mesh)
        oldOwner.initialize()
        advance(1_100)
        verify(mesh, times(1)).startServices()
        val oldJob = oldOwner.job()

        oldOwner.destroy()
        assertTrue(oldJob?.isCancelled == true)
        assertEquals(OnboardingState.INITIALIZING, model.onboardingState.value)
        val replacement = fixture(model, mesh = mesh)
        replacement.resumeAfterAttach()
        advance(1_600)

        assertEquals(OnboardingState.COMPLETE, model.onboardingState.value)
        assertEquals("", model.errorMessage.value)
        assertSame(mesh, replacement.mesh)
        // Activity attachment is retried on the same process-owned, idempotent backend.
        verify(mesh, times(2)).startServices()
    }

    @Test
    fun `recorded permission completion resumes rather than waiting for a lost callback`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.PERMISSION_REQUESTING) }
        val owner = fixture(model, completionRecorded = true)

        owner.resumeAfterAttach()
        advance(1_600)

        assertEquals(OnboardingState.COMPLETE, model.onboardingState.value)
        verify(owner.mesh, times(1)).startServices()
    }

    @Test
    fun `an unfinished permission prompt is not treated as recorded completion`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.PERMISSION_REQUESTING) }
        val owner = fixture(model, completionRecorded = false)

        owner.resumeAfterAttach()
        advance(2_000)

        assertEquals(OnboardingState.PERMISSION_REQUESTING, model.onboardingState.value)
        assertNull(owner.job())
        verify(owner.mesh, never()).startServices()
    }

    @Test
    fun `revoked permissions return an interrupted launch to its explanation`() {
        for (state in listOf(OnboardingState.INITIALIZING, OnboardingState.PERMISSION_REQUESTING)) {
            val model = MainViewModel().apply { updateOnboardingState(state) }
            val owner = fixture(model, requiredGranted = false)

            owner.resumeAfterAttach()
            advance(1_600)

            assertEquals(OnboardingState.PERMISSION_EXPLANATION, model.onboardingState.value)
            assertNull(owner.job())
            verify(owner.mesh, never()).startServices()
        }
    }

    @Test
    fun `an unrecorded initialization does not skip first-time onboarding`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.INITIALIZING) }
        val owner = fixture(model, completionRecorded = false)

        owner.resumeAfterAttach()

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, model.onboardingState.value)
        assertNull(owner.job())
    }

    @Test
    fun `repeated callbacks share one initialization job and cannot restart a completed one`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.INITIALIZING) }
        val owner = fixture(model)

        owner.initialize()
        val job = owner.job()
        assertTrue(job?.isActive == true)
        owner.initialize()
        owner.complete()
        assertSame(job, owner.job())
        advance(1_600)
        owner.complete()
        advance(1_600)

        assertEquals(OnboardingState.COMPLETE, model.onboardingState.value)
        assertSame(job, owner.job())
        verify(owner.mesh, times(1)).startServices()
    }

    @Test
    fun `a newer permission phase cannot be overwritten by initialization`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.INITIALIZING) }
        val owner = fixture(model)
        owner.initialize()
        advance(50)

        model.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
        advance(1_600)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, model.onboardingState.value)
        verify(owner.mesh, never()).startServices()
    }

    @Test
    fun `a new permission completion cannot be blocked by an abandoned initialization job`() {
        val model = MainViewModel().apply { updateOnboardingState(OnboardingState.INITIALIZING) }
        val owner = fixture(model)
        owner.initialize()
        advance(50)

        model.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
        model.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
        owner.complete()
        advance(1_600)

        assertEquals(OnboardingState.COMPLETE, model.onboardingState.value)
        assertEquals("", model.errorMessage.value)
        verify(owner.mesh, times(1)).startServices()
    }

    private fun fixture(
        model: MainViewModel,
        completionRecorded: Boolean = true,
        requiredGranted: Boolean = true,
        mesh: MeshService = mock(),
    ): Fixture {
        // Attach only: invoking onCreate would construct unrelated native/mesh state. Bind
        // lifecycleScope to a registry without the framework saved-state/onCreate observers.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val resources = mock<Resources>()
        whenever(resources.getString(any())).thenReturn("Synthetic initialization failure")
        ReflectionHelpers.setField(activity, "mResources", resources)
        val permissions = mock<PermissionManager>()
        whenever(permissions.areRequiredPermissionsGranted()).thenReturn(requiredGranted)
        whenever(permissions.areAllPermissionsGranted()).thenReturn(requiredGranted)
        whenever(permissions.isFirstTimeLaunch()).thenReturn(!completionRecorded)
        val bluetooth = mock<BluetoothStatusManager>()
        whenever(bluetooth.checkBluetoothStatus()).thenReturn(BluetoothStatus.ENABLED)
        val battery = mock<BatteryOptimizationManager>()
        whenever(battery.isBatteryOptimizationSupported()).thenReturn(false)
        ReflectionHelpers.setField(activity, "mainViewModel\$delegate", lazyOf(model))
        ReflectionHelpers.setField(activity, "chatViewModel\$delegate", lazyOf(mock<ChatViewModel>()))
        ReflectionHelpers.setField(activity, "permissionManager", permissions)
        ReflectionHelpers.setField(activity, "bluetoothStatusManager", bluetooth)
        ReflectionHelpers.setField(activity, "batteryOptimizationManager", battery)
        ReflectionHelpers.setField(activity, "unifiedMeshService", mesh)
        val lifecycle = LifecycleRegistry(activity)
        ReflectionHelpers.setField(activity, "lifecycleRegistry", lifecycle)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        // Keep the unchanged key-ready call fenced without reading or creating an identity.
        WeshQrIdentity.io = Dispatchers.Main.immediate
        WeshQrIdentity.eraseUnconfirmed = { true }
        WeshQrIdentity.keyStore = { error("Identity storage must not be touched by this fixture") }
        return Fixture(activity, lifecycle, mesh).also(fixtures::add)
    }

    private fun advance(millis: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private class Fixture(
        val activity: MainActivity,
        val lifecycle: LifecycleRegistry,
        val mesh: MeshService,
    ) {
        fun initialize() = invoke("initializeApp")
        fun resumeAfterAttach() = invoke("resumeOnboardingAfterRuntimeAttachment")
        fun complete() = invoke("handleOnboardingComplete")
        fun job(): Job? = ReflectionHelpers.getField(activity, "initializationJob")
        fun destroy() {
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        private fun invoke(name: String) {
            MainActivity::class.java.getDeclaredMethod(name)
                .apply { isAccessible = true }.invoke(activity)
        }
    }
}
