package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PacketProcessorAnnounceSideEffectTest {
    private val processors = mutableListOf<PacketProcessor>()

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
    }

    @Test
    fun `rejected announce does not update last seen or relay`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = false)
        val processor = processor(delegate)

        processor.processPacket(announce())
        withTimeout(1_000) { delegate.handled.await() }

        assertNull(withTimeoutOrNull(250) { delegate.lastSeen.await() })
        assertEquals(0, delegate.relayCount)
    }

    @Test
    fun `accepted announce unlocks post-handler side effects`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = true)
        val processor = processor(delegate)

        processor.processPacket(announce())

        assertEquals(PEER_ID, withTimeout(1_000) { delegate.lastSeen.await() })
    }

    @Test
    fun `validated self announce closes only its exact direct link before presence or relay`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val encryption = FlowEncryptionService()
        val noiseKey = ByteArray(32) { 0x42 }
        val signingKey = ByteArray(32) { 0x24 }
        val localPeerID = NoisePeerIdentity.derivePeerID(noiseKey)!!
        val security = SecurityManager(encryption, localPeerID)
        val rejectedValidation = CompletableDeferred<Unit>()
        val tracker = BluetoothConnectionTracker(scope, mock(), localPeerID)
        val selfAddress = "AA:BB:CC:DD:EE:01"
        val witnessAddress = "AA:BB:CC:DD:EE:02"
        tracker.addDeviceConnection(selfAddress,
            BluetoothConnectionTracker.DeviceConnection(device(selfAddress), linkID = "self-link"))
        tracker.addDeviceConnection(witnessAddress,
            BluetoothConnectionTracker.DeviceConnection(device(witnessAddress), linkID = "witness-link"))
        val delegate = RecordingDelegate(
            acceptAnnounce = true,
            validate = { packet, peerID ->
                val valid = security.validatePacket(packet, peerID)
                if (!valid) rejectedValidation.complete(Unit)
                valid
            },
            onValidatedSelf = selfHandler@ { routed ->
                val observation = DirectLinkAnnouncementPolicy.observationFor(routed, 7u)
                    ?: return@selfHandler false
                tracker.observePeerIfCurrent(
                    observation.relayAddress,
                    observation.ingressLinkID,
                    observation.peerID
                )
                true
            }
        )
        val processor = processor(delegate, localPeerID)
        try {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.ANNOUNCE.value,
                senderID = localPeerID.hexToBytes(),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = IdentityAnnouncement("Self", noiseKey, signingKey).encode()!!,
                ttl = 7u
            ).also { it.signature = ByteArray(64) { 1 } }

            processor.processPacket(RoutedPacket(packet, localPeerID, selfAddress, "self-link"))
            withTimeout(1_000) { delegate.validatedSelfHandled.await() }

            assertFalse(tracker.hasLink(selfAddress, "self-link"))
            assertTrue(tracker.hasLink(witnessAddress, "witness-link"))
            assertEquals(0, delegate.announceHandleCount)
            assertNull(withTimeoutOrNull(100) { delegate.lastSeen.await() })
            assertEquals(0, delegate.relayCount)

            tracker.addDeviceConnection(selfAddress,
                BluetoothConnectionTracker.DeviceConnection(device(selfAddress), linkID = "invalid-self-link"))
            encryption.acceptSignatures = false
            processor.processPacket(RoutedPacket(
                packet.copy(timestamp = packet.timestamp + 1u),
                localPeerID,
                selfAddress,
                "invalid-self-link"
            ))
            withTimeout(1_000) { rejectedValidation.await() }
            assertTrue(tracker.hasLink(selfAddress, "invalid-self-link"))
            assertEquals(1, delegate.validatedSelfCount)
        } finally {
            security.shutdown()
            tracker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `rejected handshake does not update last seen`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = true, acceptHandshake = false)
        val processor = processor(delegate)

        processor.processPacket(handshake())
        withTimeout(1_000) { delegate.handshakeHandled.await() }

        assertNull(withTimeoutOrNull(250) { delegate.lastSeen.await() })
    }

    @Test
    fun `accepted handshake updates last seen`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = true, acceptHandshake = true)
        val processor = processor(delegate)

        processor.processPacket(handshake())

        assertEquals(PEER_ID, withTimeout(1_000) { delegate.lastSeen.await() })
    }

    @Test
    fun `a redelivered copy of an already relayed packet is not relayed again`() = runBlocking {
        val suppressed = RecordingDelegate(acceptAnnounce = true, relaySuppressed = true)
        processor(suppressed).processPacket(publicMessage())
        withTimeout(1_000) { suppressed.lastSeen.await() }
        kotlinx.coroutines.delay(200)
        assertEquals(0, suppressed.relayCount)

        val normal = RecordingDelegate(acceptAnnounce = true)
        processor(normal).processPacket(publicMessage())
        withTimeout(1_000) { while (normal.relayCount == 0) kotlinx.coroutines.delay(10) }
        assertEquals(1, normal.relayCount)
    }

    @Test
    fun `a first copy dropped by local delivery is still relayed once`() = runBlocking {
        // Local delivery runs asynchronously and may mark the packet suppressed before the relay step.
        val delegate = RecordingDelegate(acceptAnnounce = true, suppressOnHandleMessage = true)
        processor(delegate).processPacket(publicMessage())
        withTimeout(1_000) { delegate.lastSeen.await() }
        kotlinx.coroutines.delay(200)
        assertEquals(1, delegate.relayCount)
    }

    private fun publicMessage(): RoutedPacket = RoutedPacket(BitchatPacket(
        version = 1u, type = MessageType.MESSAGE.value, senderID = PEER_ID.hexToBytes(),
        recipientID = SpecialRecipients.BROADCAST, timestamp = System.currentTimeMillis().toULong(),
        payload = "synthetic".toByteArray(), ttl = 7u), PEER_ID, "direct-link")

    private fun processor(delegate: RecordingDelegate, myPeerID: String = MY_PEER_ID): PacketProcessor =
        PacketProcessor(myPeerID).also {
            it.delegate = delegate
            processors += it
        }

    private fun announce(): RoutedPacket {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = PEER_ID.hexToBytes(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = byteArrayOf(0x01),
            ttl = 7u
        )
        return RoutedPacket(packet, PEER_ID, "direct-link")
    }

    private fun handshake(): RoutedPacket {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_HANDSHAKE.value,
            senderID = PEER_ID.hexToBytes(),
            recipientID = MY_PEER_ID.hexToBytes(),
            timestamp = System.currentTimeMillis().toULong(),
            payload = byteArrayOf(0x01),
            ttl = 7u
        )
        return RoutedPacket(packet, PEER_ID, "direct-link")
    }

    private class RecordingDelegate(
        private val acceptAnnounce: Boolean,
        private val acceptHandshake: Boolean = false,
        @Volatile private var relaySuppressed: Boolean = false,
        private val suppressOnHandleMessage: Boolean = false,
        private val validate: (BitchatPacket, String) -> Boolean = { _, _ -> true },
        private val onValidatedSelf: (RoutedPacket) -> Boolean = { true }
    ) : PacketProcessorDelegate {
        override fun isRelaySuppressed(packet: BitchatPacket, peerID: String) = relaySuppressed
        val handled = CompletableDeferred<Unit>()
        val handshakeHandled = CompletableDeferred<Unit>()
        val validatedSelfHandled = CompletableDeferred<Unit>()
        val lastSeen = CompletableDeferred<String>()
        @Volatile var relayCount = 0
        @Volatile var validatedSelfCount = 0
        @Volatile var announceHandleCount = 0

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = validate(packet, peerID)
        override fun handleValidatedSelfAnnounce(routed: RoutedPacket): Boolean {
            validatedSelfCount += 1
            val accepted = onValidatedSelf(routed)
            // Publish completion only after the direct-link mutation is finished.
            validatedSelfHandled.complete(Unit)
            return accepted
        }
        override fun updatePeerLastSeen(peerID: String) {
            lastSeen.complete(peerID)
        }
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
            handshakeHandled.complete(Unit)
            return acceptHandshake
        }
        override fun handleNoiseEncrypted(routed: RoutedPacket) = true
        override suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
            announceHandleCount += 1
            handled.complete(Unit)
            return acceptAnnounce
        }
        override fun handleMessage(routed: RoutedPacket) {
            if (suppressOnHandleMessage) relaySuppressed = true
        }
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket, ingressKey: String): FragmentResult = FragmentResult.Pending
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayCount += 1
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun device(address: String): BluetoothDevice = mock<BluetoothDevice>().also {
        whenever(it.address).thenReturn(address)
    }

    private class FlowEncryptionService : EncryptionService(RuntimeEnvironment.getApplication()) {
        var acceptSignatures = true
        override fun initialize() = Unit
        override fun verifyEd25519Signature(
            signature: ByteArray,
            data: ByteArray,
            publicKeyBytes: ByteArray
        ): Boolean = acceptSignatures
    }

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val PEER_ID = "aaaabbbbccccdddd"
    }
}
