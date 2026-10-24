package com.bitchat.android.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BluetoothPermissionRevocationTest {
    private val scope = TestScope()
    private val adapter = mock<BluetoothAdapter>()
    private val bluetoothManager = mock<BluetoothManager> {
        on { adapter }.thenReturn(this@BluetoothPermissionRevocationTest.adapter)
    }
    private val context = mock<Context> {
        on { getSystemService(Context.BLUETOOTH_SERVICE) }.thenReturn(bluetoothManager)
        on { applicationContext }.thenReturn(RuntimeEnvironment.getApplication())
        on { checkPermission(any(), any(), any()) }.thenReturn(PackageManager.PERMISSION_GRANTED)
    }
    private val permissions = mock<BluetoothPermissionManager> {
        on { hasBluetoothPermissions() }.thenReturn(true)
    }
    private val tracker = BluetoothConnectionTracker(scope, mock())
    private var connectionManager: BluetoothConnectionManager? = null

    @After
    fun tearDown() {
        connectionManager?.stopServices()
        scope.cancel()
    }

    @Test
    fun `connection manager rejects startup if adapter permission is revoked after preflight`() {
        whenever(adapter.isEnabled).thenThrow(SecurityException("synthetic revocation"))
        val manager = BluetoothConnectionManager(context, "0011223344556677")
        connectionManager = manager

        assertFalse(manager.startServices())
        assertTrue(manager.isReusable())
        verify(adapter).isEnabled
        verify(bluetoothManager, never()).openGattServer(any(), any())
    }

    @Test
    fun `connection manager rejects denied permission without reading adapter state`() {
        whenever(context.checkPermission(any(), any(), any())).thenReturn(PackageManager.PERMISSION_DENIED)
        val manager = BluetoothConnectionManager(context, "0011223344556677")
        connectionManager = manager

        assertFalse(manager.startServices())
        verify(adapter, never()).isEnabled
    }

    @Test
    fun `client rejects startup if adapter permission is revoked after preflight`() {
        whenever(adapter.bluetoothLeScanner).thenReturn(mock<BluetoothLeScanner>())
        whenever(adapter.isEnabled).thenThrow(SecurityException("synthetic revocation"))
        val client = BluetoothGattClientManager(context, scope, tracker, permissions, mock(), null)

        assertFalse(client.start())
        client.stop()
        verify(adapter).isEnabled
    }

    @Test
    fun `server rejects startup if adapter permission is revoked after preflight`() {
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(mock<BluetoothLeAdvertiser>())
        whenever(adapter.isEnabled).thenThrow(SecurityException("synthetic revocation"))
        val server = BluetoothGattServerManager(context, scope, tracker, permissions, mock(), null)

        assertFalse(server.start())
        server.stop()
        verify(bluetoothManager, never()).openGattServer(any(), any())
    }

    @Test
    fun `server stop continues denied cancellations and releases denied close only once`() {
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(mock<BluetoothLeAdvertiser>())
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.isMultipleAdvertisementSupported).thenReturn(true)
        val gatt = mock<BluetoothGattServer>()
        whenever(bluetoothManager.openGattServer(any(), any())).thenReturn(gatt)
        whenever(gatt.addService(any())).thenReturn(true)
        val first = mock<BluetoothDevice>()
        val second = mock<BluetoothDevice>()
        tracker.addDeviceConnection("AA:BB:CC:DD:EE:01", BluetoothConnectionTracker.DeviceConnection(first))
        tracker.addDeviceConnection("AA:BB:CC:DD:EE:02", BluetoothConnectionTracker.DeviceConnection(second))
        doThrow(SecurityException("synthetic revocation")).whenever(gatt).cancelConnection(first)
        doThrow(SecurityException("synthetic revocation")).whenever(gatt).close()
        val server = BluetoothGattServerManager(context, scope, tracker, permissions, mock(), null)
        assertTrue(server.start())
        scope.runCurrent()
        assertNotNull(server.getGattServer())
        assertNotNull(server.getCharacteristic())

        server.stop()
        server.stop()

        verify(gatt).cancelConnection(first)
        verify(gatt).cancelConnection(second)
        verify(gatt).close()
        assertNull(server.getGattServer())
        assertNull(server.getCharacteristic())
    }

    @Test
    fun `denied server disconnect does not read permission protected device details in error path`() {
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(mock<BluetoothLeAdvertiser>())
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.isMultipleAdvertisementSupported).thenReturn(true)
        val gatt = mock<BluetoothGattServer>()
        whenever(bluetoothManager.openGattServer(any(), any())).thenReturn(gatt)
        whenever(gatt.addService(any())).thenReturn(true)
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenThrow(SecurityException("synthetic revocation"))
        doThrow(SecurityException("synthetic revocation")).whenever(gatt).cancelConnection(device)
        val server = BluetoothGattServerManager(context, scope, tracker, permissions, mock(), null)
        assertTrue(server.start())
        scope.runCurrent()

        server.disconnectDevice(device)
        server.stop()

        verify(device, never()).address
        verify(gatt).close()
    }
}
