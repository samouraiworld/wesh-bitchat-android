package com.bitchat.android

import com.bitchat.android.ui.pave.BluetoothRefusal
import com.bitchat.android.ui.pave.OnboardingStart
import com.bitchat.android.ui.pave.BluetoothStepAction
import com.bitchat.android.ui.pave.bluetoothRefusal
import com.bitchat.android.ui.pave.bluetoothStepAction
import com.bitchat.android.ui.pave.OnboardingStep
import com.bitchat.android.ui.pave.onboardingCanGoBack
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeshWelcomeTest {
    @Test
    fun `a simple refusal keeps Continuer, only a permanent one sends to Settings`() {
        assertEquals(BluetoothRefusal.None, bluetoothRefusal(granted = true, shouldShowRationale = false))
        assertEquals(BluetoothRefusal.Once, bluetoothRefusal(granted = false, shouldShowRationale = true))
        assertEquals(BluetoothRefusal.Permanent, bluetoothRefusal(granted = false, shouldShowRationale = false))
    }

    @Test
    fun `an existing nickname is kept, BitChat's default is not proposed`() {
        assertEquals("Renard 42", welcomeNickname(welcomed = false, stored = "Renard 42"))
        assertNull(welcomeNickname(welcomed = false, stored = "anon1234"))
        assertNull(welcomeNickname(welcomed = false, stored = ""))
        assertEquals("anon1234", welcomeNickname(welcomed = true, stored = "anon1234"))
    }

    @Test
    fun `a returning person goes straight to what is missing`() {
        assertEquals(OnboardingStart.Welcome, welcomeStart(welcomed = false, requiredGranted = false, notificationsPending = true))
        assertEquals(OnboardingStart.Bluetooth, welcomeStart(welcomed = true, requiredGranted = false, notificationsPending = true))
        assertEquals(OnboardingStart.Notifications, welcomeStart(welcomed = true, requiredGranted = true, notificationsPending = true))
        assertEquals(OnboardingStart.Notifications, welcomeStart(welcomed = false, requiredGranted = true, notificationsPending = true))
    }

    @Test
    fun `Bluetooth Back returns to nickname only when Welcome started the flow`() {
        assertEquals(true, onboardingCanGoBack(OnboardingStep.Bluetooth, OnboardingStart.Welcome))
        assertEquals(false, onboardingCanGoBack(OnboardingStep.Bluetooth, OnboardingStart.Bluetooth))
        assertEquals(false, onboardingCanGoBack(OnboardingStep.Notifications, OnboardingStart.Welcome))
    }

    @Test
    fun `no Bluetooth refusal leads to the error screen or loops without an action`() {
        // Every refusal keeps one action forward on the Pavé step itself.
        assertEquals(BluetoothStepAction.Ask, bluetoothStepAction(BluetoothRefusal.None))
        assertEquals(BluetoothStepAction.Ask, bluetoothStepAction(BluetoothRefusal.Once))
        assertEquals(BluetoothStepAction.OpenSettings, bluetoothStepAction(BluetoothRefusal.Permanent))
        val flow = File("src/main/java/com/bitchat/android/ui/pave/OnboardingFlow.kt").readText()
        val activity = File("src/main/java/com/bitchat/android/MainActivity.kt").readText()
        // V1 has no way around Bluetooth, so nothing completes onboarding without it.
        assertFalse(flow.contains("continue_without") || flow.contains("onContinueWithout"))
        assertFalse(activity.contains("continueWithoutBluetooth"))
        // Revoked permissions go back to the explained step, never to a hard-coded English error.
        assertFalse(activity.contains("Some permissions were revoked"))
        assertFalse(activity.contains("Failed to initialize the app"))
        assertFalse(Regex("handleOnboardingFailed\\(\"").containsMatchIn(activity))
    }
}
