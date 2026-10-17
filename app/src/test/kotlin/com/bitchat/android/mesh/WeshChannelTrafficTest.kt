package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Wesh has no named channels. A BitChat peer leaving a "#channel" sends a LEAVE whose payload is
 * the channel tag: it must change nothing here (no eviction, no state) while the mesh keeps
 * relaying it exactly like any other LEAVE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class WeshChannelTrafficTest {
    private lateinit var handler: MessageHandler
    private lateinit var handlerDelegate: MessageHandlerDelegate
    private val processors = mutableListOf<PacketProcessor>()

    @Before
    fun setUp() {
        handler = MessageHandler(MY_PEER_ID, RuntimeEnvironment.getApplication())
        handlerDelegate = mock()
        handler.delegate = handlerDelegate
    }

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
        handler.shutdown()
    }

    @Test
    fun `a BitChat channel leave touches no state`() = runBlocking {
        handler.handleLeave(leave("#general"))
        handler.handleLeave(leave("#"))

        verifyNoInteractions(handlerDelegate)
    }

    @Test
    fun `a plain peer leave still evicts the peer`() = runBlocking {
        handler.handleLeave(leave(""))

        verify(handlerDelegate).removePeer(PEER_ID)
    }

    @Test
    fun `a channel leave through the packet processor is relayed but changes nothing`() = runBlocking {
        val delegate = ForwardingDelegate(handler)
        PacketProcessor(MY_PEER_ID).also {
            it.delegate = delegate
            processors += it
        }.processPacket(leave("#general"))

        withTimeout(1_000) { delegate.leaveHandled.await() }
        withTimeout(1_000) { while (delegate.relayCount == 0) delay(10) }
        delay(100)

        assertEquals(1, delegate.relayCount)
        verifyNoInteractions(handlerDelegate)
    }

    private fun leave(payload: String) = RoutedPacket(
        BitchatPacket(
            version = 1u,
            type = MessageType.LEAVE.value,
            senderID = PEER_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload.toByteArray(Charsets.UTF_8),
            ttl = 7u
        ),
        PEER_ID,
        "direct-link"
    )

    /** Security already passed; LEAVE goes to the real [MessageHandler]. */
    private class ForwardingDelegate(private val handler: MessageHandler) : PacketProcessorDelegate {
        val leaveHandled = CompletableDeferred<Unit>()
        @Volatile var relayCount = 0

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket) = false
        override fun handleNoiseEncrypted(routed: RoutedPacket) = false
        override suspend fun handleAnnounce(routed: RoutedPacket) = false
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) {
            runBlocking { handler.handleLeave(routed) }
            leaveHandled.complete(Unit)
        }
        override fun handleFragment(packet: BitchatPacket): FragmentResult = FragmentResult.Skipped
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayCount += 1
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val PEER_ID = "aaaabbbbccccdddd"
    }
}
