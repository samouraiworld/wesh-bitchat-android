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
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    private val manager = BluetoothGattServerManager(context, scope, tracker, permissions, power,
        delegate)
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

    @Test fun `advertising waits for successful service callback`() {
        start()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
        publish()
        verify(advertiser).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `overlapping advertising restarts leave one advertiser running`() {
        start()
        publish()
        manager.restartAdvertising()
        manager.restartAdvertising()
        scope.advanceTimeBy(200)
        scope.runCurrent()
        val started = argumentCaptor<AdvertiseCallback>()
        verify(advertiser, times(3)).startAdvertising(any(), any(), any(), started.capture())
        val stopped = argumentCaptor<AdvertiseCallback>()
        verify(advertiser, atLeast(0)).stopAdvertising(stopped.capture())
        assertEquals(1, (started.allValues - stopped.allValues.toSet()).size)
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

    @Test fun `null open leaves server unavailable and permits a new attempt`() {
        openResult = null
        start()
        assertNull(manager.getGattServer())
        assertNull(manager.getCharacteristic())
        openResult = gatt
        start()
        assertSame(gatt, manager.getGattServer())
    }

    @Test fun `revoked open is contained and a later explicit start can recover`() {
        openFails = true
        start()
        assertNull(manager.getGattServer())
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
    }

    @Test fun `revoked service publication closes its server without advertising`() {
        addFails = true
        start()
        assertNull(manager.getGattServer())
        verify(gatt).close()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
    }

    @Test fun `unsuccessful service callback closes the generation`() {
        start()
        callback.onServiceAdded(BluetoothGatt.GATT_FAILURE, service)
        assertNull(manager.getGattServer())
        verify(gatt).close()
        verify(advertiser, never()).startAdvertising(any(), any(), any(), any())
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
    }

    @Test fun `denied CCCD response does not subscribe or announce ready`() {
        start(); publish(); connect()
        doThrow(SecurityException("synthetic revocation")).whenever(gatt)
            .sendResponse(any(), any(), any(), any(), anyOrNull())
        subscribe(); readyTime()
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        verify(delegate, never()).onDeviceConnected(any())
        assertNull(manager.getGattServer())
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
