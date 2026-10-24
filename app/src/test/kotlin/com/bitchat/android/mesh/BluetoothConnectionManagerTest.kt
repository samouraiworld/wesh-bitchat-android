package com.bitchat.android.mesh

import android.bluetooth.BluetoothAdapter
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
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
}
