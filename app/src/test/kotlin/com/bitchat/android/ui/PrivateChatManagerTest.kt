package com.bitchat.android.ui

import android.os.Build
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateChatManagerTest {
    private lateinit var state: ChatState
    private lateinit var manager: PrivateChatManager

    @Before
    fun setUp() {
        AppStateStore.clear()
        state = ChatState(TestScope())
        manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(RuntimeEnvironment.getApplication()),
            noiseSessionDelegate = mock()
        )
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun `nostr message is stored after sender alias canonicalizes to contact id`() {
        val conversationID =
            ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 7 })
        val message = BitchatMessage(
            id = "nostr-message",
            sender = "alice",
            content = "hello over nostr",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = conversationID
        )

        manager.handleIncomingPrivateMessage(
            message = message,
            suppressUnread = false,
            origin = PrivateMessageOrigin.NOSTR
        )

        assertEquals(listOf(message), state.getPrivateChatsValue()[conversationID])
    }

    @Test
    fun `headless Nostr processing stores messages without retaining UI unread work`() {
        val headlessManager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(RuntimeEnvironment.getApplication()),
            noiseSessionDelegate = mock(),
            trackUnreadMessages = false
        )
        val message = BitchatMessage(
            id = "background-nostr-message",
            sender = "alice",
            content = "background",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = "nostr_background"
        )

        headlessManager.handleIncomingPrivateMessage(
            message = message,
            suppressUnread = false,
            origin = PrivateMessageOrigin.NOSTR
        )

        assertEquals(listOf(message), AppStateStore.privateMessages.value["nostr_background"])
        assertTrue(state.getUnreadPrivateMessagesValue().isEmpty())
    }

    @Test
    fun `canonical conversation sends read receipt through live mesh peer id`() {
        val noiseKey = ByteArray(32) { 9 }
        val meshPeerID = ContactIdentityResolver.peerIdForNoiseKey(noiseKey)
        val conversationID = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey)
        val message = BitchatMessage(
            id = "mesh-message",
            sender = "alice",
            content = "hello over mesh",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = meshPeerID
        )
        val meshService = mock<MeshService>()
        val peerInfo = PeerInfo(
            id = meshPeerID,
            nickname = "alice",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = noiseKey,
            signingPublicKey = null,
            isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        )
        state.setNickname("bob")
        state.setPrivateChats(mapOf(conversationID to listOf(message)))
        whenever(meshService.getPeerInfo(meshPeerID)).thenReturn(peerInfo)
        whenever(meshService.hasEstablishedSession(meshPeerID)).thenReturn(true)

        manager.sendReadReceiptsForPeer(
            expectedSeenToken = null,
            conversationID = conversationID,
            meshPeerID = meshPeerID,
            meshService = meshService
        )

        verify(meshService).sendReadReceipt(message.id, meshPeerID, "bob")
    }

    @Test
    fun `a media placeholder is never reported read, a text beside it is`() {
        val noiseKey = ByteArray(32) { 7 }
        val meshPeerID = ContactIdentityResolver.peerIdForNoiseKey(noiseKey)
        val conversationID = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey)
        val text = BitchatMessage(
            id = "text-message", sender = "alice", content = "hello", timestamp = Date(1),
            isPrivate = true, senderPeerID = meshPeerID
        )
        val placeholder = text.copy(
            id = "media-placeholder", content = "", type = com.bitchat.android.model.BitchatMessageType.File
        )
        val meshService = mock<MeshService>()
        whenever(meshService.getPeerInfo(meshPeerID)).thenReturn(PeerInfo(
            id = meshPeerID, nickname = "alice", isConnected = true, isDirectConnection = true,
            noisePublicKey = noiseKey, signingPublicKey = null, isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        ))
        whenever(meshService.hasEstablishedSession(meshPeerID)).thenReturn(true)
        state.setNickname("bob")
        state.setPrivateChats(mapOf(conversationID to listOf(placeholder, text)))

        manager.sendReadReceiptsForPeer(conversationID, meshPeerID, meshService, expectedSeenToken = null)

        verify(meshService).sendReadReceipt(text.id, meshPeerID, "bob")
        verify(meshService, never()).sendReadReceipt(eq(placeholder.id), any(), any())
    }

    @Test
    fun `opening canonical unread conversation clears all source aliases`() {
        val canonicalID = "contact_alice"
        val nostrAlias = "nostr_0123456789abcdef"
        val meshAlias = "0123456789abcdef"
        val unrelatedConversation = "other-contact"
        val meshService = mock<MeshService>()
        state.setUnreadPrivateMessages(
            setOf(canonicalID, nostrAlias, meshAlias, unrelatedConversation)
        )

        manager.startPrivateChat(
            expectedSeenToken = null,
            peerID = canonicalID,
            meshService = meshService,
            unreadAliases = setOf(canonicalID, nostrAlias, meshAlias)
        )

        assertEquals(
            setOf(unrelatedConversation),
            state.getUnreadPrivateMessagesValue()
        )
    }

    @Test
    fun `opening a chat sends READ over Internet for Internet messages not read yet, out of Bluetooth range`() {
        val noiseKey = ByteArray(32) { 9 }
        val conversationID = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey)
        val internet = BitchatMessage(
            id = "net-unread", sender = "alice", content = "salut", timestamp = Date(1), isPrivate = true,
            senderPeerID = conversationID, transport = com.bitchat.android.model.MessageTransport.INTERNET
        )
        val alreadyRead = internet.copy(id = "net-read", timestamp = Date(2))
        val overBluetooth = internet.copy(id = "ble-unread", timestamp = Date(3),
            transport = com.bitchat.android.model.MessageTransport.BLUETOOTH)
        val sent = mutableListOf<Triple<String, String, Any?>>()
        val token = com.bitchat.android.services.SeenReceiptAdmission().capture()
        manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(RuntimeEnvironment.getApplication()),
            noiseSessionDelegate = mock(),
            hasReadReceiptBeenSent = { it == alreadyRead.id },
            sendInternetReadReceipt = { id, conversation, expectedToken -> sent += Triple(id, conversation, expectedToken) }
        )
        state.setNickname("bob")
        state.setPrivateChats(mapOf(conversationID to listOf(internet, alreadyRead, overBluetooth)))
        val meshService = mock<MeshService>()

        manager.sendReadReceiptsForPeer(conversationID, meshPeerID = null, meshService, expectedSeenToken = token)

        // Only the unread Internet message: a Bluetooth one waits for Bluetooth (the ack goes back the way it came).
        assertEquals(listOf(Triple(internet.id, conversationID, token)), sent)
        verify(meshService, never()).sendReadReceipt(any(), any(), any())

        // In Bluetooth range too: the Internet message still goes over Internet, the Bluetooth one over Bluetooth.
        sent.clear()
        val meshPeerID = ContactIdentityResolver.peerIdForNoiseKey(noiseKey)
        whenever(meshService.getPeerInfo(meshPeerID)).thenReturn(
            PeerInfo(id = meshPeerID, nickname = "alice", isConnected = true, isDirectConnection = true,
                noisePublicKey = noiseKey, signingPublicKey = null, isVerifiedNickname = false,
                lastSeen = System.currentTimeMillis())
        )
        whenever(meshService.hasEstablishedSession(meshPeerID)).thenReturn(true)
        manager.sendReadReceiptsForPeer(conversationID, meshPeerID, meshService, expectedSeenToken = token)
        assertEquals(listOf(Triple(internet.id, conversationID, token)), sent)
        verify(meshService, never()).sendReadReceipt(eq(internet.id), any(), any())
        verify(meshService).sendReadReceipt(overBluetooth.id, meshPeerID, "bob")

        // With read receipts turned off, opening the chat marks messages read locally without sending READ on either path.
        sent.clear()
        org.mockito.Mockito.clearInvocations(meshService)
        val locallyRead = mutableListOf<String>()
        manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(RuntimeEnvironment.getApplication()),
            noiseSessionDelegate = mock(),
            markMessageReadLocally = { id, _ -> locallyRead += id },
            sendInternetReadReceipt = { id, conversation, expectedToken -> sent += Triple(id, conversation, expectedToken) },
            readReceiptsEnabled = { false }
        )
        manager.sendReadReceiptsForPeer(conversationID, meshPeerID, meshService, expectedSeenToken = token)
        assertTrue(sent.isEmpty())
        verify(meshService, never()).sendReadReceipt(any(), any(), any())
        assertEquals(listOf(internet.id, alreadyRead.id, overBluetooth.id), locallyRead)
    }

    @Test
    fun `opening chat skips messages whose receipt send already completed`() {
        val noiseKey = ByteArray(32) { 8 }
        val meshPeerID = ContactIdentityResolver.peerIdForNoiseKey(noiseKey)
        val conversationID = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey)
        val oldMessage = BitchatMessage(
            id = "already-read",
            sender = "alice",
            content = "old",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = meshPeerID
        )
        val unreadMessage = oldMessage.copy(
            id = "still-unread",
            content = "new",
            timestamp = Date(2)
        )
        val meshService = mock<MeshService>()
        manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(RuntimeEnvironment.getApplication()),
            noiseSessionDelegate = mock(),
            hasReadReceiptBeenSent = { it == oldMessage.id }
        )
        state.setNickname("bob")
        state.setPrivateChats(mapOf(conversationID to listOf(oldMessage, unreadMessage)))
        whenever(meshService.getPeerInfo(meshPeerID)).thenReturn(
            PeerInfo(
                id = meshPeerID,
                nickname = "alice",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = noiseKey,
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = System.currentTimeMillis()
            )
        )
        whenever(meshService.hasEstablishedSession(meshPeerID)).thenReturn(true)

        manager.sendReadReceiptsForPeer(conversationID, meshPeerID, meshService, expectedSeenToken = null)

        verify(meshService, never()).sendReadReceipt(oldMessage.id, meshPeerID, "bob")
        verify(meshService).sendReadReceipt(unreadMessage.id, meshPeerID, "bob")
    }

    @Test
    fun `canonical conversation send does not require resolved nickname`() {
        val conversationID =
            ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 4 })
        var callbackInvoked = false

        manager.sendPrivateMessage(
            content = "hello",
            peerID = conversationID,
            recipientNickname = null,
            senderNickname = "bob",
            myPeerID = "self"
        ) { content, recipientID, nickname, _ ->
            callbackInvoked = true
            assertEquals("hello", content)
            assertEquals(conversationID, recipientID)
            assertEquals("", nickname)
        }

        assertTrue(callbackInvoked)
        assertEquals(
            "hello",
            state.getPrivateChatsValue()[conversationID]?.single()?.content
        )
    }

    @Test
    fun `opening a blocked peer in Wesh adds nothing to the public timeline`() {
        val peerID = "0123456789abcdef"
        com.bitchat.android.services.SessionHiddenPeers.hide(peerID)
        try {
            val wesh = PrivateChatManager(
                state = state,
                messageManager = MessageManager(state),
                dataManager = DataManager(RuntimeEnvironment.getApplication()),
                noiseSessionDelegate = mock(),
                weshPolicy = true
            )
            assertEquals(false, wesh.startPrivateChat(peerID = peerID, meshService = mock(), expectedSeenToken = null))
            assertTrue(state.getMessagesValue().isEmpty())
            assertTrue(AppStateStore.publicMessages.value.isEmpty())
        } finally {
            com.bitchat.android.services.SessionHiddenPeers.unhide(peerID)
        }
    }

    @Test
    fun `an unnamed remote private message stays unread when my nickname is empty`() {
        state.setNickname("")
        val remote = BitchatMessage(id = "unnamed-remote-manager", sender = "", content = "synthetic",
            timestamp = Date(1), isPrivate = true, senderPeerID = "0123456789abcdef")
        MessageManager(state).addPrivateMessage("0123456789abcdef", remote)
        assertTrue(state.getUnreadPrivateMessagesValue().isNotEmpty())
    }
}
