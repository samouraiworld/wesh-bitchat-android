package com.bitchat.android.mesh

import android.bluetooth.*
import android.content.Context
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import android.os.Build
import com.bitchat.android.util.AppConstants
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.ui.debug.DebugScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35])
class BluetoothGattClientCallbacksTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val scope = TestScope()
    private var scanTime = 1_000_000L
    private val scanCallbacks = mutableListOf<ScanCallback>()
    private val scanner = mock<BluetoothLeScanner>()
    private val adapter = mock<BluetoothAdapter>()
    private val power = mock<PowerManager> {
        on { profile }.thenReturn(MutableStateFlow(PowerProfileResolver.resolve(100, false, false, false)))
        on { getScanSettings() }.thenReturn(ScanSettings.Builder().build())
    }
    private val manager = mock<BluetoothManager> { on { adapter }.thenReturn(this@BluetoothGattClientCallbacksTest.adapter) }
    private val context = mock<Context> { on { getSystemService(Context.BLUETOOTH_SERVICE) }.thenReturn(manager) }
    private val permissions = mock<BluetoothPermissionManager> { on { hasBluetoothPermissions() }.thenReturn(true) }
    private val tracker = BluetoothConnectionTracker(scope, mock(), LOCAL_PEER_ID) { scanTime }
    private val delegate = mock<BluetoothConnectionManagerDelegate>()
    private val device = mock<BluetoothDevice> { on { address }.thenReturn(this@BluetoothGattClientCallbacksTest.address) }
    private val gatt = mock<BluetoothGatt>()
    private val service = mock<BluetoothGattService>()
    private val characteristic = BluetoothGattCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID, 0, 0)
    private val descriptor = BluetoothGattDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID, 0)
    private val callbacks = mutableListOf<BluetoothGattCallback>()
    private val roleFailures = mutableListOf<Triple<Long, String, BluetoothRoleFailureAction>>()
    private val client: BluetoothGattClientManager

    init {
        doAnswer {
            scanCallbacks += it.getArgument<ScanCallback>(2)
            null
        }.whenever(scanner).startScan(any(), any(), any<ScanCallback>())
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.bluetoothLeScanner).thenReturn(scanner)
        characteristic.addDescriptor(descriptor)
        whenever(adapter.getRemoteDevice(address)).thenReturn(device)
        whenever(device.connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))).thenAnswer {
            callbacks += it.getArgument<BluetoothGattCallback>(2)
            gatt
        }
        whenever(gatt.requestMtu(517)).thenReturn(true)
        whenever(gatt.discoverServices()).thenReturn(true)
        whenever(gatt.getService(AppConstants.Mesh.Gatt.SERVICE_UUID)).thenReturn(service)
        whenever(service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)).thenReturn(characteristic)
        whenever(gatt.setCharacteristicNotification(characteristic, true)).thenReturn(true)
        whenever(gatt.writeDescriptor(descriptor)).thenReturn(true)
        if (Build.VERSION.SDK_INT >= 33) {
            whenever(gatt.writeDescriptor(eq(descriptor), any())).thenReturn(BluetoothStatusCodes.SUCCESS)
        }
        client = BluetoothGattClientManager(
            context,
            scope,
            tracker,
            permissions,
            power,
            delegate,
            roleFailure = { owner, reason, action -> roleFailures += Triple(owner, reason, action) },
            elapsedRealtimeMillis = { scanTime }
        )
    }

    private fun clientForSdk(
        sdk: Int,
        locationEnabled: () -> Boolean = { true },
        beforeScanPublication: (() -> Unit)? = null,
        afterScanCallbackPreflight: (() -> Unit)? = null,
        afterScanScheduleOwnerCheck: ((Boolean) -> Unit)? = null,
        onRoleFailure: (Long, String, BluetoothRoleFailureAction) -> Unit = { owner, reason, action ->
            roleFailures += Triple(owner, reason, action)
        },
        scanWindowWakeLock: ScanWindowWakeLock = RecordingWakeLock(),
        clock: () -> Long = { scanTime }
    ) = BluetoothGattClientManager(
        context = context,
        connectionScope = scope,
        connectionTracker = tracker,
        permissionManager = permissions,
        powerManager = power,
        delegate = delegate,
        platformSdkInt = sdk,
        legacyLocationEnabledProvider = locationEnabled,
        beforeScanPublication = beforeScanPublication,
        afterScanCallbackPreflight = afterScanCallbackPreflight,
        afterScanScheduleOwnerCheck = afterScanScheduleOwnerCheck,
        roleFailure = onRoleFailure,
        elapsedRealtimeMillis = clock,
        scanWindowWakeLock = scanWindowWakeLock
    )

    private class RecordingWakeLock : ScanWindowWakeLock {
        class Window { var held = true }
        val events = mutableListOf<String>()
        val windows = mutableListOf<Window>()
        val held get() = windows.any { it.held }
        override fun acquire(timeoutMs: Long): ScanWindowWakeLock.Held {
            events += "acquire:$timeoutMs"
            val window = Window().also { windows += it }
            return ScanWindowWakeLock.Held {
                if (window.held) events += "release"
                window.held = false
            }
        }
    }

    @Test fun `a replaced duty schedule releases its own window and leaves the new one held`() {
        whenever(power.profile).thenReturn(MutableStateFlow(PowerProfileResolver.resolve(100, false, true, false)))
        val wake = RecordingWakeLock()
        val modern = clientForSdk(Build.VERSION_CODES.S, scanWindowWakeLock = wake)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            assertEquals(1, wake.windows.size)

            modern.applyPowerProfile(PowerProfileResolver.resolve(100, false, true, true))
            scope.runCurrent()
            assertEquals(2, wake.windows.size)
            assertFalse("the replaced window is released", wake.windows[0].held)
            assertTrue("the new window stays held", wake.windows[1].held)
        } finally {
            modern.stop()
        }
        scope.runCurrent()
        assertFalse(wake.held)
    }

    @Test fun `background duty window holds a bounded wake lock until its scan stops`() {
        whenever(power.profile).thenReturn(MutableStateFlow(PowerProfileResolver.resolve(100, false, true, false)))
        val wake = RecordingWakeLock()
        val modern = clientForSdk(Build.VERSION_CODES.S, scanWindowWakeLock = wake)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            val first = scanCallbacks.single()
            assertTrue("the one-second scan window must keep the CPU awake", wake.held)
            assertEquals(listOf("acquire:6000"), wake.events)

            scope.advanceTimeBy(1_000)
            scope.runCurrent()
            verify(scanner).stopScan(first)
            assertFalse("the off window must not hold the CPU", wake.held)

            scope.advanceTimeBy(30_000)
            scope.runCurrent()
            assertEquals(listOf("acquire:6000", "release"), wake.events)
        } finally {
            modern.stop()
        }
    }

    @Test fun `stopping inside a duty window releases its wake lock`() {
        whenever(power.profile).thenReturn(MutableStateFlow(PowerProfileResolver.resolve(100, false, true, false)))
        val wake = RecordingWakeLock()
        val modern = clientForSdk(Build.VERSION_CODES.S, scanWindowWakeLock = wake)
        assertTrue(modern.start())
        scope.runCurrent()
        assertTrue(wake.held)

        modern.stop()
        scope.runCurrent()
        assertFalse(wake.held)
    }

    @Test fun `continuous scanning never takes the scan window wake lock`() {
        whenever(power.profile).thenReturn(MutableStateFlow(PowerProfileResolver.resolve(100, true, false, false)))
        val wake = RecordingWakeLock()
        val modern = clientForSdk(Build.VERSION_CODES.S, scanWindowWakeLock = wake)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            scope.advanceTimeBy(60_000)
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
            assertTrue(wake.events.isEmpty())
        } finally {
            modern.stop()
        }
    }

    @Test fun `scan starts stay below the Android limit of five per thirty seconds`() {
        val fastDuty = PowerProfileResolver.resolve(100, false, false, false).copy(
            ble = PowerManager.BleSchedule(scanOnMs = 1_000L, scanOffMs = 4_000L, continuousScan = false)
        )
        whenever(power.profile).thenReturn(MutableStateFlow(fastDuty))
        val modern = clientForSdk(
            Build.VERSION_CODES.S,
            clock = { 1_000_000L + scope.testScheduler.currentTime }
        )
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            scope.advanceTimeBy(29_999)
            scope.runCurrent()
            assertEquals(4, scanCallbacks.size)
        } finally {
            modern.stop()
        }
    }

    @After fun tearDown() { client.stop(); scope.cancel() }

    private fun connect(): BluetoothGattCallback {
        assertTrue(client.connectToAddress(address))
        return callbacks.last()
    }
    private fun mtu(callback: BluetoothGattCallback) {
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        scope.advanceTimeBy(200)
        scope.runCurrent()
    }
    private fun discover(callback: BluetoothGattCallback) {
        mtu(callback)
        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
    }
    private fun subscribe(callback: BluetoothGattCallback) {
        discover(callback)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
    }
    private fun assertRejected() {
        assertNull(tracker.getDeviceConnection(address))
        assertTrue(tracker.isConnectionAttemptAllowed(address))
        verify(delegate, never()).onDeviceConnected(any())
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    private fun successfulScanResult(rssi: Int = -30): ScanResult {
        val record = mock<ScanRecord> {
            on { serviceUuids }.thenReturn(listOf(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)))
        }
        return mock {
            on { device }.thenReturn(this@BluetoothGattClientCallbacksTest.device)
            on { getRssi() }.thenReturn(rssi)
            on { scanRecord }.thenReturn(record)
        }
    }

    private fun deliverAfterInvalidationBarrier(
        callback: ScanCallback,
        result: ScanResult,
        admitted: CountDownLatch,
        resume: CountDownLatch,
        invalidate: () -> Unit
    ) {
        val failure = AtomicReference<Throwable?>()
        val delivery = thread(name = "synthetic-scan-delivery") {
            try {
                callback.onScanResult(0, result)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        assertTrue("scan callback did not reach admission barrier", admitted.await(5, TimeUnit.SECONDS))
        invalidate()
        resume.countDown()
        delivery.join(5_000)
        assertFalse("scan callback remained blocked", delivery.isAlive)
        failure.get()?.let { throw AssertionError("scan callback failed", it) }
    }

    @Test fun `start reports unavailable scanner until the stack resource returns`() {
        whenever(adapter.bluetoothLeScanner).thenReturn(null)
        assertEquals(BluetoothRoleStartResult.RESOURCE_UNAVAILABLE, client.startResult())

        whenever(adapter.bluetoothLeScanner).thenReturn(scanner)
        assertEquals(BluetoothRoleStartResult.STARTED, client.startResult())
    }

    @Test fun `denied permission rejects address connection before device lookup`() {
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)
        assertFalse(client.connectToAddress(address))
        verify(adapter, never()).getRemoteDevice(any<String>())
    }
    @Test fun `null framework connection rejects acceptance and releases pending attempt`() {
        whenever(device.connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))).thenReturn(null)
        assertFalse(client.connectToAddress(address))
        assertTrue(tracker.isConnectionAttemptAllowed(address))
    }
    @Test fun `revocation while starting rejects acceptance and releases pending attempt`() {
        whenever(device.connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE)))
            .thenThrow(SecurityException("synthetic revocation"))
        assertFalse(client.connectToAddress(address))
        assertTrue(tracker.isConnectionAttemptAllowed(address))
    }
    @Test fun `duplicate address connection reports refusal`() {
        connect()
        assertFalse(client.connectToAddress(address))
        verify(device).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
    }
    @Test fun `MTU request refusal releases attempt`() {
        whenever(gatt.requestMtu(517)).thenReturn(false)
        mtu(connect())
        assertRejected()
    }
    @Test fun `permission revocation before delayed MTU terminates without framework call`() {
        val callback = connect()
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)
        mtu(callback)
        verify(gatt, never()).requestMtu(any())
        assertRejected()
    }
    @Test fun `MTU request revocation race terminates attempt`() {
        whenever(gatt.requestMtu(517)).thenThrow(SecurityException("synthetic revocation"))
        mtu(connect())
        assertRejected()
    }
    @Test fun `failed MTU callback cannot create connected tracker state`() {
        val callback = connect()
        mtu(callback)
        callback.onMtuChanged(gatt, 23, BluetoothGatt.GATT_FAILURE)
        verify(gatt, never()).discoverServices()
        assertRejected()
    }
    @Test fun `service discovery refusal cleans registered connection`() {
        whenever(gatt.discoverServices()).thenReturn(false)
        discover(connect())
        assertRejected()
    }
    @Test fun `service discovery revocation race cleans registered connection`() {
        whenever(gatt.discoverServices()).thenThrow(SecurityException("synthetic revocation"))
        discover(connect())
        assertRejected()
    }
    @Test fun `notification refusal prevents descriptor write and readiness`() {
        whenever(gatt.setCharacteristicNotification(characteristic, true)).thenReturn(false)
        subscribe(connect())
        verify(gatt, never()).writeDescriptor(descriptor)
        if (Build.VERSION.SDK_INT >= 33) verify(gatt, never()).writeDescriptor(eq(descriptor), any())
        assertRejected()
    }
    @Test fun `descriptor write refusal does not announce readiness`() {
        whenever(gatt.writeDescriptor(descriptor)).thenReturn(false)
        if (Build.VERSION.SDK_INT >= 33) {
            whenever(gatt.writeDescriptor(eq(descriptor), any())).thenReturn(BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION)
        }
        subscribe(connect())
        assertRejected()
    }
    @Test fun `descriptor write revocation race does not announce readiness`() {
        whenever(gatt.writeDescriptor(descriptor)).thenThrow(SecurityException("synthetic revocation"))
        if (Build.VERSION.SDK_INT >= 33) {
            whenever(gatt.writeDescriptor(eq(descriptor), any())).thenThrow(SecurityException("synthetic revocation"))
        }
        subscribe(connect())
        assertRejected()
    }
    @Test fun `readiness waits for matching successful descriptor acknowledgement only once`() {
        val callback = connect()
        subscribe(callback)
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertNull(tracker.getDeviceConnection(address)?.characteristic)
        verify(delegate, never()).onDeviceConnected(any())
        callback.onDescriptorWrite(gatt, BluetoothGattDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID, 0), BluetoothGatt.GATT_SUCCESS)
        verify(delegate, never()).onDeviceConnected(any())
        callback.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        callback.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        verify(delegate).onDeviceConnected(device)
        assertSame(characteristic, tracker.getDeviceConnection(address)?.characteristic)
    }
    @Test fun `failed descriptor acknowledgement cleans connection`() {
        val callback = connect()
        subscribe(callback)
        callback.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_FAILURE)
        assertRejected()
    }
    @Test fun `permission denied at descriptor acknowledgement cannot announce readiness`() {
        val callback = connect()
        subscribe(callback)
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)
        callback.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        assertRejected()
    }
    @Test fun `connection error while connecting terminates attempt and backs off`() {
        val callback = connect()
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_CONNECTING)
        assertNull(tracker.getDeviceConnection(address))
        verify(delegate, never()).onDeviceConnected(any())
        verify(gatt).disconnect()
        verify(gatt).close()
        // The failed attempt stays on record: the next scan result cannot retry at once.
        assertFalse(tracker.isConnectionAttemptAllowed(address))
        assertFalse(client.connectToAddress(address))
    }
    @Test fun `missing setup callbacks timeout and release pending attempt`() {
        connect()
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        assertRejected()
    }
    @Test fun `stop releases unregistered GATT and ignores delayed MTU and old callbacks`() {
        val old = connect()
        old.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        client.stop()
        scope.advanceTimeBy(200)
        scope.runCurrent()
        verify(gatt, never()).requestMtu(any())
        old.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        old.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRejected()
    }
    @Test fun `old callback generation cannot overwrite or disconnect replacement even with reused GATT object`() {
        val old = connect()
        discover(old)
        old.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        val current = connect()
        subscribe(current)
        current.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        val replacement = tracker.getDeviceConnection(address)
        old.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        old.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        old.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        old.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        old.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertSame(replacement, tracker.getDeviceConnection(address))
        verify(delegate).onDeviceConnected(device)
        verify(delegate, never()).onGattClientWriteComplete(any(), any(), any())
        verify(gatt).close()
    }

    @Test fun `failed service callback prevents subscription and readiness`() {
        val callback = connect()
        discover(callback)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
        verify(gatt, never()).setCharacteristicNotification(any(), any())
        assertRejected()
    }
    @Test fun `notification setup revocation race terminates connection`() {
        whenever(gatt.setCharacteristicNotification(characteristic, true)).thenThrow(SecurityException("synthetic revocation"))
        subscribe(connect())
        assertRejected()
    }
    @Test fun `missing descriptor is terminal and cannot announce readiness`() {
        val noDescriptor = BluetoothGattCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID, 0, 0)
        whenever(service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)).thenReturn(noDescriptor)
        subscribe(connect())
        assertRejected()
    }
    @Test fun `descriptor timeout prevents late acknowledgement from announcing readiness`() {
        val callback = connect()
        subscribe(callback)
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        callback.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        assertRejected()
    }
    @Test fun `stop attempts close even when disconnect permission is revoked`() {
        connect()
        doThrow(SecurityException("synthetic revocation")).whenever(gatt).disconnect()
        client.stop()
        client.stop()
        assertRejected()
    }
    @Test fun `late callback cannot mutate an independently replaced tracker generation`() {
        val callback = connect()
        discover(callback)
        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(address, tracker.getCurrentLinkID(address)!!))
        val replacement = BluetoothConnectionTracker.DeviceConnection(device, linkID = "synthetic-new-link")
        tracker.addDeviceConnection(address, replacement)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertSame(replacement, tracker.getDeviceConnection(address))
        verify(delegate, never()).onDeviceConnected(any())
        verify(delegate, never()).onDeviceDisconnected(any(), any(), anyOrNull())
        verify(gatt).close()
    }
    @Test fun `own link reported to the local server first does not stop the attempt`() {
        val callback = connect()
        tracker.addDeviceConnection(address,
            BluetoothConnectionTracker.DeviceConnection(device, isClient = false, linkID = "synthetic-server-link"))
        mtu(callback)
        verify(gatt).requestMtu(517)
        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        assertEquals(2, tracker.getConnectedDeviceCount())
        assertTrue(tracker.getAllDeviceConnections().any { it.isClient })
        assertTrue(tracker.getAllDeviceConnections().any { !it.isClient })
        verify(gatt, never()).close()
    }
    @Test fun `denied close does not retain attempt or prevent repeated stop`() {
        connect()
        doThrow(SecurityException("synthetic revocation")).whenever(gatt).close()
        client.stop()
        client.stop()
        assertRejected()
    }

    @Test fun `active stop synchronously stops old scan and cannot disconnect a new attempt when queued work drains`() {
        assertTrue(client.start())
        scope.runCurrent()
        val old = connect()
        discover(old)
        client.stop()
        verify(scanner).stopScan(any<ScanCallback>())
        clearInvocations(gatt, scanner)
        assertTrue(client.start())
        val current = connect()
        // A Binder callback can arrive before the coroutine dispatcher drains queued stop/start work.
        current.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        current.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        val replacement = tracker.getDeviceConnection(address)
        scope.runCurrent()
        assertSame(replacement, tracker.getDeviceConnection(address))
        verify(gatt, never()).disconnect()
        verify(gatt, never()).close()
        verify(scanner, never()).stopScan(any<ScanCallback>())
    }
    @Test fun `revoked scan stop is attempted once and clears old scan state for restart`() {
        assertTrue(client.start())
        scope.runCurrent()
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)
        doThrow(SecurityException("synthetic revocation")).whenever(scanner).stopScan(any<ScanCallback>())
        client.stop()
        client.stop()
        verify(scanner).stopScan(any<ScanCallback>())
        whenever(permissions.hasBluetoothPermissions()).thenReturn(true)
        assertTrue(client.start())
        scope.runCurrent()
        assertTrue(client.connectToAddress(address))
    }

    @Test fun `scan stop uses the scanner instance that started the generation when adapter returns null`() {
        assertTrue(client.start())
        scope.runCurrent()
        val callback = scanCallbacks.single()
        whenever(adapter.bluetoothLeScanner).thenReturn(null)

        client.stop()

        verify(scanner).stopScan(callback)
    }

    @Test fun `scan stop never targets a replacement scanner instance`() {
        val replacementScanner = mock<BluetoothLeScanner>()
        assertTrue(client.start())
        scope.runCurrent()
        val callback = scanCallbacks.single()
        whenever(adapter.bluetoothLeScanner).thenReturn(replacementScanner)

        client.stop()

        verify(scanner).stopScan(callback)
        verify(replacementScanner, never()).stopScan(any<ScanCallback>())
    }

    @Test fun `Android 11 initial location read fails closed and first on transition starts once`() {
        var locationEnabled = false
        val legacy = clientForSdk(Build.VERSION_CODES.R, locationEnabled = { locationEnabled })
        try {
            assertEquals(BluetoothRoleStartResult.STARTED, legacy.startResult(ownerGeneration = 41))
            scope.runCurrent()
            assertTrue(scanCallbacks.isEmpty())

            locationEnabled = true
            legacy.refreshLegacyLocationState()
            scope.advanceTimeBy(500)
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)

            legacy.refreshLegacyLocationState()
            scope.advanceTimeBy(1_000)
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
        } finally {
            legacy.stop()
        }

        val unreadable = clientForSdk(
            Build.VERSION_CODES.R,
            locationEnabled = { error("synthetic provider failure") }
        )
        try {
            assertTrue(unreadable.start())
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
        } finally {
            unreadable.stop()
        }
    }

    @Test fun `location broadcast racing initial read wins over stale provider value`() {
        lateinit var legacy: BluetoothGattClientManager
        legacy = clientForSdk(Build.VERSION_CODES.R, locationEnabled = {
            legacy.onLegacyLocationChanged(false)
            true
        })
        try {
            assertTrue(legacy.start())
            scope.runCurrent()
            assertTrue(scanCallbacks.isEmpty())
        } finally {
            legacy.stop()
        }
    }

    @Test fun `Android 11 location off preserves desire and on creates one exact generation`() {
        val legacy = clientForSdk(Build.VERSION_CODES.R)
        try {
            assertTrue(legacy.start())
            scope.runCurrent()
            val first = scanCallbacks.single()

            legacy.onLegacyLocationChanged(false)
            legacy.onLegacyLocationChanged(false)
            verify(scanner, times(1)).stopScan(first)

            scanTime += 5_000
            legacy.onLegacyLocationChanged(true)
            scope.advanceTimeBy(500)
            scope.runCurrent()
            assertEquals(2, scanCallbacks.size)

            legacy.onLegacyLocationChanged(true)
            scope.advanceTimeBy(1_000)
            scope.runCurrent()
            assertEquals(2, scanCallbacks.size)
        } finally {
            legacy.stop()
        }
    }

    @Test fun `Android 11 location off between precheck and publication prevents scan`() {
        lateinit var legacy: BluetoothGattClientManager
        legacy = clientForSdk(
            sdk = Build.VERSION_CODES.R,
            beforeScanPublication = { legacy.onLegacyLocationChanged(false) }
        )
        try {
            assertTrue(legacy.start())
            scope.runCurrent()
            assertTrue(scanCallbacks.isEmpty())
            verify(scanner, never()).startScan(any(), any(), any<ScanCallback>())
        } finally {
            legacy.stop()
        }
    }

    @Test fun `location off fences a pending transient retry before one on transition`() {
        val legacy = clientForSdk(Build.VERSION_CODES.R)
        try {
            assertTrue(legacy.start())
            scope.runCurrent()
            scanCallbacks.single().onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

            legacy.onLegacyLocationChanged(false)
            scanTime += 5_000
            scope.advanceTimeBy(5_000)
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)

            legacy.onLegacyLocationChanged(true)
            scope.advanceTimeBy(500)
            scope.runCurrent()
            assertEquals(2, scanCallbacks.size)
        } finally {
            legacy.stop()
        }
    }

    @Test fun `location off atomically rejects a callback that passed scan preflight`() {
        val admitted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val legacy = clientForSdk(
            Build.VERSION_CODES.R,
            afterScanCallbackPreflight = {
                admitted.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
        )
        try {
            assertTrue(legacy.start())
            scope.runCurrent()
            deliverAfterInvalidationBarrier(
                scanCallbacks.single(), successfulScanResult(), admitted, resume
            ) { legacy.onLegacyLocationChanged(false) }

            verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
            assertTrue(tracker.isConnectionAttemptAllowed(address))
            assertTrue(callbacks.isEmpty())
        } finally {
            resume.countDown()
            legacy.stop()
        }
    }

    @Test fun `unsupported atomically rejects a callback that passed scan preflight`() {
        val admitted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val modern = clientForSdk(
            Build.VERSION_CODES.S,
            afterScanCallbackPreflight = {
                admitted.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
        )
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            val scan = scanCallbacks.single()
            deliverAfterInvalidationBarrier(scan, successfulScanResult(), admitted, resume) {
                scan.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            }
            scope.runCurrent()

            verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
            assertTrue(tracker.isConnectionAttemptAllowed(address))
            assertTrue(callbacks.isEmpty())
        } finally {
            resume.countDown()
            modern.stop()
        }
    }

    @Test fun `stop atomically rejects a callback that passed scan preflight`() {
        val admitted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val modern = clientForSdk(
            Build.VERSION_CODES.S,
            afterScanCallbackPreflight = {
                admitted.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
        )
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            deliverAfterInvalidationBarrier(
                scanCallbacks.single(), successfulScanResult(), admitted, resume
            ) { modern.stop() }

            verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
            assertTrue(tracker.isConnectionAttemptAllowed(address))
            assertTrue(callbacks.isEmpty())
        } finally {
            resume.countDown()
            modern.stop()
        }
    }

    @Test fun `Android 12 ignores legacy location provider and broadcasts`() {
        var providerReads = 0
        val modern = clientForSdk(Build.VERSION_CODES.S, locationEnabled = {
            providerReads++
            false
        })
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            assertEquals(0, providerReads)
            assertEquals(1, scanCallbacks.size)

            modern.onLegacyLocationChanged(false)
            modern.refreshLegacyLocationState()
            assertEquals(0, providerReads)
            verify(scanner, never()).stopScan(any<ScanCallback>())
        } finally {
            modern.stop()
        }
    }

    @Test fun `unsupported scan is permanent preserves established GATT and reports owner outside scan lock`() {
        var callbackReturned = false
        lateinit var unsupported: BluetoothGattClientManager
        unsupported = clientForSdk(Build.VERSION_CODES.S, onRoleFailure = { owner, reason, action ->
            unsupported.onScanStateChanged(false)
            roleFailures += Triple(owner, reason, action)
            callbackReturned = true
        })
        try {
            assertEquals(BluetoothRoleStartResult.STARTED, unsupported.startResult(ownerGeneration = 44))
            scope.runCurrent()
            val scan = scanCallbacks.single()
            assertTrue(unsupported.connectToAddress(address))
            val attempt = callbacks.last()
            subscribe(attempt)
            attempt.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
            val established = tracker.getDeviceConnection(address)
            clearInvocations(gatt)

            scan.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            assertFalse(callbackReturned)
            scope.runCurrent()

            assertTrue(callbackReturned)
            assertSame(established, tracker.getDeviceConnection(address))
            verify(gatt, never()).disconnect()
            verify(gatt, never()).close()
            assertEquals(
                listOf(Triple(44L, "scan-feature-unsupported", BluetoothRoleFailureAction.DISABLE_CLIENT_SCAN)),
                roleFailures
            )

            scanTime += 600_000
            unsupported.stop()
            assertEquals(BluetoothRoleStartResult.STARTED, unsupported.startResult(ownerGeneration = 45))
            scope.advanceTimeBy(600_000)
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
        } finally {
            unsupported.stop()
        }
    }

    @Test fun `synchronous unsupported callback defers manager notification beyond platform start`() {
        var insidePlatformStart = false
        lateinit var synchronous: BluetoothGattClientManager
        synchronous = clientForSdk(Build.VERSION_CODES.S, onRoleFailure = { owner, reason, action ->
            assertFalse(insidePlatformStart)
            synchronous.onScanStateChanged(false)
            roleFailures += Triple(owner, reason, action)
        })
        doAnswer {
            val callback = it.getArgument<ScanCallback>(2)
            scanCallbacks += callback
            insidePlatformStart = true
            callback.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            assertTrue(roleFailures.isEmpty())
            insidePlatformStart = false
            null
        }.whenever(scanner).startScan(any(), any(), any<ScanCallback>())

        try {
            assertEquals(BluetoothRoleStartResult.STARTED, synchronous.startResult(ownerGeneration = 48))
            scope.runCurrent()
            assertEquals(
                listOf(Triple(48L, "scan-feature-unsupported", BluetoothRoleFailureAction.DISABLE_CLIENT_SCAN)),
                roleFailures
            )
        } finally {
            synchronous.stop()
        }
    }

    @Test fun `stale unsupported callback cannot disable replacement scan generation`() {
        val modern = clientForSdk(Build.VERSION_CODES.S)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            val stale = scanCallbacks.single()
            modern.stop()
            scanTime += 5_000
            assertEquals(BluetoothRoleStartResult.STARTED, modern.startResult(ownerGeneration = 52))
            scope.runCurrent()
            assertEquals(2, scanCallbacks.size)

            stale.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            scope.runCurrent()

            assertTrue(roleFailures.isEmpty())
            verify(scanner, never()).stopScan(scanCallbacks.last())
        } finally {
            modern.stop()
        }
    }

    @Test fun `continuous watchdog recycles a scan with zero first results at 120 seconds`() {
        whenever(power.profile).thenReturn(
            MutableStateFlow(PowerProfileResolver.resolve(100, true, false, false))
        )
        val modern = clientForSdk(Build.VERSION_CODES.S)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            val first = scanCallbacks.single()

            repeat(4) {
                scanTime += 30_000
                scope.advanceTimeBy(30_000)
                scope.runCurrent()
            }
            scope.advanceTimeBy(500)
            scope.runCurrent()

            assertEquals(2, scanCallbacks.size)
            verify(scanner).stopScan(first)
        } finally {
            modern.stop()
        }
    }

    @Test fun `monotonic clock near device boot does not delay the first scan`() {
        scanTime = 0L
        val modern = clientForSdk(Build.VERSION_CODES.S)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
        } finally {
            modern.stop()
        }
    }

    @Test fun `a recent scan result postpones the continuous watchdog deadline`() {
        whenever(power.profile).thenReturn(
            MutableStateFlow(PowerProfileResolver.resolve(100, true, false, false))
        )
        val modern = clientForSdk(Build.VERSION_CODES.S)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            val scan = scanCallbacks.single()

            repeat(3) {
                scanTime += 30_000
                scope.advanceTimeBy(30_000)
                scope.runCurrent()
            }
            val record = mock<ScanRecord> {
                on { serviceUuids }.thenReturn(listOf(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)))
            }
            val result = mock<ScanResult> {
                on { device }.thenReturn(this@BluetoothGattClientCallbacksTest.device)
                on { rssi }.thenReturn(-95)
                on { scanRecord }.thenReturn(record)
            }
            scan.onScanResult(0, result)

            repeat(3) {
                scanTime += 30_000
                scope.advanceTimeBy(30_000)
                scope.runCurrent()
            }

            assertEquals(1, scanCallbacks.size)
            verify(scanner, never()).stopScan(scan)
        } finally {
            modern.stop()
        }
    }

    @Test fun `watchdog uses monotonic time instead of scheduler or wall clock passage`() {
        whenever(power.profile).thenReturn(
            MutableStateFlow(PowerProfileResolver.resolve(100, true, false, false))
        )
        val modern = clientForSdk(Build.VERSION_CODES.S)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            val first = scanCallbacks.single()

            // Scheduler time (and, in production, wall-clock time) may jump independently. The
            // injected elapsed-realtime clock remains authoritative for the 120 second deadline.
            scope.advanceTimeBy(300_000)
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
            verify(scanner, never()).stopScan(first)

            scanTime += 120_000
            scope.advanceTimeBy(30_000)
            scope.runCurrent()
            scope.advanceTimeBy(500)
            scope.runCurrent()
            assertEquals(2, scanCallbacks.size)
            verify(scanner).stopScan(first)
        } finally {
            modern.stop()
        }
    }

    @Test fun `replaced duty owner cannot turn off a newer continuous schedule`() {
        val dutyProfile = PowerProfileResolver.resolve(100, false, false, false).copy(
            ble = PowerManager.BleSchedule(scanOnMs = 1_000L, scanOffMs = 180_000L, continuousScan = false)
        )
        val continuousProfile = PowerProfileResolver.resolve(100, true, false, false)
        whenever(power.profile).thenReturn(MutableStateFlow(dutyProfile))
        val replacementAttempted = CountDownLatch(1)
        val replacementCompleted = CountDownLatch(1)
        var replacementThread: Thread? = null
        lateinit var modern: BluetoothGattClientManager
        modern = clientForSdk(
            Build.VERSION_CODES.S,
            afterScanScheduleOwnerCheck = { shouldScan ->
                if (!shouldScan && replacementThread == null) {
                    replacementThread = thread(name = "synthetic-profile-replacement") {
                        replacementAttempted.countDown()
                        modern.applyPowerProfile(continuousProfile)
                        replacementCompleted.countDown()
                    }
                    assertTrue(replacementAttempted.await(5, TimeUnit.SECONDS))
                    assertFalse(
                        "replacement must wait for the atomic owner mutation",
                        replacementCompleted.await(100, TimeUnit.MILLISECONDS)
                    )
                }
            }
        )
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
            val first = scanCallbacks.single()

            scanTime += 5_000
            scope.advanceTimeBy(1_000)
            scope.runCurrent()
            replacementThread?.join(5_000)
            assertTrue(replacementCompleted.await(5, TimeUnit.SECONDS))
            scope.runCurrent()
            assertEquals(2, scanCallbacks.size)
            val replacement = scanCallbacks.last()
            verify(scanner).stopScan(first)

            scope.advanceTimeBy(180_000)
            scope.runCurrent()
            verify(scanner, never()).stopScan(replacement)
        } finally {
            modern.stop()
        }
    }

    @Test fun `unsupported scan rejects all later profile schedules without wakeup or retry`() {
        val continuousProfile = PowerProfileResolver.resolve(100, true, false, false)
        val dutyProfile = continuousProfile.copy(
            ble = PowerManager.BleSchedule(scanOnMs = 1_000L, scanOffMs = 1_000L, continuousScan = false)
        )
        val modern = clientForSdk(Build.VERSION_CODES.S)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            val failed = scanCallbacks.single()
            failed.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            scope.runCurrent()
            clearInvocations(scanner)

            modern.applyPowerProfile(dutyProfile)
            modern.applyPowerProfile(continuousProfile)
            scanTime += 600_000
            scope.advanceTimeBy(600_000)
            scope.runCurrent()

            assertEquals(1, scanCallbacks.size)
            verify(scanner, never()).startScan(any(), any(), any<ScanCallback>())
            verify(scanner, never()).stopScan(any<ScanCallback>())
        } finally {
            modern.stop()
        }
    }

    @Test fun `watchdog does not run during an intentional long duty cycle off window`() {
        val dutyProfile = PowerProfileResolver.resolve(100, false, false, false).copy(
            ble = PowerManager.BleSchedule(scanOnMs = 1_000L, scanOffMs = 180_000L, continuousScan = false)
        )
        whenever(power.profile).thenReturn(MutableStateFlow(dutyProfile))
        val modern = clientForSdk(Build.VERSION_CODES.S)
        try {
            assertTrue(modern.start())
            scope.runCurrent()
            assertEquals(1, scanCallbacks.size)
            scope.advanceTimeBy(1_000)
            scope.runCurrent()
            clearInvocations(scanner)

            scanTime += 120_000
            scope.advanceTimeBy(120_000)
            scope.runCurrent()

            assertEquals(1, scanCallbacks.size)
            verify(scanner, never()).startScan(any(), any(), any<ScanCallback>())
        } finally {
            modern.stop()
        }
    }

    @Test fun `old scan results batches and failures cannot act on a restarted scanner`() {
        assertTrue(client.start())
        scope.runCurrent()
        val oldScan = scanCallbacks.single()
        client.stop()
        scanTime += 5_000
        assertTrue(client.start())
        scope.runCurrent()
        assertEquals(2, scanCallbacks.size)
        clearInvocations(scanner)
        val record = mock<ScanRecord> {
            on { serviceUuids }.thenReturn(listOf(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)))
        }
        val result = mock<ScanResult> {
            on { device }.thenReturn(this@BluetoothGattClientCallbacksTest.device)
            on { rssi }.thenReturn(-30)
            on { scanRecord }.thenReturn(record)
        }
        oldScan.onScanResult(0, result)
        oldScan.onBatchScanResults(mutableListOf(result))
        oldScan.onScanFailed(1)
        scope.runCurrent()
        verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        verify(scanner, never()).stopScan(any<ScanCallback>())
        verifyNoInteractions(result)
    }
    @Test fun `scan callback after permission denial does not read or connect discovered device`() {
        assertTrue(client.start())
        scope.runCurrent()
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)
        val result = mock<ScanResult>()
        scanCallbacks.single().onScanResult(0, result)
        scanCallbacks.single().onBatchScanResults(mutableListOf(result))
        verifyNoInteractions(result)
        verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
    }

    private fun scanDebugResult(
        advertisedName: String?,
        rssi: Int,
        advertisedPeerHint: ByteArray? = null,
        beforeDelivery: () -> Unit = {}
    ): DebugScanResult {
        val debug = DebugSettingsManager.getInstance()
        debug.clearScanResults()
        whenever(power.getRSSIThreshold()).thenReturn(-70)
        assertTrue(client.start())
        scope.runCurrent()
        val record = mock<ScanRecord> {
            on { serviceUuids }.thenReturn(listOf(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)))
            on { deviceName }.thenReturn(advertisedName)
            on { getServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)) }
                .thenReturn(advertisedPeerHint)
        }
        val result = mock<ScanResult> {
            on { device }.thenReturn(this@BluetoothGattClientCallbacksTest.device)
            on { getRssi() }.thenReturn(rssi)
            on { scanRecord }.thenReturn(record)
        }
        beforeDelivery()
        scanCallbacks.single().onScanResult(0, result)
        return debug.scanResults.value.single()
    }

    @Test fun `advertised debug name avoids protected cached name without blocking connection attempt`() {
        whenever(device.name).thenThrow(SecurityException("synthetic name denial"))
        val result = scanDebugResult("Synthetic advertised label", -30)
        assertEquals("Synthetic advertised label", result.deviceName)
        verify(device, never()).name
        verify(device).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        verify(delegate, never()).onDeviceConnected(any())
    }

    @Test fun `cached debug name is read once for weak unnamed advertisement`() {
        whenever(device.name).thenReturn("Synthetic cached label")
        val result = scanDebugResult(null, -95)
        assertEquals("Synthetic cached label", result.deviceName)
        assertEquals(-95, tracker.getBestRSSI(address))
        verify(device).name
        verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
    }

    @Test fun `revoked cached name still publishes weak scan without claiming a connection`() {
        whenever(device.name).thenThrow(SecurityException("synthetic name denial"))
        val result = scanDebugResult(null, -95)
        assertNull(result.deviceName)
        assertEquals(-95, tracker.getBestRSSI(address))
        verify(device).name
        verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        verify(delegate, never()).onDeviceConnected(any())
    }

    @Test fun `permission revoked before optional name lookup prevents protected read and connection attempt`() {
        val result = scanDebugResult(null, -30) {
            // The callback and scan handler pass preflight, then name lookup and connect fail closed.
            whenever(permissions.hasBluetoothPermissions()).thenReturn(true, true, false)
        }
        assertNull(result.deviceName)
        verify(device, never()).name
        verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        verify(delegate, never()).onDeviceConnected(any())
    }

    @Test fun `optional cached name refusal does not gate otherwise accepted connection attempt`() {
        whenever(device.name).thenThrow(SecurityException("synthetic name denial"))
        val result = scanDebugResult(null, -30)
        assertNull(result.deviceName)
        verify(device).name
        verify(device).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        verify(delegate, never()).onDeviceConnected(any())
    }

    @Test fun `advertised peer hint cannot suppress or authenticate a new physical link`() {
        val claimedPeer = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x7F)
        val claimedPeerHex = claimedPeer.joinToString("") { "%02x".format(it) }
        tracker.addDeviceConnection("AA:BB:CC:DD:EE:02",
            BluetoothConnectionTracker.DeviceConnection(
                mock(), peerID = claimedPeerHex, linkID = "authenticated-other-link"
            ))

        val debug = scanDebugResult(null, -30, advertisedPeerHint = claimedPeer)
        assertEquals(claimedPeerHex, debug.peerID)
        verify(device).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))

        val callback = callbacks.last()
        mtu(callback)
        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        val linkID = tracker.getAllDeviceConnections().single { it.isClient }.linkID
        assertNull(tracker.getPeerForLink(linkID))
        assertFalse(tracker.addressPeerMap.containsKey(address))
    }

    @Test fun `legacy dual role survivor is not redialed by location or watchdog generations`() {
        whenever(power.profile).thenReturn(
            MutableStateFlow(PowerProfileResolver.resolve(100, true, false, false))
        )
        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(
                device, isClient = true, linkID = "legacy-client-link"
            )
        )
        tracker.addServerConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(
                device, isClient = false, linkID = "legacy-server-link"
            )
        )
        assertTrue(
            tracker.observePeerIfCurrent(
                address,
                "legacy-client-link",
                REMOTE_PEER_ID,
                supportsDeterministicElection = false
            ).linksToClose.isEmpty()
        )
        assertTrue(
            tracker.observePeerIfCurrent(
                address,
                "legacy-server-link",
                REMOTE_PEER_ID,
                supportsDeterministicElection = false
            ).linksToClose.isEmpty()
        )
        val record = mock<ScanRecord> {
            on { serviceUuids }.thenReturn(listOf(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)))
        }
        val result = mock<ScanResult> {
            on { device }.thenReturn(this@BluetoothGattClientCallbacksTest.device)
            on { rssi }.thenReturn(-30)
            on { scanRecord }.thenReturn(record)
        }
        val legacy = clientForSdk(Build.VERSION_CODES.R)
        try {
            assertTrue(legacy.start())
            scope.runCurrent()
            val staleScan = scanCallbacks.single()
            staleScan.onScanResult(0, result)

            legacy.onLegacyLocationChanged(false)
            scanTime += 5_000
            legacy.onLegacyLocationChanged(true)
            scope.advanceTimeBy(500)
            scope.runCurrent()
            assertEquals(2, scanCallbacks.size)

            clearInvocations(device)
            staleScan.onScanResult(0, result)
            scanCallbacks.last().onScanResult(0, result)
            repeat(4) {
                scanTime += 30_000
                scope.advanceTimeBy(30_000)
                scope.runCurrent()
            }
            scope.advanceTimeBy(500)
            scope.runCurrent()
            assertEquals(3, scanCallbacks.size)
            scanCallbacks.last().onScanResult(0, result)

            verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
            assertTrue(tracker.hasLink(address, "legacy-client-link"))
            assertTrue(tracker.hasLink(address, "legacy-server-link"))
        } finally {
            legacy.stop()
        }
    }

    @Test fun `repeated scan scheduling cannot recreate authenticated cooperative tie break loser`() {
        val winnerAddress = "AA:BB:CC:DD:EE:02"
        val winnerDevice = mock<BluetoothDevice> { on { getAddress() }.thenReturn(winnerAddress) }
        tracker.addDeviceConnection(address,
            BluetoothConnectionTracker.DeviceConnection(
                device, isClient = true, linkID = "rejected-client"
            ))
        tracker.addServerConnection(winnerAddress,
            BluetoothConnectionTracker.DeviceConnection(
                winnerDevice, isClient = false, linkID = "authenticated-server"
            ))
        tracker.observePeerIfCurrent(
            address,
            "rejected-client",
            REMOTE_PEER_ID,
            supportsDeterministicElection = true
        )
        val election = tracker.observePeerIfCurrent(
            winnerAddress,
            "authenticated-server",
            REMOTE_PEER_ID,
            supportsDeterministicElection = true
        )
        assertEquals(listOf("rejected-client"), election.linksToClose.map { it.connection.linkID })
        clearInvocations(device)

        repeat(30) { scanDebugResult(null, -30) }

        verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        assertTrue(tracker.hasLink(winnerAddress, "authenticated-server"))

        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(winnerAddress, "authenticated-server"))
        scanTime += BluetoothConnectionTracker.TIE_BREAK_RECONNECT_COOLDOWN_MS - 1
        scanDebugResult(null, -30)
        verify(device, never()).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))

        scanTime += 1
        scanDebugResult(null, -30)
        verify(device).connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
    }

    private companion object {
        const val LOCAL_PEER_ID = "ffeeddccbbaa9988"
        const val REMOTE_PEER_ID = "0011223344556677"
    }

}
