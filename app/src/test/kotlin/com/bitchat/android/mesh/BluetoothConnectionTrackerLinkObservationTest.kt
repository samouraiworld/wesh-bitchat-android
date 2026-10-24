package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BluetoothConnectionTrackerLinkObservationTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val tracker = BluetoothConnectionTracker(scope, mock())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `stale connection callbacks cannot mutate or remove replacement link`() {
        val address = "AA:BB:CC:DD:EE:FF"
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)

        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-a")
        )
        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-b")
        )

        assertFalse(
            tracker.updateDeviceConnectionIfCurrent(address, "link-a") {
                it.copy(rssi = -10)
            }
        )
        assertFalse(tracker.cleanupDeviceConnectionIfCurrent(address, "link-a"))
        assertEquals("link-b", tracker.getCurrentLinkID(address))

        assertTrue(tracker.observePeerIfCurrent(address, "link-b", "0011223344556677"))
        assertEquals("0011223344556677", tracker.addressPeerMap[address])
        assertSame(device, tracker.getDeviceConnection(address)?.device)
    }

    @Test
    fun `a peer heard over the server side of a link held by the client is observed`() {
        val address = "AA:BB:CC:DD:EE:FF"
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)

        // Remote dialled first, then this device's own client registered over the same address.
        assertTrue(tracker.addServerConnection(address,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = false, linkID = "server-1")))
        tracker.addDeviceConnection(address,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = true, linkID = "client-1"))
        assertTrue(tracker.observePeerIfCurrent(address, "server-1", "0011223344556677"))

        // The other order: the client holds the slot when the server reports the link.
        assertFalse(tracker.addServerConnection(address,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = false, linkID = "server-2")))
        assertEquals("client-1", tracker.getCurrentLinkID(address))
        assertTrue(tracker.observePeerIfCurrent(address, "server-2", "0011223344556677"))

        // The client side closing hands the slot to the server link, still up on the same address.
        val other = "AA:BB:CC:DD:EE:00"
        tracker.addServerConnection(other,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = false, linkID = "server-3"))
        tracker.addDeviceConnection(other,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = true, linkID = "client-3"))
        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(other, "client-3"))
        assertEquals("server-3", tracker.getCurrentLinkID(other))
        assertTrue(tracker.observePeerIfCurrent(other, "server-3", "0011223344556677"))

        // The server side closing leaves the client link tracked.
        assertFalse(tracker.cleanupDeviceConnectionIfCurrent(address, "server-2"))
        assertFalse(tracker.observePeerIfCurrent(address, "server-2", "0011223344556677"))
        assertEquals("client-1", tracker.getCurrentLinkID(address))
    }

    @Test
    fun `one peer can remain directly observed over multiple current links`() {
        val firstAddress = "AA:BB:CC:DD:EE:01"
        val secondAddress = "AA:BB:CC:DD:EE:02"
        val firstDevice = mock<BluetoothDevice>()
        val secondDevice = mock<BluetoothDevice>()
        whenever(firstDevice.address).thenReturn(firstAddress)
        whenever(secondDevice.address).thenReturn(secondAddress)

        tracker.addDeviceConnection(
            firstAddress,
            BluetoothConnectionTracker.DeviceConnection(device = firstDevice, linkID = "link-a")
        )
        tracker.addDeviceConnection(
            secondAddress,
            BluetoothConnectionTracker.DeviceConnection(device = secondDevice, linkID = "link-b")
        )

        assertTrue(tracker.observePeerIfCurrent(firstAddress, "link-a", PEER_ID))
        assertTrue(tracker.observePeerIfCurrent(secondAddress, "link-b", PEER_ID))
        assertTrue(tracker.observePeerIfCurrent(secondAddress, "link-b", PEER_ID))
        assertEquals(2, tracker.addressPeerMap.values.count { it == PEER_ID })

        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(firstAddress, "link-a"))
        assertEquals(PEER_ID, tracker.addressPeerMap[secondAddress])
        assertTrue(tracker.addressPeerMap.containsValue(PEER_ID))
    }

    private companion object {
        const val PEER_ID = "0011223344556677"
    }
}
