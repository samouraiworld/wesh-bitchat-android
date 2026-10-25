package com.bitchat.android.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionManagerTest {
    private lateinit var manager: BluetoothConnectionManager

    @Before
    fun setUp() {
        manager = BluetoothConnectionManager(
            RuntimeEnvironment.getApplication(),
            "0011223344556677"
        )
    }

    @After
    fun tearDown() {
        manager.stopServices()
    }

    @Test
    fun `inactive manager rejects a broadcast instead of reporting it queued`() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            recipientID = null,
            timestamp = 1uL,
            payload = byteArrayOf(1),
            ttl = 7u
        )

        assertFalse(manager.broadcastPacket(RoutedPacket(packet)))
    }

    @Test
    fun `a disconnected link frees the fragment assemblies it opened`() {
        val fragments = FragmentManager()
        val withFragments = BluetoothConnectionManager(RuntimeEnvironment.getApplication(), "0011223344556677", fragments)
        try {
            val fragment = BitchatPacket(
                version = 1u,
                type = MessageType.FRAGMENT.value,
                senderID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                recipientID = null,
                timestamp = 1uL,
                payload = FragmentPayload(ByteArray(8) { 1 }, 0, 2, MessageType.NOISE_ENCRYPTED.value, ByteArray(32) { 1 }).encode(),
                ttl = 7u
            )
            assertEquals(FragmentResult.Pending, fragments.receiveFragment(fragment, PacketProcessor.linkQuotaKey("link-1")))

            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:FF")
            withFragments.componentDelegate.onDeviceDisconnected(device, "link-1", null)

            assertTrue(fragments.getDebugInfo().contains("Active Fragment Sets: 0"))
            assertEquals(FragmentResult.Skipped, fragments.receiveFragment(fragment, PacketProcessor.linkQuotaKey("link-1")))
        } finally {
            withFragments.stopServices()
            fragments.shutdown()
        }
    }

    @Test
    fun `Bluetooth off releases a running manager and on restarts only what it released`() {
        assertEquals(AdapterReaction.RELEASE, adapterReaction(BluetoothAdapter.STATE_TURNING_OFF, active = true, resumePending = false))
        assertEquals(AdapterReaction.RELEASE, adapterReaction(BluetoothAdapter.STATE_OFF, active = true, resumePending = false))
        assertEquals(AdapterReaction.NONE, adapterReaction(BluetoothAdapter.STATE_OFF, active = false, resumePending = true))
        assertEquals(AdapterReaction.RESTART, adapterReaction(BluetoothAdapter.STATE_ON, active = false, resumePending = true))
        assertEquals(AdapterReaction.NONE, adapterReaction(BluetoothAdapter.STATE_ON, active = false, resumePending = false))
        assertEquals(AdapterReaction.NONE, adapterReaction(BluetoothAdapter.STATE_ON, active = true, resumePending = true))
        assertEquals(AdapterReaction.NONE, adapterReaction(BluetoothAdapter.STATE_TURNING_ON, active = true, resumePending = false))
    }

    @Test
    fun `Bluetooth off cancels recovery and still releases a concurrently active generation`() {
        val off = adapterStateTransition(
            BluetoothAdapter.STATE_OFF,
            active = true,
            resumePending = false,
            recoveryPending = true
        )
        assertTrue(off.cancelRecovery)
        assertEquals(AdapterReaction.RELEASE, off.reaction)
        assertTrue(off.resumePending)

        val on = adapterStateTransition(
            BluetoothAdapter.STATE_ON,
            active = false,
            resumePending = off.resumePending,
            recoveryPending = false
        )
        assertFalse(on.cancelRecovery)
        assertEquals(AdapterReaction.RESTART, on.reaction)
    }

    @Test
    fun `Bluetooth off cancels an inactive recovery immediately and on restarts once`() {
        val off = adapterStateTransition(
            BluetoothAdapter.STATE_TURNING_OFF,
            active = false,
            resumePending = false,
            recoveryPending = true
        )
        assertTrue(off.cancelRecovery)
        assertEquals(AdapterReaction.NONE, off.reaction)
        assertTrue(off.resumePending)
        assertEquals(
            AdapterReaction.RESTART,
            adapterStateTransition(
                BluetoothAdapter.STATE_ON,
                active = false,
                resumePending = off.resumePending,
                recoveryPending = false
            ).reaction
        )
    }

    @Test
    fun `stack recovery backs off across server ready client failures until full transport readiness`() {
        val recovery = BluetoothStackRecoveryState()

        recovery.beginGeneration(1)
        recovery.markServerReady(1, transportReady = false)
        assertEquals(3_000L, recovery.nextDelayMs())

        recovery.beginGeneration(2)
        recovery.markServerReady(2, transportReady = false)
        assertEquals(6_000L, recovery.nextDelayMs())

        recovery.beginGeneration(3)
        recovery.markServerReady(3, transportReady = false)
        assertEquals(12_000L, recovery.nextDelayMs())
        assertEquals(24_000L, recovery.nextDelayMs())
        assertEquals(30_000L, recovery.nextDelayMs())
        assertEquals(30_000L, recovery.nextDelayMs())

        recovery.beginGeneration(4)
        recovery.markServerReady(4, transportReady = false)
        recovery.markTransportReady(4, serverEnabled = true)
        assertEquals(3_000L, recovery.nextDelayMs())
    }

    @Test
    fun `late server readiness from an old lifecycle cannot reset recovery backoff`() {
        val recovery = BluetoothStackRecoveryState()
        recovery.beginGeneration(10)
        assertEquals(3_000L, recovery.nextDelayMs())
        recovery.beginGeneration(11)

        assertFalse(recovery.markServerReady(10, transportReady = true))
        assertFalse(recovery.markTransportReady(10, serverEnabled = false))
        assertEquals(6_000L, recovery.nextDelayMs())

        assertTrue(recovery.markServerReady(11, transportReady = false))
        assertTrue(recovery.markTransportReady(11, serverEnabled = true))
        assertEquals(3_000L, recovery.nextDelayMs())
    }

    @Test
    fun `stable client-only transport resets backoff after permanent server disable`() {
        val recovery = BluetoothStackRecoveryState()
        recovery.beginGeneration(20)
        assertEquals(3_000L, recovery.nextDelayMs())

        recovery.beginGeneration(21)
        assertTrue(recovery.markServerDisabled(21, transportReady = false))
        assertTrue(recovery.markTransportReady(21, serverEnabled = true))

        assertEquals(3_000L, recovery.nextDelayMs())
    }

    @Test
    fun `duplicate public start is deferred while recovery owns the next generation`() {
        assertTrue(recoveryOwnsNextStart(recoveryPending = true))
        assertFalse(recoveryOwnsNextStart(recoveryPending = false))
    }

    @Test
    fun `cold start while Bluetooth is off waits for state on unless manager is terminal`() {
        assertTrue(initialResumeWhenBluetoothOn(bluetoothEnabled = false, stopped = false))
        assertFalse(initialResumeWhenBluetoothOn(bluetoothEnabled = true, stopped = false))
        assertFalse(initialResumeWhenBluetoothOn(bluetoothEnabled = false, stopped = true))
    }

    @Config(sdk = [30])
    @Test
    fun `Android 11 observes location for process lifetime and unregisters on terminal stop`() {
        val application = RuntimeEnvironment.getApplication()
        val shadowApplication = shadowOf(application)
        shadowApplication.grantPermissions(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            "${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        val local = BluetoothConnectionManager(application, "0011223344556677")
        try {
            local.startServices()
            val registration = shadowApplication.registeredReceivers.single {
                it.intentFilter.hasAction(LocationManager.MODE_CHANGED_ACTION)
            }
            assertTrue(registration.intentFilter.hasAction(LocationManager.PROVIDERS_CHANGED_ACTION))

            val clientField = BluetoothConnectionManager::class.java.getDeclaredField("clientManager").apply {
                isAccessible = true
            }
            val client = clientField.get(local)
            val generationField = client.javaClass.getDeclaredField("locationObservationGeneration").apply {
                isAccessible = true
            }
            val before = generationField.getLong(client)

            application.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(generationField.getLong(client) > before)
            local.stopServices()
            assertFalse(shadowApplication.registeredReceivers.any {
                it.broadcastReceiver === registration.broadcastReceiver
            })
        } finally {
            local.stopServices()
        }
    }

    @Test
    fun `off or stop during async start invalidates every pending component mutation`() {
        val epoch = BluetoothLifecycleEpoch()
        val starting = epoch.begin()
        assertTrue(epoch.isCurrent(starting))

        epoch.invalidate() // Bluetooth OFF or terminal stop happens before the job resumes.

        assertFalse(epoch.isCurrent(starting))
    }

    @Test
    fun `old startup job cannot mutate a restarted generation`() {
        val epoch = BluetoothLifecycleEpoch()
        val oldStart = epoch.begin()
        epoch.invalidate()
        val restarted = epoch.begin()

        assertFalse(epoch.isCurrent(oldStart))
        assertTrue(epoch.isCurrent(restarted))
    }

    @Test
    fun `disable release is a real barrier before a concurrent restart mutates components`() = runTest {
        val releaseEntered = CompletableDeferred<Unit>()
        val finishRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val lifecycle = BluetoothComponentLifecycleQueue(this) {
            events += "release-enter"
            releaseEntered.complete(Unit)
            finishRelease.await()
            events += "release-exit"
        }

        // disableTransport registers and starts this release while holding the manager lock.
        lifecycle.newRelease(startupToCancel = null).start()
        val restart = lifecycle.newStart { events += "restart-start" }
        restart.start()
        runCurrent()

        assertTrue(releaseEntered.isCompleted)
        assertEquals(listOf("release-enter"), events)

        finishRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("release-enter", "release-exit", "restart-start"), events)
    }

    @Test
    fun `cancelling recovery delay cannot strand the started release barrier`() = runTest {
        val events = mutableListOf<String>()
        val lifecycle = BluetoothComponentLifecycleQueue(this) { events += "release" }
        val release = lifecycle.newRelease(startupToCancel = null)
        release.start()
        val retryOwner = launch(start = CoroutineStart.LAZY) {
            release.join()
            events += "retry"
        }
        retryOwner.start()
        retryOwner.cancelAndJoin() // disable/stop cancels only the delayed restart owner.

        val next = lifecycle.newStart { events += "next-start" }
        next.start()
        advanceUntilIdle()

        assertEquals(listOf("release", "next-start"), events)
    }

    @Test
    fun `permanent async failure cancels startup before serialized release`() = runTest {
        val startEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val lifecycle = BluetoothComponentLifecycleQueue(this) { events += "release" }
        val startup = lifecycle.newStart {
            events += "start-enter"
            startEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                events += "start-cancelled"
            }
        }
        startup.start()
        runCurrent()
        assertTrue(startEntered.isCompleted)

        val release = lifecycle.newRelease(startup)
        release.start()
        advanceUntilIdle()

        assertEquals(listOf("start-enter", "start-cancelled", "release"), events)
    }

    @Test
    fun `direct role control waits for release and can enqueue failure teardown safely`() = runTest {
        val releaseEntered = CompletableDeferred<Unit>()
        val finishRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        lateinit var lifecycle: BluetoothComponentLifecycleQueue
        lifecycle = BluetoothComponentLifecycleQueue(this) {
            events += "release-enter"
            if (!releaseEntered.isCompleted) {
                releaseEntered.complete(Unit)
                finishRelease.await()
            }
            events += "release-exit"
        }
        lifecycle.newRelease(startupToCancel = null).start()
        val roleControl = lifecycle.newStart {
            events += "role-control"
            lifecycle.newRelease(startupToCancel = null).start()
        }
        roleControl.start()
        runCurrent()

        assertEquals(listOf("release-enter"), events)
        finishRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("release-enter", "release-exit", "role-control", "release-enter", "release-exit"),
            events
        )
    }
}
