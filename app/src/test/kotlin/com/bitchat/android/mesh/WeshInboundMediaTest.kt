package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class WeshInboundMediaTest {
    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private lateinit var handler: MessageHandler
    private lateinit var delegate: MessageHandlerDelegate
    private val cache get() = RuntimeEnvironment.getApplication().cacheDir

    @Before
    fun setup() {
        File(cache, "images").deleteRecursively()
        File(cache, "files").deleteRecursively()
        handler = MessageHandler(myPeerID, RuntimeEnvironment.getApplication(),
            PublicInboundBudget(global = PublicInboundBudget.Rate(100.0, 100.0),
                perAuthenticatedSource = PublicInboundBudget.Rate(0.0, 5.0), perLink = PublicInboundBudget.Rate(100.0, 100.0),
                unauthenticated = PublicInboundBudget.Rate(0.0, 5.0)))
        delegate = mock()
        handler.delegate = delegate
        whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(delegate.getPeerNickname(peerID)).thenReturn("Synthetic")
        whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(true)
        whenever(delegate.getPeerInfo(peerID)).thenReturn(PeerInfo(
            id = peerID, nickname = "Synthetic", isConnected = true, isDirectConnection = true,
            noisePublicKey = ByteArray(32) { 3 }, signingPublicKey = ByteArray(32) { 4 },
            isVerifiedNickname = true, lastSeen = System.currentTimeMillis()))
    }

    private val processors = mutableListOf<PacketProcessor>()

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
    }

    /** A well-formed BitChat file TLV (name "a", 2 bytes, MIME "m"): Wesh must never decode it. */
    private val fileTlv = "01000161020004000000020300016d0400000002dead".hexToBytes()
    private val senderKey = ByteArray(32) { 9 }

    private fun packet(type: MessageType, recipient: ByteArray, payload: ByteArray) = BitchatPacket(
        version = 2u, type = type.value, senderID = peerID.hexToBytes(), recipientID = recipient,
        timestamp = System.currentTimeMillis().toULong(), payload = payload, signature = ByteArray(64) { 1 }, ttl = 7u)

    private fun incomingFiles(): List<File> = listOf("images/incoming", "files/incoming")
        .flatMap { File(cache, it).listFiles()?.toList().orEmpty() }

    /** A Noise transport message addressed to us whose plaintext is a 0x20 file payload. */
    private fun encryptedMedia(
        plaintext: ByteArray = NoisePayload(NoisePayloadType.FILE_TRANSFER, fileTlv).encode()
    ): RoutedPacket {
        val ciphertext = byteArrayOf(0x41, 0x42)
        whenever(delegate.decryptFromPeer(eq(ciphertext), eq(peerID))).thenReturn(NoiseDecryptionResult(
            plaintext,
            AuthenticatedNoiseSession(senderKey, ByteArray(32) { 5 })))
        whenever(delegate.encryptForPeer(any(), eq(peerID))).thenReturn(byteArrayOf(0x55))
        return RoutedPacket(packet(MessageType.NOISE_ENCRYPTED, myPeerID.hexToBytes(), ciphertext), peerID, "link")
    }

    private fun enroll() {
        whenever(delegate.getAuthenticatedRemoteStaticKey(peerID)).thenReturn(senderKey)
        whenever(delegate.isEnrolledContactKey(any())).thenReturn(true)
    }

    @Test
    fun `public media is dropped before delivery, storage or budget`() = runBlocking {
        handler.handleMessage(RoutedPacket(packet(MessageType.FILE_TRANSFER, SpecialRecipients.BROADCAST, fileTlv), peerID, "link"))

        verify(delegate, never()).onMessageReceived(any())
        verify(delegate, never()).onPrivateMessageReceived(any())
        assertEquals(0L, handler.publicBudget.dropped)
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `a public text carrying a file TLV stays text`() = runBlocking {
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, fileTlv), peerID, "link"))

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate).onMessageReceived(captor.capture())
        assertEquals(BitchatMessageType.Message, captor.firstValue.type)
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `a file for another peer is relayed as before, a public or local one never is`() = runBlocking {
        val recorder = RelayRecorder()
        val processor = PacketProcessor(myPeerID).also { it.delegate = recorder; processors += it }
        val otherPeer = "9999888877776666".hexToBytes()
        fun fragment(id: Byte) = FragmentPayload(
            fragmentID = ByteArray(8) { id }, index = 0, total = 2,
            originalType = MessageType.FILE_TRANSFER.value, data = fileTlv
        ).encode()

        // Relayed for the other peer, as main did, and never processed here.
        processor.processPacket(RoutedPacket(packet(MessageType.FILE_TRANSFER, otherPeer, fileTlv), peerID, "link"))
        processor.processPacket(RoutedPacket(packet(MessageType.FRAGMENT, otherPeer, fragment(3)), peerID, "link"))
        // Product decision for the V1: a broadcast file, whole or fragmented, is never spread.
        processor.processPacket(RoutedPacket(packet(MessageType.FILE_TRANSFER, SpecialRecipients.BROADCAST, fileTlv), peerID, "link"))
        processor.processPacket(RoutedPacket(packet(MessageType.FRAGMENT, SpecialRecipients.BROADCAST, fragment(4)), peerID, "link"))
        // Addressed to us: a whole file reaches the handler (which keeps nothing); a fragment is not reassembled.
        processor.processPacket(RoutedPacket(packet(MessageType.FILE_TRANSFER, myPeerID.hexToBytes(), fileTlv), peerID, "link"))
        processor.processPacket(RoutedPacket(packet(MessageType.FRAGMENT, myPeerID.hexToBytes(), fragment(5)), peerID, "link"))
        // One actor per peer handles packets in order: once this text is relayed, the media was handled.
        processor.processPacket(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "after".toByteArray()), peerID, "link"))
        withTimeout(5_000) { recorder.textRelayed.await() }

        assertEquals(
            listOf(MessageType.FILE_TRANSFER.value, MessageType.FRAGMENT.value, MessageType.MESSAGE.value),
            recorder.relayed.map { it.packet.type }
        )
        assertTrue(recorder.relayed.take(2).all { it.packet.recipientID!!.contentEquals(otherPeer) })
        assertEquals(listOf(MessageType.FILE_TRANSFER.value, MessageType.MESSAGE.value), recorder.handled.map { it.packet.type })
        assertTrue(recorder.handled.first().packet.recipientID!!.contentEquals(myPeerID.hexToBytes()))
        assertEquals(0, recorder.fragmentsHandled)
    }

    @Test
    fun `private media from a sender who is not an enrolled contact gets silence`() = runBlocking {
        handler.handleNoiseEncrypted(encryptedMedia())
        handler.handleMessage(RoutedPacket(packet(MessageType.FILE_TRANSFER, myPeerID.hexToBytes(), fileTlv), peerID, "link"))

        verify(delegate, never()).onPrivateMessageReceived(any())
        verify(delegate, never()).onMessageReceived(any())
        verify(delegate, never()).sendPacket(any())
        verify(delegate, never()).verifySignature(any(), any())
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `private media from an enrolled contact is one empty placeholder and nothing on disk`() = runBlocking {
        enroll()
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(true)

        handler.handleNoiseEncrypted(encryptedMedia())

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate, times(1)).onPrivateMessageReceived(captor.capture())
        val placeholder = captor.firstValue
        assertEquals("", placeholder.content)
        assertTrue(placeholder.type != BitchatMessageType.Message)
        assertTrue(placeholder.isPrivate)
        assertEquals(peerID, placeholder.senderPeerID)
        verify(delegate, never()).onMessageReceived(any())
        // No DELIVERED: the media itself never arrived.
        verify(delegate, never()).sendPacket(any())
        verify(delegate, never()).encryptForPeer(any(), any())
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `a private file under the prerelease 0x09 alias is the same placeholder`() = runBlocking {
        enroll()
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(true)

        handler.handleNoiseEncrypted(encryptedMedia(byteArrayOf(0x09) + fileTlv))

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate, times(1)).onPrivateMessageReceived(captor.capture())
        assertEquals("", captor.firstValue.content)
        assertTrue(captor.firstValue.type != BitchatMessageType.Message)
        verify(delegate, never()).sendPacket(any())
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `a directed raw file from an enrolled contact is one placeholder without acknowledgement`() = runBlocking {
        enroll()

        handler.handleMessage(RoutedPacket(packet(MessageType.FILE_TRANSFER, myPeerID.hexToBytes(), fileTlv), peerID, "link"))

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate, times(1)).onMessageReceived(captor.capture())
        assertEquals("", captor.firstValue.content)
        assertTrue(captor.firstValue.type != BitchatMessageType.Message)
        assertTrue(captor.firstValue.isPrivate)
        verify(delegate, never()).onPrivateMessageReceived(any())
        verify(delegate, never()).sendPacket(any())
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `a placeholder that admission rejects is not acknowledged`() = runBlocking {
        enroll()
        whenever(delegate.onPrivateMessageReceived(any())).thenReturn(false)

        handler.handleNoiseEncrypted(encryptedMedia())

        verify(delegate, times(1)).onPrivateMessageReceived(any())
        verify(delegate, never()).sendPacket(any())
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
        override fun handleNoiseHandshake(routed: RoutedPacket) = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) = true
        override suspend fun handleAnnounce(routed: RoutedPacket) = true
        override fun handleMessage(routed: RoutedPacket) { handled += routed }
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): FragmentResult { fragmentsHandled += 1; return FragmentResult.Pending }
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayed += routed
            if (routed.packet.type == MessageType.MESSAGE.value) textRelayed.complete(Unit)
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    @Test
    fun `public text above the V1 limit is dropped silently`() = runBlocking {
        // Refused (false): the caller neither stores it for gossip sync nor treats it as delivered.
        assertFalse(handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, ByteArray(2_001) { 'a'.code.toByte() }), peerID, "link")))
        verify(delegate, never()).onMessageReceived(any())
        assertEquals(1L, handler.publicBudget.dropped)

        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, ByteArray(2_000) { 'a'.code.toByte() }), peerID, "link"))
        verify(delegate).onMessageReceived(any())
    }

    @Test
    fun `public flood from one peer is cut at its budget`() = runBlocking {
        repeat(20) { index ->
            handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "hello $index".toByteArray()), peerID, "link"))
        }
        verify(delegate, times(5)).onMessageReceived(any())
        assertEquals(15L, handler.publicBudget.dropped)
    }

    @Test
    fun `Wesh drops unencrypted directed text entirely`() = runBlocking {
        handler.acceptDirectedPlaintext = false
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "directed".toByteArray()), peerID, "link"))
        verify(delegate, never()).onMessageReceived(any())
        assertEquals(1L, handler.publicBudget.dropped)
    }

    @Test
    fun `unauthenticated senders share one budget whatever key they announce`() = runBlocking {
        val otherPeer = "1111aaaa2222bbbb"
        val otherInfo = delegate.getPeerInfo(peerID)!!.copy(id = otherPeer)
        whenever(delegate.getPeerInfo(otherPeer)).thenReturn(otherInfo)
        whenever(delegate.getPeerNickname(otherPeer)).thenReturn("Synthetic")
        // No Noise session: an announced key earns no bucket; both peer IDs share one (5 total).
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "a$it".toByteArray()), peerID, "link")) }
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "b$it".toByteArray()).copy(senderID = otherPeer.hexToBytes()), otherPeer, "link")) }
        verify(delegate, times(5)).onMessageReceived(any())
    }

    @Test
    fun `distinct authenticated keys get separate budgets`() = runBlocking {
        val otherPeer = "1111aaaa2222bbbb"
        val otherInfo = delegate.getPeerInfo(peerID)!!.copy(id = otherPeer)
        whenever(delegate.getPeerInfo(otherPeer)).thenReturn(otherInfo)
        whenever(delegate.getAuthenticatedRemoteStaticKey(peerID)).thenReturn(ByteArray(32) { 7 })
        whenever(delegate.getAuthenticatedRemoteStaticKey(otherPeer)).thenReturn(ByteArray(32) { 8 })
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "a$it".toByteArray()), peerID, "link")) }
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "b$it".toByteArray()).copy(senderID = otherPeer.hexToBytes()), otherPeer, "link")) }
        verify(delegate, times(10)).onMessageReceived(any())
    }

    @Test
    fun `an enrolled QR contact is not charged to the relaying link`() = runBlocking {
        val key = ByteArray(32) { 7 }
        val tightLink = MessageHandler(myPeerID, RuntimeEnvironment.getApplication(),
            PublicInboundBudget(global = PublicInboundBudget.Rate(100.0, 100.0),
                perAuthenticatedSource = PublicInboundBudget.Rate(0.0, 5.0), perLink = PublicInboundBudget.Rate(0.0, 2.0)))
        tightLink.delegate = delegate
        whenever(delegate.getAuthenticatedRemoteStaticKey(peerID)).thenReturn(key)
        whenever(delegate.isEnrolledContactKey(key)).thenReturn(true)
        repeat(5) { tightLink.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "c$it".toByteArray()), peerID, "link")) }
        verify(delegate, times(5)).onMessageReceived(any())
    }

    @Test
    fun `the Wesh inbound policy closes plaintext private text and favorite notices`() {
        val fresh = MessageHandler(myPeerID, RuntimeEnvironment.getApplication())
        fresh.applyInboundPolicy(weshLocalOnly = true)
        assertFalse(fresh.acceptDirectedPlaintext || fresh.acceptFavoriteNotices)
        fresh.applyInboundPolicy(weshLocalOnly = false)
        assertTrue(fresh.acceptDirectedPlaintext && fresh.acceptFavoriteNotices)
        // No build decodes compressed media any more, whatever the product policy.
        assertFalse(com.bitchat.android.protocol.PacketPayloadLimits.acceptCompressedMedia)
    }

    @Test
    fun `peers proven to hold one key share one budget`() = runBlocking {
        val otherPeer = "1111aaaa2222bbbb"
        val key = ByteArray(32) { 7 }
        val otherInfo = delegate.getPeerInfo(peerID)!!.copy(id = otherPeer)
        whenever(delegate.getPeerInfo(otherPeer)).thenReturn(otherInfo)
        whenever(delegate.getAuthenticatedRemoteStaticKey(peerID)).thenReturn(key)
        whenever(delegate.getAuthenticatedRemoteStaticKey(otherPeer)).thenReturn(key)
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "a$it".toByteArray()), peerID, "link")) }
        repeat(10) { handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, SpecialRecipients.BROADCAST, "b$it".toByteArray()).copy(senderID = otherPeer.hexToBytes()), otherPeer, "link")) }
        verify(delegate, times(5)).onMessageReceived(any())
    }

    @Test
    fun `signed directed plain text is delivered as private so QR admission applies`() = runBlocking {
        handler.acceptDirectedPlaintext = true
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "directed".toByteArray()), peerID, "link"))

        val captor = argumentCaptor<BitchatMessage>()
        verify(delegate).onMessageReceived(captor.capture())
        assertTrue(captor.firstValue.isPrivate)
        assertEquals(peerID, captor.firstValue.senderPeerID)
    }

    @Test
    fun `unsigned or oversized directed plain text is dropped`() = runBlocking {
        handler.acceptDirectedPlaintext = true
        val unsigned = packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "directed".toByteArray()).copy(signature = null)
        handler.handleMessage(RoutedPacket(unsigned, peerID, "link"))
        whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(false)
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), "forged".toByteArray()), peerID, "link"))
        whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(true)
        handler.handleMessage(RoutedPacket(packet(MessageType.MESSAGE, myPeerID.hexToBytes(), ByteArray(2_001) { 'a'.code.toByte() }), peerID, "link"))

        verify(delegate, never()).onMessageReceived(any())
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
