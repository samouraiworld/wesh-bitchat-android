package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** All addresses, packets and devices are synthetic; no emulator or radio is accessed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35])
class BluetoothGattServerPermissionCallbacksTest {
    private val scope = TestScope()
    private val advertiser = mock<BluetoothLeAdvertiser>()
    private val adapter = mock<BluetoothAdapter> {
        on { isEnabled }.thenReturn(true)
        on { bluetoothLeAdvertiser }.thenReturn(advertiser)
        on { isMultipleAdvertisementSupported }.thenReturn(true)
    }
    private val gatt = mock<BluetoothGattServer>()
    private var openResult: BluetoothGattServer? = gatt
    private var openFails = false
    private var addFails = false
    private var addAccepted = true
    private var duringOpen: (() -> Unit)? = null
    private lateinit var callback: BluetoothGattServerCallback
    private lateinit var service: BluetoothGattService
    private val bluetoothManager = mock<BluetoothManager> {
        on { adapter }.thenReturn(this@BluetoothGattServerPermissionCallbacksTest.adapter)
    }
    private val context = mock<Context> {
        on { getSystemService(Context.BLUETOOTH_SERVICE) }.thenReturn(bluetoothManager)
    }
    private val permissions = mock<BluetoothPermissionManager> {
        on { hasBluetoothPermissions() }.thenReturn(true)
    }
    private val power = mock<PowerManager> {
        on { getAdvertiseSettings() }.thenReturn(AdvertiseSettings.Builder().build())
    }
    private val delegate = mock<BluetoothConnectionManagerDelegate>()
    private val tracker = BluetoothConnectionTracker(scope, power)
    private val roleFailures = mutableListOf<Pair<String, BluetoothRoleFailureAction>>()
    private val failureOwners = mutableListOf<Long>()
    private val readyOwners = mutableListOf<Long>()
    private var beforeAdvertiseStart: (() -> Unit)? = null
    private val manager = BluetoothGattServerManager(context, scope, tracker, permissions, power,
        delegate,
        roleFailure = { owner, reason, action ->
            failureOwners += owner
            roleFailures += reason to action
        },
        roleReady = readyOwners::add,
        beforePlatformAdvertiseStart = { beforeAdvertiseStart?.invoke() }
    )
    private val device = mock<BluetoothDevice> {
        on { address }.thenReturn("AA:BB:CC:DD:EE:01")
    }

