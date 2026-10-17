package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class WeshAckAndVoiceTest {
    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private val session = AuthenticatedNoiseSession(ByteArray(32) { 0x0B }, ByteArray(32) { 0x5D })
    private lateinit var handler: MessageHandler
    private lateinit var delegate: MessageHandlerDelegate

    @Before
    fun setup() {
        handler = MessageHandler(myPeerID, RuntimeEnvironment.getApplication())
        delegate = mock()
        handler.delegate = delegate
        whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(delegate.getPeerNickname(peerID)).thenReturn("Synthetic")
        whenever(delegate.getMyNickname()).thenReturn("Me")
        whenever(delegate.encryptForPeer(any(), eq(peerID))).thenReturn(byteArrayOf(0x55))
    }

    private val processors = mutableListOf<PacketProcessor>()

    @After
    fun tearDown() { processors.forEach(PacketProcessor::shutdown) }

    private fun encrypted(plaintext: ByteArray): RoutedPacket {
        val ciphertext = byteArrayOf(0x41, 0x42)
        whenever(delegate.decryptFromPeer(any(), eq(peerID))).thenReturn(NoiseDecryptionResult(plaintext, session))
        return RoutedPacket(BitchatPacket(
            version = 2u, type = MessageType.NOISE_ENCRYPTED.value, senderID = peerID.hexToBytes(),
            recipientID = myPeerID.hexToBytes(), timestamp = System.currentTimeMillis().toULong(),
            payload = ciphertext, signature = ByteArray(64) { 1 }, ttl = 7u), peerID, "link")
    }

    private fun privateText() = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE,
        PrivateMessagePacket("SYNTHETIC-ID", "Synthetic secret").encode()!!).encode()

    @Test
    fun `no delivery ACK is sent for a message admission refused`() = runBlocking<Unit> {
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(false)
        assertTrue(handler.handleNoiseEncrypted(encrypted(privateText())))
        verify(delegate).onPrivateMessageReceived(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        verify(delegate, never()).sendPacket(any())
    }

    @Test
    fun `a sender without a known nickname is left blank, never a placeholder word`() = runBlocking<Unit> {
        whenever(delegate.getPeerNickname(peerID)).thenReturn(null)
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(false)
        handler.handleNoiseEncrypted(encrypted(privateText()))
        val captured = org.mockito.kotlin.argumentCaptor<com.bitchat.android.model.BitchatMessage>()
        verify(delegate).onPrivateMessageReceived(captured.capture())
        assertTrue(captured.firstValue.sender.isEmpty())
    }

    @Test
    fun `a delegate that does not opt in never acknowledges`() = runBlocking<Unit> {
        // Real interface default, as for any transport that does not run admission itself.
        org.mockito.kotlin.doCallRealMethod().whenever(delegate).onPrivateMessageReceived(any())
        handler.handleNoiseEncrypted(encrypted(privateText()))
        verify(delegate).onMessageReceived(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        verify(delegate, never()).sendPacket(any())
    }

    @Test
    fun `a delivery ACK follows an admitted message`() = runBlocking<Unit> {
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(true)
        handler.handleNoiseEncrypted(encrypted(privateText()))
        verify(delegate).encryptForPeer(any(), eq(peerID))
        verify(delegate).sendPacket(any())
    }

    @Test
    fun `favorite notices are ignored without ACK when disabled`() = runBlocking<Unit> {
        handler.acceptFavoriteNotices = false
        val notice = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE,
            PrivateMessagePacket("FAV-ID", "[FAVORITED]:" + "ab".repeat(32)).encode()!!).encode()
        handler.handleNoiseEncrypted(encrypted(notice))
        verify(delegate, never()).encryptForPeer(any(), any())
    }

    @Test
    fun `a private voice frame from an enrolled contact produces no message and no ACK`() = runBlocking<Unit> {
        // Enrolled and admitted: a private text in this state would be shown and acknowledged.
        whenever(delegate.getAuthenticatedRemoteStaticKey(peerID)).thenReturn(session.remoteStaticKey)
        whenever(delegate.isEnrolledContactKey(any())).thenReturn(true)
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(true)
        val cacheBefore = RuntimeEnvironment.getApplication().cacheDir.walkTopDown().count()
        val filesBefore = RuntimeEnvironment.getApplication().filesDir.walkTopDown().count()

        val voice = NoisePayload(NoisePayloadType.VOICE_FRAME, ByteArray(40) { 7 }).encode()
        assertTrue(handler.handleNoiseEncrypted(encrypted(voice)))

        verify(delegate, never()).onPrivateMessageReceived(any())
        verify(delegate, never()).onMessageReceived(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        verify(delegate, never()).sendPacket(any())
        assertEquals(cacheBefore, RuntimeEnvironment.getApplication().cacheDir.walkTopDown().count())
        assertEquals(filesBefore, RuntimeEnvironment.getApplication().filesDir.walkTopDown().count())
    }

    @Test
    fun `a public voice frame is neither handled nor relayed by the packet processor`() = runBlocking<Unit> {
        val recorder = RelayRecorder()
        val processor = PacketProcessor(myPeerID).also { it.delegate = recorder; processors += it }
        fun packet(type: MessageType, recipient: ByteArray, payload: ByteArray) = RoutedPacket(BitchatPacket(
            version = 2u, type = type.value, senderID = peerID.hexToBytes(), recipientID = recipient,
            timestamp = System.currentTimeMillis().toULong(), payload = payload, signature = ByteArray(64) { 1 },
            ttl = 7u), peerID, "link")
        val voiceFragment = FragmentPayload(
            fragmentID = ByteArray(8) { 3 }, index = 0, total = 2,
            originalType = MessageType.VOICE_FRAME.value, data = ByteArray(40) { 7 }
        ).encode()

        processor.processPacket(packet(MessageType.VOICE_FRAME, SpecialRecipients.BROADCAST, ByteArray(40) { 7 }))
        processor.processPacket(packet(MessageType.VOICE_FRAME, "9999888877776666".hexToBytes(), ByteArray(40) { 7 }))
        processor.processPacket(packet(MessageType.VOICE_FRAME, myPeerID.hexToBytes(), ByteArray(40) { 7 }))
        processor.processPacket(packet(MessageType.FRAGMENT, SpecialRecipients.BROADCAST, voiceFragment))
        // One actor per peer handles packets in order: once this text is relayed, the voice was handled.
        processor.processPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "after".toByteArray()))
        withTimeout(5_000) { recorder.textRelayed.await() }

        assertEquals(listOf(MessageType.MESSAGE.value), recorder.relayed.map { it.packet.type })
        assertEquals(listOf(MessageType.MESSAGE.value), recorder.handled.map { it.packet.type })
        assertEquals(0, recorder.fragmentsHandled)
        assertEquals(1, recorder.lastSeenUpdates)
    }

    private class RelayRecorder : PacketProcessorDelegate {
        val relayed = CopyOnWriteArrayList<RoutedPacket>()
        val handled = CopyOnWriteArrayList<RoutedPacket>()
        val textRelayed = CompletableDeferred<Unit>()
        @Volatile var fragmentsHandled = 0
        @Volatile var lastSeenUpdates = 0

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) { lastSeenUpdates += 1 }
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket): Boolean { handled += routed; return true }
        override fun handleNoiseEncrypted(routed: RoutedPacket): Boolean { handled += routed; return true }
        override suspend fun handleAnnounce(routed: RoutedPacket): Boolean { handled += routed; return true }
        override fun handleMessage(routed: RoutedPacket) { handled += routed }
        override fun handleLeave(routed: RoutedPacket) { handled += routed }
        override fun handleFragment(packet: BitchatPacket): FragmentResult { fragmentsHandled += 1; return FragmentResult.Pending }
        override fun handleRequestSync(routed: RoutedPacket) { handled += routed }
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayed += routed
            if (routed.packet.type == MessageType.MESSAGE.value) textRelayed.complete(Unit)
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean { relayed += routed; return false }
    }

    @Test
    fun `a budget drop is reported so the packet is not marked seen for gossip`() = runBlocking<Unit> {
        val tight = MessageHandler(myPeerID, RuntimeEnvironment.getApplication(), PublicInboundBudget(
            global = PublicInboundBudget.Rate(0.0, 1.0), perLink = PublicInboundBudget.Rate(0.0, 100.0)))
        tight.delegate = delegate
        whenever(delegate.getPeerInfo(peerID)).thenReturn(PeerInfo(id = peerID, nickname = "Synthetic", isConnected = true,
            isDirectConnection = true, noisePublicKey = ByteArray(32) { 3 }, signingPublicKey = ByteArray(32) { 4 },
            isVerifiedNickname = true, lastSeen = System.currentTimeMillis()))
        fun message(text: String) = RoutedPacket(BitchatPacket(version = 2u, type = MessageType.MESSAGE.value,
            senderID = peerID.hexToBytes(), recipientID = SpecialRecipients.BROADCAST, timestamp = System.currentTimeMillis().toULong(),
            payload = text.toByteArray(), signature = ByteArray(64) { 1 }, ttl = 7u), peerID, "link")
        assertTrue(tight.handleMessage(message("first")))
        assertFalse(tight.handleMessage(message("second")))
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
