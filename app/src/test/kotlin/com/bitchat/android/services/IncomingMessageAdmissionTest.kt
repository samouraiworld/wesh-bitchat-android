package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.PrivateChatManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class IncomingMessageAdmissionTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private val repositoriesToClose = mutableListOf<ConversationRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "admission-test-${UUID.randomUUID()}.db"
        repository = ConversationRepository(
            context = context,
            dispatcher = Dispatchers.Unconfined,
            databaseName = databaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        repositoriesToClose += repository
        AppStateStore.setConversationRepositoryForTest(repository)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        IncomingMessageAdmission.installForTesting { emptySet() }
    }

    @After
    fun tearDown() {
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repositoriesToClose.forEach(ConversationRepository::closeForTest)
        context.deleteDatabase(databaseName)
        context.deleteDatabase("failing-$databaseName")
    }

    @Test
    fun `blocked public sender cannot enter shared state or reach downstream notification`() {
        val publicMessage = BitchatMessage(id = "blocked-public", sender = "Synthetic", content = "test", timestamp = Date(1), isRelay = false, senderPeerID = "peer-a")
        IncomingMessageAdmission.installForTesting { setOf("fingerprint-a") }
        assertFalse(IncomingMessageAdmission.admitToAppState(publicMessage, null) { if (it == "peer-a") "fingerprint-a" else null })
        assertTrue(AppStateStore.publicMessages.value.isEmpty())
        assertFalse(IncomingMessageAdmission.admitToAppState(publicMessage, null) { error("synthetic unavailable identity") })
        assertTrue(AppStateStore.publicMessages.value.isEmpty())
        assertTrue(IncomingMessageAdmission.admitToAppState(publicMessage, null) { "fingerprint-other" })
        assertEquals(listOf("blocked-public"), AppStateStore.publicMessages.value.map { it.id })
    }

    @Test
    fun `private message rejected during panic cannot continue transport dispatch`() {
        assertTrue(runBlocking { AppStateStore.panicClearPrivateConversations() })

        assertFalse(
            IncomingMessageAdmission.admitToAppState(
                privateMessage(id = "during-panic"), null
            ) { null }
        )
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
    }

    @Test
    fun `duplicate private transport delivery is rejected before downstream effects`() {
        val message = privateMessage(id = "same-message-over-two-transports")

        assertTrue(IncomingMessageAdmission.admitToAppState(message, null) { null })
        assertFalse(IncomingMessageAdmission.admitToAppState(message, null) { null })
    }

    @Test
    fun `older retained replay is rejected after summary-only restart`() {
        val older = privateMessage(id = "older-retained")
        val latest = privateMessage(id = "latest-summary").copy(timestamp = Date(2L))
        assertTrue(IncomingMessageAdmission.admitToAppState(older, null) { null })
        assertTrue(IncomingMessageAdmission.admitToAppState(latest, null) { null })

        AppStateStore.clear()
        repository.reload { snapshot ->
            AppStateStore.restorePrivateConversations(snapshot)
        }
        runBlocking { repository.awaitPendingWrites() }
        assertEquals(
            listOf(latest.id),
            AppStateStore.privateMessages.value.getValue("peer-a").map { it.id }
        )

        assertFalse(IncomingMessageAdmission.admitToAppState(older, null) { null })
        assertEquals(
            listOf(latest.id),
            AppStateStore.privateMessages.value.getValue("peer-a").map { it.id }
        )
    }

    @Test
    fun `delivery receipt persists after older message is unloaded from memory`() {
        val older = privateMessage(id = "older-outgoing").copy(
            sender = "me",
            senderPeerID = "self",
            recipientNickname = "alice",
            deliveryStatus = DeliveryStatus.Sent
        )
        val latest = privateMessage(id = "latest-summary").copy(timestamp = Date(2L))
        assertTrue(AppStateStore.addPrivateMessage("peer-a", older, forceRead = true))
        assertTrue(IncomingMessageAdmission.admitToAppState(latest, null) { null })
        runBlocking { repository.awaitPendingWrites() }

        AppStateStore.releasePrivateConversationHistory("peer-a")
        assertEquals(
            listOf(latest.id),
            AppStateStore.privateMessages.value.getValue("peer-a").map { it.id }
        )

        val delivered = DeliveryStatus.Delivered(to = "alice", at = Date(3L))
        AppStateStore.updatePrivateMessageStatus(older.id, delivered)
        runBlocking { repository.awaitPendingWrites() }

        val snapshot = runBlocking {
            repository.loadConversationAndWait("peer-a")
        }
        assertEquals(
            delivered,
            snapshot?.chats?.getValue("peer-a")
                ?.single { it.id == older.id }
                ?.deliveryStatus
        )
    }

    @Test
    fun `public messages are admitted and BitChat channel messages are dropped`() {
        val public = BitchatMessage(
            id = "public",
            sender = "alice",
            content = "hello",
            timestamp = Date(1L)
        )
        val channel = public.copy(id = "channel", content = "in a room", channel = "#mesh")
        val encryptedChannel = channel.copy(
            id = "encrypted-channel",
            content = "",
            encryptedContent = byteArrayOf(1, 2, 3),
            isEncrypted = true
        )

        assertTrue(IncomingMessageAdmission.admitToAppState(public, null) { null })
        assertFalse(IncomingMessageAdmission.admitToAppState(channel, null) { null })
        assertFalse(IncomingMessageAdmission.admitToAppState(encryptedChannel, null) { null })
        assertTrue(AppStateStore.publicMessages.value.contains(public))
        assertTrue(AppStateStore.publicMessages.value.none { it.id == channel.id || it.id == encryptedChannel.id })
    }

    @Test
    fun `outgoing callback runs only after local echo survives process state clear`() {
        val state = ChatState(TestScope())
        state.setNickname("me")
        val manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(context),
            noiseSessionDelegate = testNoiseDelegate()
        )
        var callbackInvoked = false

        val sent = runBlocking {
            manager.sendPrivateMessageDurably(
                content = "durable outgoing",
                peerID = "peer-a",
                recipientNickname = "alice",
                senderNickname = "me",
                myPeerID = "self"
            ) { _, _, _, _ ->
                callbackInvoked = true
            }
        }

        assertTrue(sent)
        assertTrue(callbackInvoked)
        AppStateStore.clear()
        repository.reload { snapshot ->
            assertEquals(
                "durable outgoing",
                snapshot.chats.getValue("peer-a").single().content
            )
        }
        runBlocking { repository.awaitPendingWrites() }
    }

    @Test
    fun `outgoing messages to different peers keep separate persisted conversations`() {
        val state = ChatState(TestScope())
        state.setNickname("me")
        val manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(context),
            noiseSessionDelegate = testNoiseDelegate()
        )

        runBlocking {
            assertTrue(
                manager.sendPrivateMessageDurably(
                    content = "only for alice",
                    peerID = "peer-a",
                    recipientNickname = "alice",
                    senderNickname = "me",
                    myPeerID = "self"
                ) { _, _, _, _ -> }
            )
            assertTrue(
                manager.sendPrivateMessageDurably(
                    content = "only for bob",
                    peerID = "peer-b",
                    recipientNickname = "bob",
                    senderNickname = "me",
                    myPeerID = "self"
                ) { _, _, _, _ -> }
            )

            val alice = repository.loadConversationAndWait("peer-a")
            val bob = repository.loadConversationAndWait("peer-b")

            assertEquals(
                listOf("only for alice"),
                alice?.chats?.getValue("peer-a")?.map { it.content }
            )
            assertEquals(
                listOf("only for bob"),
                bob?.chats?.getValue("peer-b")?.map { it.content }
            )
        }
    }

    @Test
    fun `outgoing callback is suppressed when durable storage fails`() {
        val failingRepository = ConversationRepository(
            context = context,
            dispatcher = Dispatchers.Unconfined,
            databaseName = "failing-$databaseName",
            storageCipher = object : ConversationStorageCipher {
                override fun encrypt(
                    plaintext: ByteArray,
                    associatedData: ByteArray
                ): ByteArray = error("storage unavailable")

                override fun decrypt(
                    envelope: ByteArray,
                    associatedData: ByteArray
                ): ByteArray = error("storage unavailable")

                override fun destroyKey() = Unit
            }
        )
        repositoriesToClose += failingRepository
        AppStateStore.setConversationRepositoryForTest(failingRepository)
        val state = ChatState(TestScope())
        val manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(context),
            noiseSessionDelegate = testNoiseDelegate()
        )
        var callbackInvoked = false

        val sent = runBlocking {
            manager.sendPrivateMessageDurably(
                "lost",
                "peer-a",
                "alice",
                "me",
                "self"
            ) { _, _, _, _ -> callbackInvoked = true }
        }

        assertFalse(sent)
        assertFalse(callbackInvoked)
        assertTrue(state.getPrivateChatsValue().isEmpty())
    }

    private fun privateMessage(id: String) = BitchatMessage(
        id = id,
        sender = "alice",
        content = "secret",
        timestamp = Date(1L),
        isPrivate = true,
        senderPeerID = "peer-a"
    )

    private fun testNoiseDelegate() = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String) = false
        override fun initiateHandshake(peerID: String) = Unit
        override fun getMyPeerID() = "self"
    }
}
