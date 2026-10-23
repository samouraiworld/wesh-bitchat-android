package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MeshDelegateHandlerStateContractTest {
    private lateinit var state: ChatState
    private lateinit var messages: MessageManager
    private lateinit var privateChats: PrivateChatManager
    private lateinit var notifications: NotificationManager
    private lateinit var mesh: MeshService
    private lateinit var handler: MeshDelegateHandler
    private lateinit var haptics: AtomicInteger
    private lateinit var locallyReadMessageIDs: MutableList<String>

    @Before
    fun setUp() {
        val scope = TestScope(UnconfinedTestDispatcher())
        state = ChatState(scope)
        state.setNickname("Résumé")
        messages = MessageManager(state)
        privateChats = mock()
        notifications = mock()
        mesh = mock()
        haptics = AtomicInteger()
        locallyReadMessageIDs = mutableListOf()
        handler = MeshDelegateHandler(
            state = state,
            messageManager = messages,
            privateChatManager = privateChats,
            notificationManager = notifications,
            coroutineScope = scope,
            onHapticFeedback = { haptics.incrementAndGet() },
            getMeshService = { mesh },
            markMessageReadLocally = { id, _ -> locallyReadMessageIDs.add(id) }
        )
    }

    @Test
    fun `peer arrival deduplicates list and final departure restores disconnected state`() {
        handler.didUpdatePeerList(listOf("peer-a", "peer-a", "peer-b"))

        assertEquals(listOf("peer-a", "peer-b"), state.connectedPeers.value)
        assertTrue(state.isConnected.value)

        handler.didUpdatePeerList(emptyList())

        assertTrue(state.connectedPeers.value.isEmpty())
        assertFalse(state.isConnected.value)
    }

    @Test
    fun `delivery and read callbacks advance visible status monotonically`() {
        val outgoing = message(
            id = "outgoing",
            sender = "me",
            deliveryStatus = DeliveryStatus.Sending
        )
        state.setMessages(listOf(outgoing))

        handler.didReceiveDeliveryAck("outgoing", "peer-a")
        assertTrue(state.messages.value.single().deliveryStatus is DeliveryStatus.Delivered)

        handler.didReceiveReadReceipt("outgoing", "peer-a")
        assertTrue(state.messages.value.single().deliveryStatus is DeliveryStatus.Read)

        handler.didReceiveDeliveryAck("outgoing", "peer-a")
        assertTrue(state.messages.value.single().deliveryStatus is DeliveryStatus.Read)
    }

    @Test
    fun `an Internet message arriving in the open chat is read, and its READ goes back over Internet`() {
        val conversationID = com.bitchat.android.services.ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 3 })
        val incoming = BitchatMessage(
            id = "focused-internet-message", sender = "alice", content = "salut", timestamp = Date(1),
            isPrivate = true, senderPeerID = conversationID, transport = com.bitchat.android.model.MessageTransport.INTERNET
        )
        val sent = mutableListOf<Pair<String, String>>()
        val token = com.bitchat.android.services.SeenReceiptAdmission().capture()
        handler = MeshDelegateHandler(
            state = state,
            messageManager = messages,
            privateChatManager = privateChats,
            notificationManager = notifications,
            coroutineScope = TestScope(UnconfinedTestDispatcher()),
            onHapticFeedback = {},
            getMeshService = { mesh },
            markMessageReadLocally = { id, _ -> locallyReadMessageIDs.add(id) },
            captureSeenToken = { token },
            sendInternetReadReceipt = { id, conversation, expectedToken ->
                assertTrue(expectedToken === token)
                sent += id to conversation
            }
        )
        whenever(notifications.getAppBackgroundState()).thenReturn(false)
        whenever(notifications.getCurrentPrivateChatPeer()).thenReturn(conversationID)

        handler.readInternetMessageIfFocused(incoming)

        assertEquals(listOf(incoming.id to conversationID), sent)
        assertEquals(listOf(incoming.id), locallyReadMessageIDs)
        verify(mesh, never()).sendReadReceipt(any(), any(), any())

        // Another chat open, or the app in the background: unread, no READ.
        whenever(notifications.getCurrentPrivateChatPeer()).thenReturn(
            com.bitchat.android.services.ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 4 })
        )
        handler.readInternetMessageIfFocused(incoming.copy(id = "other-chat-open"))
        whenever(notifications.getCurrentPrivateChatPeer()).thenReturn(conversationID)
        whenever(notifications.getAppBackgroundState()).thenReturn(true)
        handler.readInternetMessageIfFocused(incoming.copy(id = "in-background"))
        assertEquals(1, sent.size)
        assertEquals(1, locallyReadMessageIDs.size)
    }

    @Test
    fun `focused private message schedules receipt and records local read independently`() {
        val peerID = "1122334455667788"
        val incoming = BitchatMessage(
            id = "focused-private-message",
            sender = "alice",
            content = "hello",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = peerID
        )
        whenever(notifications.getAppBackgroundState()).thenReturn(false)
        whenever(notifications.getCurrentPrivateChatPeer()).thenReturn(peerID)
        whenever(mesh.getPeerInfo(peerID)).thenReturn(
            PeerInfo(
                id = peerID,
                nickname = "alice",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = ByteArray(32) { 1 },
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = System.currentTimeMillis()
            )
        )
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(true)

        handler.didReceiveMessage(incoming)

        verify(mesh).sendReadReceipt(incoming.id, peerID, "Résumé")
        assertEquals(listOf(incoming.id), locallyReadMessageIDs)

        // With read receipts off, both Bluetooth and Internet arrivals in the open chat are read locally without sending READ.
        org.mockito.Mockito.clearInvocations(mesh)
        locallyReadMessageIDs.clear()
        val internetSent = mutableListOf<String>()
        handler = MeshDelegateHandler(
            state = state,
            messageManager = messages,
            privateChatManager = privateChats,
            notificationManager = notifications,
            coroutineScope = TestScope(UnconfinedTestDispatcher()),
            onHapticFeedback = {},
            getMeshService = { mesh },
            markMessageReadLocally = { id, _ -> locallyReadMessageIDs.add(id) },
            sendInternetReadReceipt = { id, _, _ -> internetSent += id },
            readReceiptsEnabled = { false }
        )
        handler.didReceiveMessage(incoming.copy(id = "ble-muted-receipt", content = "hello again", timestamp = Date(2)))
        handler.readInternetMessageIfFocused(
            incoming.copy(id = "net-muted-receipt", content = "hello net", timestamp = Date(3), transport = com.bitchat.android.model.MessageTransport.INTERNET)
        )
        verify(mesh, never()).sendReadReceipt(any(), any(), any())
        assertTrue(internetSent.isEmpty())
        assertEquals(listOf("ble-muted-receipt", "net-muted-receipt"), locallyReadMessageIDs)
    }

    @Test
    fun `focused private message remains locally read when transport is disconnected`() {
        val peerID = "1122334455667788"
        val incoming = BitchatMessage(
            id = "focused-private-message-offline",
            sender = "alice",
            content = "hello",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = peerID
        )
        whenever(notifications.getAppBackgroundState()).thenReturn(false)
        whenever(notifications.getCurrentPrivateChatPeer()).thenReturn(peerID)
        whenever(mesh.getPeerInfo(peerID)).thenReturn(null)

        handler.didReceiveMessage(incoming)

        verify(mesh, never()).sendReadReceipt(any(), any(), any())
        assertEquals(listOf(incoming.id), locallyReadMessageIDs)
    }

    @Test
    fun `a private message from a sender without a nickname notifies with no name, not the peer ID`() {
        val peerID = "1122334455667788"
        whenever(notifications.getAppBackgroundState()).thenReturn(true)
        listOf(peerID, "").forEachIndexed { index, sender ->
            handler.didReceiveMessage(BitchatMessage(id = "unnamed-$index", sender = sender, content = "hello $index",
                timestamp = Date(1), isPrivate = true, senderPeerID = peerID))
        }
        verify(notifications, times(2)).showPrivateMessageNotification(eq(peerID), org.mockito.kotlin.isNull(), any())
    }

    @Test
    fun `unicode mention notifies once and duplicate transport delivery is suppressed`() {
        val incoming = message(
            id = "incoming",
            sender = "alice",
            content = "hello @résumé",
            senderPeerID = "peer-a"
        )

        handler.didReceiveMessage(incoming)
        handler.didReceiveMessage(incoming)

        assertEquals(1, haptics.get())
        verify(notifications, times(1)).showMeshMentionNotification(
            senderNickname = eq("alice"),
            messageContent = eq("hello @résumé"),
            senderPeerID = eq("peer-a")
        )
    }

    @Test
    fun `a BitChat channel message neither buzzes nor notifies nor reaches the public room`() {
        val incoming = message(
            id = "channel-1",
            content = "hello @résumé",
            senderPeerID = "peer-a",
            channel = "#room"
        )

        handler.didReceiveMessage(incoming)

        assertEquals(0, haptics.get())
        verify(notifications, never()).showMeshMentionNotification(any(), any(), any())
        assertTrue(state.messages.value.isEmpty())
    }

    private fun message(
        id: String,
        sender: String = "alice",
        content: String = id,
        senderPeerID: String? = null,
        channel: String? = null,
        deliveryStatus: DeliveryStatus? = null
    ) = BitchatMessage(
        id = id,
        sender = sender,
        content = content,
        timestamp = Date(1),
        senderPeerID = senderPeerID,
        channel = channel,
        deliveryStatus = deliveryStatus
    )
}
