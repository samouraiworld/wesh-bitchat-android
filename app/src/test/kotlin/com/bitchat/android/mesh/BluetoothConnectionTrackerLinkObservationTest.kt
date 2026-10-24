package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BluetoothConnectionTrackerLinkObservationTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val tracker = BluetoothConnectionTracker(scope, mock(), LOCAL_LOW)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `stale connection callbacks cannot mutate or remove replacement link`() {
        val device = device(ADDRESS)
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-a"))
        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(ADDRESS, "link-a"))
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-b"))

        assertFalse(tracker.updateDeviceConnectionIfCurrent(ADDRESS, "link-a") { it.copy(rssi = -10) })
        assertFalse(tracker.cleanupDeviceConnectionIfCurrent(ADDRESS, "link-a"))
        assertEquals("link-b", tracker.getCurrentLinkID(ADDRESS))

        assertTrue(tracker.observePeerIfCurrent(ADDRESS, "link-b", REMOTE_HIGH).accepted)
        assertEquals(REMOTE_HIGH, tracker.addressPeerMap[ADDRESS])
        assertSame(device, tracker.getDeviceConnection(ADDRESS)?.device)
    }

    @Test
    fun `dual roles remain separately budgeted until authenticated announce elects client`() {
        val device = device(ADDRESS)
        tracker.addServerConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = false,
                connectedAt = 1, linkID = "server"))
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = true,
                connectedAt = 2, linkID = "client"))

        assertEquals(2, tracker.getConnectedDeviceCount())
        assertEquals(1, tracker.getConnectionsToEvict(maxOverall = 1, maxServer = 1, maxClient = 1).size)
        assertTrue(tracker.observePeerIfCurrent(
            ADDRESS, "server", REMOTE_HIGH, supportsDeterministicElection = true
        ).accepted)

        val elected = tracker.observePeerIfCurrent(
            ADDRESS, "client", REMOTE_HIGH, supportsDeterministicElection = true
        )
        assertTrue(elected.accepted)
        assertEquals(listOf("server"), elected.linksToClose.map { it.connection.linkID })
        assertEquals(1, tracker.getConnectedDeviceCount())
        assertTrue(tracker.getDeviceConnection(ADDRESS)?.isClient == true)
        assertFalse(tracker.cleanupDeviceConnectionIfCurrent(ADDRESS, "server"))
        assertEquals(REMOTE_HIGH, tracker.addressPeerMap[ADDRESS])
    }

    @Test
    fun `higher local peer elects server and rejects late client callback`() {
        val high = BluetoothConnectionTracker(scope, mock(), LOCAL_HIGH)
        val device = device(ADDRESS)
        high.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = true,
                connectedAt = 1, linkID = "client"))
        high.addServerConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, isClient = false,
                connectedAt = 2, linkID = "server"))

        assertTrue(high.observePeerIfCurrent(
            ADDRESS, "client", REMOTE_LOW, supportsDeterministicElection = true
        ).accepted)
        val elected = high.observePeerIfCurrent(
            ADDRESS, "server", REMOTE_LOW, supportsDeterministicElection = true
        )

        assertTrue(elected.accepted)
        assertEquals(listOf("client"), elected.linksToClose.map { it.connection.linkID })
        assertFalse(high.getDeviceConnection(ADDRESS)?.isClient == true)
        assertFalse(high.updateDeviceConnectionIfCurrent(ADDRESS, "client") { it.copy(rssi = -1) })
    }

    @Test
    fun `late announce cannot rewrite an authenticated address to another peer`() {
        val device = device(ADDRESS)
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "current"))
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "stale"))

        assertTrue(tracker.observePeerIfCurrent(ADDRESS, "current", REMOTE_HIGH).accepted)
        val stale = tracker.observePeerIfCurrent(ADDRESS, "stale", OTHER_REMOTE)

        assertFalse(stale.accepted)
        assertEquals(listOf("stale"), stale.linksToClose.map { it.connection.linkID })
        assertEquals(REMOTE_HIGH, tracker.addressPeerMap[ADDRESS])
        assertFalse(tracker.hasLink(ADDRESS, "stale"))
    }

    @Test
    fun `two addresses for one authenticated peer collapse to one deterministic role`() {
        val clientDevice = device(ADDRESS)
        val serverDevice = device(OTHER_ADDRESS)
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(clientDevice, isClient = true, linkID = "client"))
        tracker.addServerConnection(OTHER_ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(serverDevice, isClient = false, linkID = "server"))

        assertTrue(tracker.observePeerIfCurrent(
            OTHER_ADDRESS, "server", REMOTE_HIGH, supportsDeterministicElection = true
        ).accepted)
        val elected = tracker.observePeerIfCurrent(
            ADDRESS, "client", REMOTE_HIGH, supportsDeterministicElection = true
        )

        assertTrue(elected.accepted)
        assertEquals(listOf("server"), elected.linksToClose.map { it.connection.linkID })
        assertEquals(REMOTE_HIGH, tracker.addressPeerMap[ADDRESS])
        assertNull(tracker.addressPeerMap[OTHER_ADDRESS])
        assertEquals(1, tracker.getConnectedDeviceCount())
    }

    @Test
    fun `legacy peer without signed election capability keeps both roles for legacy to settle`() {
        val device = device(ADDRESS)
        tracker.addServerConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device = device, isClient = false, linkID = "legacy-server"
            ))
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device = device, isClient = true, linkID = "legacy-client"
            ))

        assertTrue(tracker.observePeerIfCurrent(ADDRESS, "legacy-server", REMOTE_HIGH).accepted)
        val observation = tracker.observePeerIfCurrent(ADDRESS, "legacy-client", REMOTE_HIGH)

        assertTrue(observation.accepted)
        assertTrue(observation.linksToClose.isEmpty())
        assertEquals(2, tracker.getConnectedDeviceCount())
        assertTrue(tracker.hasLink(ADDRESS, "legacy-server"))
        assertTrue(tracker.hasLink(ADDRESS, "legacy-client"))
        assertFalse(tracker.shouldSuppressClientDial(ADDRESS))

        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(ADDRESS, "legacy-server"))
        assertEquals(1, tracker.getConnectedDeviceCount())
        assertEquals("legacy-client", tracker.getCurrentLinkID(ADDRESS))
        assertEquals("legacy-client", tracker.getDeviceConnection(ADDRESS)?.linkID)
        assertEquals(REMOTE_HIGH, tracker.addressPeerMap[ADDRESS])
        assertTrue(tracker.isPeerConnected(REMOTE_HIGH))
    }

    @Test
    fun `two cooperative peers dialing simultaneously converge and loser dial stays suppressed`() {
        var now = 1_000L
        val low = BluetoothConnectionTracker(scope, mock(), LOCAL_LOW) { now }
        val high = BluetoothConnectionTracker(scope, mock(), REMOTE_HIGH) { now }

        low.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device(ADDRESS), isClient = true, linkID = "low-client"
            ))
        low.addServerConnection(OTHER_ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device(OTHER_ADDRESS), isClient = false, linkID = "low-server"
            ))
        high.addDeviceConnection(OTHER_ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device(OTHER_ADDRESS), isClient = true, linkID = "high-client"
            ))
        high.addServerConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device(ADDRESS), isClient = false, linkID = "high-server"
            ))

        low.observePeerIfCurrent(
            OTHER_ADDRESS, "low-server", REMOTE_HIGH, supportsDeterministicElection = true
        )
        val lowResult = low.observePeerIfCurrent(
            ADDRESS, "low-client", REMOTE_HIGH, supportsDeterministicElection = true
        )
        high.observePeerIfCurrent(
            OTHER_ADDRESS, "high-client", LOCAL_LOW, supportsDeterministicElection = true
        )
        val highResult = high.observePeerIfCurrent(
            ADDRESS, "high-server", LOCAL_LOW, supportsDeterministicElection = true
        )

        assertEquals(listOf("low-server"), lowResult.linksToClose.map { it.connection.linkID })
        assertEquals(listOf("high-client"), highResult.linksToClose.map { it.connection.linkID })
        assertEquals(listOf("low-client"), low.getAllDeviceConnections().map { it.linkID })
        assertEquals(listOf("high-server"), high.getAllDeviceConnections().map { it.linkID })
        assertTrue(high.shouldSuppressClientDial(OTHER_ADDRESS))

        assertTrue(high.cleanupDeviceConnectionIfCurrent(ADDRESS, "high-server"))
        now += BluetoothConnectionTracker.TIE_BREAK_RECONNECT_COOLDOWN_MS - 1
        assertTrue(high.shouldSuppressClientDial(OTHER_ADDRESS))
        now += 1
        assertFalse(high.shouldSuppressClientDial(OTHER_ADDRESS))
    }

    @Test
    fun `Bluetooth off on clears cooperative loser suppression for a genuine reconnect`() {
        val high = BluetoothConnectionTracker(scope, mock(), LOCAL_HIGH)
        high.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device(ADDRESS), isClient = true, linkID = "outgoing-loser"
            ))
        high.addServerConnection(OTHER_ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(
                device(OTHER_ADDRESS), isClient = false, linkID = "incoming-winner"
            ))
        high.observePeerIfCurrent(
            ADDRESS, "outgoing-loser", REMOTE_LOW, supportsDeterministicElection = true
        )
        high.observePeerIfCurrent(
            OTHER_ADDRESS, "incoming-winner", REMOTE_LOW, supportsDeterministicElection = true
        )
        assertTrue(high.shouldSuppressClientDial(ADDRESS))

        high.stop()
        high.start()

        assertFalse(high.shouldSuppressClientDial(ADDRESS))
        assertEquals(0, high.getConnectedDeviceCount())
    }

    @Test
    fun `a remote claiming the local peer id is failed closed`() {
        tracker.addDeviceConnection(ADDRESS,
            BluetoothConnectionTracker.DeviceConnection(device(ADDRESS), linkID = "self"))

        val result = tracker.observePeerIfCurrent(ADDRESS, "self", LOCAL_LOW)

        assertFalse(result.accepted)
        assertEquals(listOf("self"), result.linksToClose.map { it.connection.linkID })
        assertEquals(0, tracker.getConnectedDeviceCount())
        assertFalse(tracker.addressPeerMap.containsKey(ADDRESS))
    }

    @Test
    fun `subscription list is unique by live server address`() {
        val first = device(ADDRESS)
        val replacement = device(ADDRESS)
        tracker.addSubscribedDevice(first)
        tracker.addSubscribedDevice(replacement)
        assertEquals(1, tracker.getSubscribedDevices().size)
        assertSame(replacement, tracker.getSubscribedDevices().single())

        tracker.removeSubscribedDevice(first)
        assertTrue(tracker.getSubscribedDevices().isEmpty())
    }

    private fun device(address: String): BluetoothDevice = mock<BluetoothDevice>().also {
        whenever(it.address).thenReturn(address)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:02"
        const val LOCAL_LOW = "0011223344556677"
        const val REMOTE_HIGH = "8899aabbccddeeff"
        const val LOCAL_HIGH = "ffeeddccbbaa9988"
        const val REMOTE_LOW = "1122334455667788"
        const val OTHER_REMOTE = "7766554433221100"
    }
}