    init {
        whenever(bluetoothManager.openGattServer(any(), any())).thenAnswer {
            callback = it.getArgument(1)
            duringOpen?.invoke()
            if (openFails) throw SecurityException("synthetic revocation")
            openResult
        }
        whenever(gatt.addService(any())).thenAnswer {
            service = it.getArgument(0)
            if (addFails) throw SecurityException("synthetic revocation")
            addAccepted
        }
        whenever(gatt.sendResponse(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
    }

    @After fun tearDown() { manager.stop(); scope.cancel() }

    private fun start() { assertTrue(manager.start()); scope.runCurrent() }
    private fun publish() { callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, service) }
    private fun connect() { callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED) }
    private fun descriptor() = manager.getCharacteristic()!!.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
    private fun subscribe(id: Int = 1, value: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) {
        callback.onDescriptorWriteRequest(device, id, descriptor(), false, true, 0, value)
    }
    private fun readyTime() { scope.advanceTimeBy(100); scope.runCurrent() }
    private fun packet() = BitchatPacket(version = 1u, type = 4u,
        senderID = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), recipientID = null,
        timestamp = 1uL, payload = byteArrayOf(1), ttl = 7u).toBinaryData()!!

    @Test fun `start reports unavailable advertiser until the stack resource returns`() {
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(null)
        assertEquals(BluetoothRoleStartResult.RESOURCE_UNAVAILABLE, manager.startResult())
        assertTrue(roleFailures.isEmpty())

        whenever(adapter.bluetoothLeAdvertiser).thenReturn(advertiser)
        assertEquals(BluetoothRoleStartResult.STARTED, manager.startResult())
        scope.runCurrent()
        assertSame(gatt, manager.getGattServer())
    }

    @Test fun `permission revoked after start but before setup stops transport for exact owner`() {
        assertEquals(BluetoothRoleStartResult.STARTED, manager.startResult(ownerGeneration = 61))
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)

        scope.runCurrent()

        assertEquals(
            listOf("setup-permission" to BluetoothRoleFailureAction.STOP_TRANSPORT),
            roleFailures
        )
        assertEquals(listOf(61L), failureOwners)
        assertNull(manager.getGattServer())
    }

    @Test fun `unsupported peripheral mode is client-only rather than transient stack failure`() {
        whenever(adapter.isMultipleAdvertisementSupported).thenReturn(false)
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(null)

        assertEquals(BluetoothRoleStartResult.ROLE_UNSUPPORTED, manager.startResult())
        scope.advanceTimeBy(60_000)
        scope.runCurrent()

        assertTrue(roleFailures.isEmpty())
        verify(bluetoothManager, never()).openGattServer(any(), any())
    }

    @Test fun `advertising waits for successful service callback`() {
        start()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
        publish()
        verify(advertiser).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `server readiness and failure callbacks retain their manager lifecycle owner`() {
        assertEquals(BluetoothRoleStartResult.STARTED, manager.startResult(ownerGeneration = 42))
        scope.runCurrent()
        publish()
        val advertiseCallback = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), advertiseCallback.capture())
        advertiseCallback.firstValue.onStartSuccess(AdvertiseSettings.Builder().build())
        scope.runCurrent()
        assertEquals(listOf(42L), readyOwners)

        manager.stop()
        assertEquals(BluetoothRoleStartResult.STARTED, manager.startResult(ownerGeneration = 43))
        scope.runCurrent()
        callback.onServiceAdded(BluetoothGatt.GATT_FAILURE, service)
        scope.runCurrent()
        assertEquals(listOf(43L), failureOwners)
    }

    @Test fun `synchronous advertise success notifies manager only after platform start returns`() {
        var insidePlatformStart = false
        doAnswer {
            val callback = it.getArgument<AdvertiseCallback>(3)
            insidePlatformStart = true
            callback.onStartSuccess(AdvertiseSettings.Builder().build())
            assertTrue(readyOwners.isEmpty())
            insidePlatformStart = false
            null
        }.whenever(advertiser).startAdvertising(any(), any(), any(), any())

        assertEquals(BluetoothRoleStartResult.STARTED, manager.startResult(ownerGeneration = 51))
        scope.runCurrent()
        publish()
        assertFalse(insidePlatformStart)
        scope.runCurrent()

        assertEquals(listOf(51L), readyOwners)
    }

    @Test fun `synchronous permanent advertise failure notifies manager after lock release`() {
        var insidePlatformStart = false
        doAnswer {
            val callback = it.getArgument<AdvertiseCallback>(3)
            insidePlatformStart = true
            callback.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            assertTrue(roleFailures.isEmpty())
            insidePlatformStart = false
            null
        }.whenever(advertiser).startAdvertising(any(), any(), any(), any())

        assertEquals(BluetoothRoleStartResult.STARTED, manager.startResult(ownerGeneration = 52))
        scope.runCurrent()
        publish()
        assertFalse(insidePlatformStart)
        scope.runCurrent()

        assertEquals(
            listOf("advertise-unsupported" to BluetoothRoleFailureAction.DISABLE_ROLE),
            roleFailures
        )
        assertEquals(listOf(52L), failureOwners)
    }

    @Test fun `overlapping advertising restarts leave one advertiser running`() {
        start()
        publish()
        manager.restartAdvertising()
        manager.restartAdvertising()
        scope.advanceTimeBy(200)
        scope.runCurrent()
        val started = argumentCaptor<AdvertiseCallback>()
        verify(advertiser, times(2)).startAdvertising(any(), any(), any(), started.capture())
        val stopped = argumentCaptor<AdvertiseCallback>()
        verify(advertiser, atLeast(0)).stopAdvertising(stopped.capture())
        assertEquals(1, (started.allValues - stopped.allValues.toSet()).size)
    }

    @Test fun `late permanent failure from replaced advertiser cannot stop current set`() {
        start(); publish()
        val callbacks = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), callbacks.capture())
        val replaced = callbacks.firstValue

        manager.restartAdvertising()
        scope.advanceTimeBy(200)
        scope.runCurrent()
        verify(advertiser, times(2)).startAdvertising(any(), any(), any(), callbacks.capture())
        val current = callbacks.allValues.last()

        replaced.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
        scope.runCurrent()

        assertTrue(roleFailures.isEmpty())
        assertSame(gatt, manager.getGattServer())
        verify(advertiser, never()).stopAdvertising(current)
    }

    @Test fun `advertising stop uses the advertiser instance that started it when adapter returns null`() {
        start()
        publish()
        val started = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), started.capture())
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(null)

        manager.stop()

        verify(advertiser).stopAdvertising(started.firstValue)
    }

    @Test fun `advertising stop never targets a replacement advertiser instance`() {
        val replacementAdvertiser = mock<BluetoothLeAdvertiser>()
        start()
        publish()
        val started = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), started.capture())
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(replacementAdvertiser)

        manager.stop()

        verify(advertiser).stopAdvertising(started.firstValue)
        verify(replacementAdvertiser, never()).stopAdvertising(any<AdvertiseCallback>())
    }

    @Test fun `server stop waits for an in-flight platform advertise start then stops exact callback`() {
        val platformStartEntered = CountDownLatch(1)
        val allowPlatformStart = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val stopReturned = AtomicBoolean(false)
        beforeAdvertiseStart = {
            platformStartEntered.countDown()
            assertTrue(allowPlatformStart.await(5, TimeUnit.SECONDS))
        }
        start()

        val publishThread = thread(name = "publish-advertising") { publish() }
        assertTrue(platformStartEntered.await(5, TimeUnit.SECONDS))
        val stopThread = thread(name = "stop-advertising") {
            manager.stop()
            stopReturned.set(true)
            stopFinished.countDown()
        }
        assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))

        allowPlatformStart.countDown()
        publishThread.join(5_000)
        stopThread.join(5_000)
        assertFalse(publishThread.isAlive)
        assertFalse(stopThread.isAlive)
        assertTrue(stopReturned.get())

        val callbacks = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), callbacks.capture())
        verify(advertiser).stopAdvertising(callbacks.firstValue)
        assertNull(manager.getGattServer())
    }

    @Test fun `null open leaves server unavailable and permits a new attempt`() {
        openResult = null
        start()
        assertNull(manager.getGattServer())
        assertNull(manager.getCharacteristic())
        assertEquals(listOf("open-gatt-server" to BluetoothRoleFailureAction.RETRY_STACK), roleFailures)
        openResult = gatt
        start()
        assertSame(gatt, manager.getGattServer())
    }

    @Test fun `revoked open is contained and a later explicit start can recover`() {
        openFails = true
        start()
        assertNull(manager.getGattServer())
        assertEquals(listOf("open-gatt-server" to BluetoothRoleFailureAction.STOP_TRANSPORT), roleFailures)
        openFails = false
        start()
        assertSame(gatt, manager.getGattServer())
    }

    @Test fun `rejected service publication closes its server without advertising`() {
        addAccepted = false
        start()
        assertNull(manager.getGattServer())
        verify(gatt).close()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
        assertEquals(listOf("add-service" to BluetoothRoleFailureAction.RETRY_STACK), roleFailures)
    }

    @Test fun `revoked service publication closes its server without advertising`() {
        addFails = true
        start()
        assertNull(manager.getGattServer())
        verify(gatt).close()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
        assertEquals(listOf("add-service" to BluetoothRoleFailureAction.STOP_TRANSPORT), roleFailures)
    }

    @Test fun `unsuccessful service callback closes the generation`() {
        start()
        callback.onServiceAdded(BluetoothGatt.GATT_FAILURE, service)
        scope.runCurrent()
        assertNull(manager.getGattServer())
        verify(gatt).close()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
        assertEquals(
            listOf("service-callback-${BluetoothGatt.GATT_FAILURE}" to BluetoothRoleFailureAction.RETRY_STACK),
            roleFailures
        )
    }

    @Test fun `missing service callback fails only its generation and late callback is inert`() {
        start()

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, service)

        assertEquals(listOf("service-callback-timeout" to BluetoothRoleFailureAction.RETRY_STACK), roleFailures)
        assertNull(manager.getGattServer())
        verify(gatt).close()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `service success just before deadline wins the generation claim`() {
        start()
        scope.advanceTimeBy(4_999)

        publish()
        scope.advanceTimeBy(2)
        scope.runCurrent()

        assertTrue(roleFailures.isEmpty())
        assertSame(gatt, manager.getGattServer())
        verify(advertiser).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `missing advertise callback emits one stack recovery and late success is inert`() {
        start(); publish()
        val callbacks = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), callbacks.capture())
        val stale = callbacks.firstValue

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        stale.onStartSuccess(AdvertiseSettings.Builder().build())
        scope.advanceUntilIdle()

        assertEquals(
            listOf("advertise-callback-timeout" to BluetoothRoleFailureAction.RETRY_STACK),
            roleFailures
        )
        assertTrue(readyOwners.isEmpty())
        assertNull(manager.getGattServer())
        verify(advertiser, times(1)).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `advertise success just before deadline wins the exact attempt claim`() {
        start(); publish()
        val callbacks = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), callbacks.capture())
        val callback = callbacks.firstValue
        scope.advanceTimeBy(4_999)

        callback.onStartSuccess(AdvertiseSettings.Builder().build())
        scope.runCurrent()
        scope.advanceTimeBy(2)
        scope.runCurrent()

        assertTrue(roleFailures.isEmpty())
        assertSame(gatt, manager.getGattServer())
        verify(advertiser, never()).stopAdvertising(callback)
    }

    @Test fun `advertiser disappearing after service publication requests stack recovery`() {
        start()
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(null)

        publish()
        scope.runCurrent()

        assertEquals(listOf("advertiser-unavailable" to BluetoothRoleFailureAction.RETRY_STACK), roleFailures)
        assertNull(manager.getGattServer())
        verify(gatt).close()
    }

    @Test fun `permission revoked while checking advertise capability stops transport`() {
        start()
        whenever(adapter.isMultipleAdvertisementSupported)
            .thenThrow(SecurityException("synthetic revocation"))

        publish()
        scope.runCurrent()

        assertEquals(
            listOf("advertise-capability-permission" to BluetoothRoleFailureAction.STOP_TRANSPORT),
            roleFailures
        )
        assertNull(manager.getGattServer())
    }

    @Test fun `permission revoked while acquiring advertiser stops transport`() {
        start()
        whenever(adapter.bluetoothLeAdvertiser)
            .thenThrow(SecurityException("synthetic revocation"))

        publish()
        scope.runCurrent()

        assertEquals(
            listOf("advertise-getter-permission" to BluetoothRoleFailureAction.STOP_TRANSPORT),
            roleFailures
        )
        assertNull(manager.getGattServer())
    }

    @Test fun `unsupported advertising disables only the server role without retrying`() {
        start(); publish()
        val callback = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), callback.capture())

        callback.firstValue.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
        scope.advanceTimeBy(60_000)
        scope.runCurrent()

        assertEquals(
            listOf("advertise-unsupported" to BluetoothRoleFailureAction.DISABLE_ROLE),
            roleFailures
        )
        assertNull(manager.getGattServer())
        verify(advertiser, times(1)).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `oversized advertising data disables only the server role without retrying`() {
        start(); publish()
        val callback = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), callback.capture())

        callback.firstValue.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        scope.advanceTimeBy(60_000)
        scope.runCurrent()

        assertEquals(
            listOf("advertise-data-too-large" to BluetoothRoleFailureAction.DISABLE_ROLE),
            roleFailures
        )
        verify(advertiser, times(1)).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `synchronous advertising exception emits one stack recovery`() {
        doThrow(IllegalStateException("synthetic stack death")).whenever(advertiser)
            .startAdvertising(any(), any(), any(), any())
        start(); publish()
        scope.advanceUntilIdle()

        verify(advertiser, times(1)).startAdvertising(any(), any(), any(), any())
        assertEquals(
            listOf("advertise-start-exception" to BluetoothRoleFailureAction.RETRY_STACK),
            roleFailures
        )
    }

    @Test fun `transient advertising failure emits one manager-owned recovery`() {
        start(); publish()
        val callbacks = argumentCaptor<AdvertiseCallback>()
        verify(advertiser).startAdvertising(any(), any(), any(), callbacks.capture())
        callbacks.firstValue.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        scope.advanceUntilIdle()

        assertEquals(
            listOf("advertise-internal-error" to BluetoothRoleFailureAction.RETRY_STACK),
            roleFailures
        )
        assertNull(manager.getGattServer())
        verify(gatt).close()
    }

    @Test fun `terminal stop cancels service and advertising deadlines`() {
        start(); publish()
        manager.stop()

        scope.advanceTimeBy(60_000)
        scope.runCurrent()

        assertTrue(roleFailures.isEmpty())
    }

    @Test fun `revoked callback permission clears its links without response or readiness`() {
        start(); publish(); connect()
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)
        subscribe()
        readyTime()
        assertTrue(tracker.getConnectedDevices().isEmpty())
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(gatt, never()).sendResponse(any(), any(), any(), any(), anyOrNull())
        verify(delegate, never()).onDeviceConnected(any())
        assertNull(manager.getGattServer())
        assertEquals(
            listOf("callback-permission" to BluetoothRoleFailureAction.STOP_TRANSPORT),
            roleFailures
        )
    }

    @Test fun `denied CCCD response does not subscribe or announce ready`() {
        start(); publish(); connect()
        doThrow(SecurityException("synthetic revocation")).whenever(gatt)
            .sendResponse(any(), any(), any(), any(), anyOrNull())
        subscribe(); readyTime()
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(delegate, never()).onDeviceConnected(any())
        assertNull(manager.getGattServer())
        assertEquals(
            listOf("response-permission" to BluetoothRoleFailureAction.STOP_TRANSPORT),
            roleFailures
        )
    }

    @Test fun `false CCCD response is retryable but never a ready connection`() {
        start(); publish(); connect()
        whenever(gatt.sendResponse(any(), any(), any(), any(), anyOrNull())).thenReturn(false)
        subscribe(); readyTime()
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(delegate, never()).onDeviceConnected(any())
        whenever(gatt.sendResponse(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        subscribe(2); readyTime()
        verify(delegate).onDeviceConnected(device)
    }

    @Test fun `unsubscribed connection triggers resource admission without announcing ready`() {
        start(); publish()
        doAnswer {
            assertNotNull(tracker.getDeviceConnection(device.address))
            // A budget policy may evict immediately, before any CCCD request.
            manager.disconnectDevice(device)
            null
        }.whenever(delegate).onServerConnectionRegistered(device)
        connect()
        verify(delegate).onServerConnectionRegistered(device)
        verify(gatt).cancelConnection(device)
        readyTime()
        verify(delegate, never()).onDeviceConnected(any())
        assertTrue(tracker.getSubscribedDevices().isEmpty())
    }

    @Test fun `successful CCCD response announces ready only after subscription`() {
        start(); publish(); connect()
        verify(delegate, never()).onDeviceConnected(any())
        subscribe()
        assertEquals(listOf(device), tracker.getSubscribedDevices())
        verify(delegate, never()).onDeviceConnected(any())
        readyTime()
        verify(delegate).onDeviceConnected(device)
    }

    @Test fun `duplicate connected callback reuses one server link and disconnect leaves no ghost`() {
        start(); publish()
        connect()
        connect()

        assertEquals(1, tracker.getConnectedDeviceCount())
        verify(delegate, times(1)).onServerConnectionRegistered(device)

        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(0, tracker.getConnectedDeviceCount())
    }

    @Test fun `late connected callback cannot resurrect a server link being closed by tie break`() {
        start(); publish(); connect()
        val rejectedLink = tracker.getAllDeviceConnections().single().linkID

        // The authenticated tie-break removes the loser before Android confirms disconnect.
        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(device.address, rejectedLink))
        manager.disconnectLink(device, rejectedLink)
        connect()

        assertEquals(0, tracker.getConnectedDeviceCount())
        verify(delegate, times(1)).onServerConnectionRegistered(device)
        verify(gatt).cancelConnection(device)

        // Once the platform confirms closure, a genuinely new connection may be admitted.
        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED)
        connect()
        assertEquals(1, tracker.getConnectedDeviceCount())
        verify(delegate, times(2)).onServerConnectionRegistered(device)
    }

    @Test fun `closing tie break loser rejects packets before platform disconnect callback`() {
        start(); publish(); connect()
        val rejectedLink = tracker.getAllDeviceConnections().single().linkID
        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(device.address, rejectedLink))
        manager.disconnectLink(device, rejectedLink)
        clearInvocations(gatt, delegate)

        callback.onCharacteristicWriteRequest(
            device, 41, manager.getCharacteristic()!!, false, true, 0, packet()
        )

        verify(gatt).sendResponse(device, 41, BluetoothGatt.GATT_FAILURE, 0, null)
        verify(delegate, never()).onPacketReceived(any(), any(), anyOrNull(), any())
    }

    @Test fun `closing tie break loser rejects CCCD before platform disconnect callback`() {
        start(); publish(); connect()
        val rejectedLink = tracker.getAllDeviceConnections().single().linkID
        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(device.address, rejectedLink))
        manager.disconnectLink(device, rejectedLink)
        clearInvocations(gatt, delegate)

        subscribe(id = 42)
        readyTime()

        verify(gatt).sendResponse(device, 42, BluetoothGatt.GATT_FAILURE, 0, null)
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(delegate, never()).onDeviceConnected(any())
    }

    @Test fun `server stop releases exact link resources without logical peer disconnect`() {
        start(); publish(); connect(); subscribe(); readyTime()
        val serverLink = tracker.getAllDeviceConnections().single()
        tracker.observePeerIfCurrent(device.address, serverLink.linkID, "0011223344556677")
        tracker.addDeviceConnection(device.address,
            BluetoothConnectionTracker.DeviceConnection(
                device, isClient = true, peerID = "0011223344556677", linkID = "client-survivor"
            ))
        clearInvocations(delegate)

        manager.stop()

        verify(delegate).onLinkClosed(device.address, serverLink.linkID)
        verify(delegate, never()).onDeviceDisconnected(any(), anyOrNull(), anyOrNull())
        assertTrue(tracker.hasLink(device.address, "client-survivor"))
    }

    @Test fun `server stop reports logical disconnect when it removes the final authenticated link`() {
        start(); publish(); connect(); subscribe(); readyTime()
        val serverLink = tracker.getAllDeviceConnections().single()
        val peerID = "0011223344556677"
        assertTrue(tracker.observePeerIfCurrent(device.address, serverLink.linkID, peerID).accepted)
        clearInvocations(delegate)

        manager.stop()

        verify(delegate).onDeviceDisconnected(device, serverLink.linkID, peerID)
        verify(delegate, never()).onLinkClosed(any(), anyOrNull())
        assertEquals(0, tracker.getConnectedDeviceCount())
        assertTrue(tracker.getSubscribedDevices().isEmpty())
    }

    @Test fun `server disconnect removes subscription while a client link at the address survives`() {
        start(); publish(); connect(); subscribe(); readyTime()
        tracker.addDeviceConnection(device.address,
            BluetoothConnectionTracker.DeviceConnection(device, isClient = true, linkID = "client"))

        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED)

        assertTrue(tracker.getSubscribedDevices().isEmpty())
        assertTrue(tracker.hasLink(device.address, "client"))
        assertEquals(1, tracker.getConnectedDeviceCount())
    }

    @Test fun `CCCD disable removes subscription and cancels delayed readiness`() {
        start(); publish(); connect(); subscribe()
        subscribe(2, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        readyTime()
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(delegate, never()).onDeviceConnected(any())
    }

    @Test fun `prepared offset and invalid CCCD values cannot subscribe`() {
        start(); publish(); connect()
        callback.onDescriptorWriteRequest(device, 1, descriptor(), true, true, 0,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        callback.onDescriptorWriteRequest(device, 2, descriptor(), false, true, 1,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        subscribe(3, byteArrayOf(0x7F))
        readyTime()
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(gatt, times(3)).sendResponse(eq(device), any(), eq(BluetoothGatt.GATT_FAILURE), eq(0), isNull())
        verify(delegate, never()).onDeviceConnected(any())
    }

    @Test fun `failed packet response is not dispatched but accepted retry is`() {
        start(); publish(); connect()
        whenever(gatt.sendResponse(any(), any(), any(), any(), anyOrNull())).thenReturn(false)
        val characteristic = manager.getCharacteristic()!!
        callback.onCharacteristicWriteRequest(device, 1, characteristic, false, true, 0, packet())
        verify(delegate, never()).onPacketReceived(any(), any(), anyOrNull(), any())
        whenever(gatt.sendResponse(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        callback.onCharacteristicWriteRequest(device, 2, characteristic, false, true, 0, packet())
        verify(delegate).onPacketReceived(any(), any(), eq(device), any())
    }

    @Test fun `malformed or offset packets get failure without dispatch`() {
        start(); publish(); connect()
        val characteristic = manager.getCharacteristic()!!
        callback.onCharacteristicWriteRequest(device, 1, characteristic, false, true, 0, byteArrayOf(1))
        callback.onCharacteristicWriteRequest(device, 2, characteristic, false, true, 1, packet())
        verify(gatt, times(2)).sendResponse(eq(device), any(), eq(BluetoothGatt.GATT_FAILURE), eq(0), isNull())
        verify(delegate, never()).onPacketReceived(any(), any(), anyOrNull(), any())
    }

    @Test fun `callbacks and delayed readiness from stopped generation cannot affect restart`() {
        start(); publish(); connect(); subscribe()
        val oldCallback = callback
        val oldDescriptor = descriptor()
        manager.stop()
        start(); publish()
        oldCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        oldCallback.onDescriptorWriteRequest(device, 7, oldDescriptor, false, true, 0,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        oldCallback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        readyTime()
        assertTrue(tracker.getConnectedDevices().isEmpty())
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(delegate, never()).onDeviceConnected(any())
        verify(delegate, never()).onGattServerNotificationComplete(any(), anyOrNull(), any())
    }

    @Test fun `stop during open prevents adoption and closes the returned server`() {
        duringOpen = { manager.stop() }
        start()
        assertNull(manager.getGattServer())
        assertNull(manager.getCharacteristic())
        verify(gatt).close()
        verify(gatt, never()).addService(any())
    }
}
