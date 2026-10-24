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
    private val tracker = BluetoothConnectionTracker(scope, mock())
    private val delegate = mock<BluetoothConnectionManagerDelegate>()
    private val device = mock<BluetoothDevice> { on { address }.thenReturn(this@BluetoothGattClientCallbacksTest.address) }
    private val gatt = mock<BluetoothGatt>()
    private val service = mock<BluetoothGattService>()
    private val characteristic = BluetoothGattCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID, 0, 0)
    private val descriptor = BluetoothGattDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID, 0)
    private val callbacks = mutableListOf<BluetoothGattCallback>()
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
        client = BluetoothGattClientManager(context, scope, tracker, permissions, power, delegate) { scanTime }
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

    private fun scanDebugResult(advertisedName: String?, rssi: Int, beforeDelivery: () -> Unit = {}): DebugScanResult {
        val debug = DebugSettingsManager.getInstance()
        debug.clearScanResults()
        whenever(power.getRSSIThreshold()).thenReturn(-70)
        assertTrue(client.start())
        scope.runCurrent()
        val record = mock<ScanRecord> {
            on { serviceUuids }.thenReturn(listOf(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID)))
            on { deviceName }.thenReturn(advertisedName)
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

}
