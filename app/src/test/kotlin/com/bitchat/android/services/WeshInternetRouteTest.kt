package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.RelayPublishOutcome
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Internet route to QR contacts: Tor-gated, npub from the QR only, BitChat envelope format. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshInternetRouteTest {
    private class FakeLink(var on: Boolean, val identity: NostrIdentity?) : WeshInternetLink {
        val published = CopyOnWriteArrayList<NostrEvent>()
        /** What the relays answer to a confirmed publish. */
        @Volatile var answer = RelayPublishOutcome.ACCEPTED
        /** Runs inside every isOn() call, to land a change at a precise point of a send. */
        @Volatile var onIsOn: (() -> Unit)? = null
        override fun isOn() = on.also { onIsOn?.invoke() }
        override fun myIdentity() = identity
        override fun publish(event: NostrEvent) { published += event }
        /** Set to hold a confirmed publish in flight until completed. */
        @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun publishConfirmed(event: NostrEvent): RelayPublishOutcome {
            if (answer != RelayPublishOutcome.NOT_SENT) published += event
            hold?.await()
            return answer
        }
    }

    /** One phone: its contact list, its Nostr key, its relay link, what it stored. */
    private inner class Phone(name: String, val nostr: NostrIdentity, val peerID: String) {
        val blocks: android.content.SharedPreferences =
            context.getSharedPreferences("synthetic-blocks-$name", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val contacts = WeshContactStore(
            SecureIdentityStateManager(context.getSharedPreferences("synthetic-id-$name", Context.MODE_PRIVATE)
                .also { it.edit().clear().commit() }, true),
            blocks
        )
        val link = FakeLink(on = true, identity = nostr)
        val route = WeshInternetRoute(contacts, link) { peerID }
        val stored = mutableListOf<Pair<String, BitchatMessage>>()
        val statuses = mutableListOf<Triple<String, String, DeliveryStatus>>()
        val acked = mutableSetOf<String>()
        val hidden = mutableSetOf<String>()
        var now: Long? = null
        fun newInbox() = WeshInternetInbox(contacts, { nostr }, route,
            deliver = { conversationID, message -> stored += conversationID to message; true },
            updateStatus = { conversationID, id, status -> statuses += Triple(conversationID, id, status) },
            alreadyAcked = { it in acked }, markAcked = { id, _ -> acked += id },
            senderBlocked = { it.noiseHex in hidden },
            nowSeconds = { now ?: (System.currentTimeMillis() / 1000) })
        var inbox = newInbox()

        fun enroll(noiseKey: ByteArray, npub: String?, warning: Boolean = false) {
            contacts.record(noiseKey, "Synthetic", WeshNpubPolicy.Enrollment(npub, npub != null, warning))
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val aliceNoise = ByteArray(32) { 1 }
    private val bobNoise = ByteArray(32) { 2 }
    private val aliceConversation = ContactIdentityResolver.contactConversationIdForNoiseKey(aliceNoise)
    private val bobConversation = ContactIdentityResolver.contactConversationIdForNoiseKey(bobNoise)
    private lateinit var alice: Phone
    private lateinit var bob: Phone

    @Before fun setup() {
        alice = Phone("alice", NostrIdentity.generate(), "1111222233334444")
        bob = Phone("bob", NostrIdentity.generate(), "5555666677778888")
        alice.enroll(bobNoise, bob.nostr.npub)
        bob.enroll(aliceNoise, alice.nostr.npub)
    }

    @Test fun `a QR contact with a bound npub gets a BitChat private envelope Bob can open`() = runTest {
        assertTrue(alice.route.canSend(bobConversation))
        assertEquals(RelayPublishOutcome.ACCEPTED, alice.route.sendPrivate("salut", bobConversation, "msg-1").outcome)
        val wrap = alice.link.published.single()
        val (content, sender, _) = NostrProtocol.decryptPrivateMessage(wrap, bob.nostr)!!
        assertEquals(alice.nostr.publicKeyHex, sender)
        assertTrue(content.startsWith("bitchat1:"))
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        val (conversation, message) = bob.stored.single()
        assertEquals(aliceConversation, conversation)
        assertEquals("msg-1", message.id)
        assertEquals("salut", message.content)
        assertTrue(message.isPrivate)
        assertEquals(com.bitchat.android.model.MessageTransport.INTERNET, message.transport)
    }

    @Test fun `the delivery ack goes back over Internet and updates only Alice's conversation`() = runTest {
        alice.route.sendPrivate("salut", bobConversation, "msg-2")
        bob.inbox.onGiftWrap(alice.link.published.single(), expectedToken = null)
        val ack = bob.link.published.single()
        assertEquals(WeshInternetInbox.Outcome.ACK_APPLIED, alice.inbox.onGiftWrap(ack, expectedToken = null))
        val (conversation, id, status) = alice.statuses.single()
        assertEquals(bobConversation, conversation)
        assertEquals("msg-2", id)
        assertTrue(status is DeliveryStatus.Delivered)
    }

    @Test fun `Tor or relays not ready fails closed and nothing is published`() = runTest {
        alice.link.on = false
        assertFalse(alice.route.canSend(bobConversation))
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("salut", bobConversation, "msg-3").outcome)
        assertFalse(alice.route.sendAck(NoisePayloadType.DELIVERED, "msg-3", bobConversation))
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `no npub, a conflict flag, a block or no enrollment never reach Internet`() = runTest {
        val strangerConversation = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", strangerConversation, "stranger").outcome)
        alice.enroll(bobNoise, null)
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", bobConversation, "bluetooth-only").outcome)
        alice.enroll(bobNoise, bob.nostr.npub, warning = true)
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", bobConversation, "flagged").outcome)
        alice.enroll(bobNoise, bob.nostr.npub)
        alice.blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(bobNoise))).commit()
        assertEquals(RelayPublishOutcome.NOT_SENT, alice.route.sendPrivate("x", bobConversation, "blocked").outcome)
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `a sender whose npub is not bound to a QR contact is dropped without an ack`() = runTest {
        val stranger = Phone("stranger", NostrIdentity.generate(), "9999aaaabbbbcccc")
        stranger.enroll(bobNoise, bob.nostr.npub)
        stranger.route.sendPrivate("spam", bobConversation, "spam-1")
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(stranger.link.published.single(), expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `a blocked or flagged contact is dropped without an ack`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "blocked-1")
        alice.route.sendPrivate("two", bobConversation, "flagged-1")
        val (first, second) = alice.link.published.toList()
        bob.blocks.edit().putStringSet("blocked_users", setOf(ContactIdentityResolver.fingerprintHex(aliceNoise))).commit()
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(first, expectedToken = null))
        bob.blocks.edit().clear().commit()
        bob.enroll(aliceNoise, alice.nostr.npub, warning = true)
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second, expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `the Bluetooth admission gate applies to Internet senders too, acks included`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "hidden-1")
        alice.route.sendPrivate("two", bobConversation, "hidden-2")
        val (first, second) = alice.link.published.toList()
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(first, expectedToken = null))
        val ack = bob.link.published.single()
        // Bob hides Alice: her next message is dropped with no store and no ack.
        bob.hidden += aliceNoise.joinToString("") { "%02x".format(it) }
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second, expectedToken = null))
        assertEquals(1, bob.stored.size)
        assertEquals(1, bob.link.published.size)
        // Alice hides Bob: his ack no longer updates anything.
        alice.hidden += bobNoise.joinToString("") { "%02x".format(it) }
        assertEquals(WeshInternetInbox.Outcome.DROPPED, alice.inbox.onGiftWrap(ack, expectedToken = null))
        assertTrue(alice.statuses.isEmpty())
    }

    @Test fun `a FAVORITED notice over Internet is never trusted`() = runTest {
        alice.route.sendPrivate("[FAVORITED]:${NostrIdentity.generate().npub}", bobConversation, "fav-1")
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(alice.link.published.single(), expectedToken = null))
        assertTrue(bob.stored.isEmpty())
    }

    @Test fun `replays and out-of-window timestamps are dropped`() = runTest {
        alice.route.sendPrivate("one", bobConversation, "window-1")
        alice.route.sendPrivate("two", bobConversation, "window-2")
        val (first, second) = alice.link.published.toList()
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(first, expectedToken = null))
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(first, expectedToken = null))
        bob.now = System.currentTimeMillis() / 1000 + WeshInternetInbox.LOOKBACK_SECONDS + 3600
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(second, expectedToken = null))
        assertEquals(1, bob.stored.size)
    }

    @Test fun `a READ for an Internet message goes back over Internet once, only to an enrolled contact, never after an erase`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        val recorded = mutableListOf<String>()
        router.markWeshReadReceiptSent = { id, _ -> recorded += id; true }
        router.hasWeshReadReceiptBeenSent = { it in recorded }
        val admission = SeenReceiptAdmission()
        try {
            assertTrue(kotlinx.coroutines.runBlocking { router.sendWeshInternetReadReceiptNow("r-1", bobConversation, admission.capture()) })
            assertEquals(WeshInternetInbox.Outcome.ACK_APPLIED,
                kotlinx.coroutines.runBlocking { bob.inbox.onGiftWrap(alice.link.published.single(), expectedToken = null) })
            val (conversation, id, status) = bob.statuses.single()
            assertEquals(aliceConversation, conversation)
            assertEquals("r-1", id)
            assertTrue(status is DeliveryStatus.Read)
            assertEquals(listOf("r-1"), recorded)
            // Never over Bluetooth: the ack goes back over the transport the message came on.
            verify(mesh, never()).sendReadReceipt(any(), any(), any())
            // Once: a second open or arrival for the same message sends nothing.
            assertFalse(kotlinx.coroutines.runBlocking { router.sendWeshInternetReadReceiptNow("r-1", bobConversation, admission.capture()) })
            // An erase while the wrap is built: it is never published.
            assertFalse(alice.route.sendAck(NoisePayloadType.READ_RECEIPT, "r-5", bobConversation) { false })

            // Internet off, or not an enrolled contact: nothing leaves, nothing is recorded.
            alice.link.on = false
            assertFalse(kotlinx.coroutines.runBlocking { router.sendWeshInternetReadReceiptNow("r-2", bobConversation, admission.capture()) })
            alice.link.on = true
            val stranger = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
            assertFalse(kotlinx.coroutines.runBlocking { router.sendWeshInternetReadReceiptNow("r-3", stranger, admission.capture()) })
            // Read before an erase, sent after it started: nothing leaves.
            val readBeforeErase = admission.capture()
            admission.fenceForPanic()
            assertFalse(kotlinx.coroutines.runBlocking { router.sendWeshInternetReadReceiptNow("r-4", bobConversation, readBeforeErase) })
            // User turned read receipts off: nothing leaves over Internet or Bluetooth, nothing is recorded.
            val afterReopen = SeenReceiptAdmission()
            router.weshReadReceiptsEnabled = { false }
            assertFalse(kotlinx.coroutines.runBlocking { router.sendWeshInternetReadReceiptNow("r-5", bobConversation, afterReopen.capture()) })
            router.sendReadReceipt(com.bitchat.android.model.ReadReceipt("r-6", bobConversation), bobConversation)
            assertEquals(1, alice.link.published.size)
            assertEquals(listOf("r-1"), recorded)
        } finally {
            router.stopOutboxScheduler()
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    @Test fun `router prefers the live Bluetooth session, then Internet, else queues`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        try {
            alice.link.on = false
            assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("queued", bobConversation, "Bob", "q-1"))
            router.tickOutbox()
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())

            alice.link.on = true
            router.tickOutbox()
            waitFor { alice.link.published.size == 1 }
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("direct", bobConversation, "Bob", "n-1"))
            waitFor { alice.link.published.size == 2 }
            verify(mesh, never()).sendPrivateMessage(any(), any(), any(), any())

            val stranger = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
            assertEquals(MessageRouter.RouteResult.DROPPED, router.sendPrivate("x", stranger, "Stranger", "d-1"))

            // Bob comes into Bluetooth range with the enrolled key: the mesh wins, Internet is untouched.
            val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
            whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob"))
            whenever(mesh.getPeerInfo(bobPeer)).thenReturn(PeerInfo(id = bobPeer, nickname = "Bob", isConnected = true,
                noisePublicKey = bobNoise, isDirectConnection = true, signingPublicKey = ByteArray(32),
                isVerifiedNickname = true, lastSeen = 1L))
            whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
            kotlinx.coroutines.runBlocking {
                whenever(mesh.sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any()))
                    .thenReturn(com.bitchat.android.mesh.BoundSendOutcome.SENT)
            }
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("near", bobConversation, "Bob", "m-1"))
            Thread.sleep(200)
            assertEquals(2, alice.link.published.size)
        } finally {
            router.stopOutboxScheduler()
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `an unconfirmed send is republished as the same signed gift wrap`() = runTest {
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        val first = alice.route.sendPrivate("salut", bobConversation, "same-1")
        assertEquals(RelayPublishOutcome.UNCONFIRMED, first.outcome)
        alice.link.answer = RelayPublishOutcome.ACCEPTED
        val second = alice.route.sendPrivate("salut", bobConversation, "same-1", previousWrap = first.giftWrap)

        assertEquals(RelayPublishOutcome.ACCEPTED, second.outcome)
        assertEquals(2, alice.link.published.size)
        assertEquals(alice.link.published[0].id, alice.link.published[1].id)
    }

    @Test fun `a wrap too old for the receivers' lookback, or for an old npub, is wrapped again`() = runTest {
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        val wrap = alice.route.sendPrivate("salut", bobConversation, "old-1").giftWrap!!
        val stale = wrap.createdAt + AppConstants.Router.WESH_INTERNET_WRAP_REUSE_MAX_AGE_S
        alice.route.sendPrivate("salut", bobConversation, "old-1", previousWrap = wrap, nowSeconds = stale.toLong())
        val newNpub = NostrIdentity.generate()
        alice.enroll(bobNoise, newNpub.npub)
        alice.route.sendPrivate("salut", bobConversation, "old-1", previousWrap = wrap)

        assertEquals(3, alice.link.published.map { it.id }.distinct().size)
        assertNotNull(NostrProtocol.decryptPrivateMessage(alice.link.published[2], newNpub))
    }

    @Test fun `the Internet retry backs off from 30 s and is capped at 30 min`() {
        val delays = (1..10).map(AppConstants.Router::weshInternetRetryDelayMs)
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L,
            1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L), delays)
    }

    @Test fun `Envoyé only after a relay OK, and failed publishes stay pending with a backoff`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        var now = 1_000_000L
        router.clock = { now }
        fun status() = AppStateStore.privateMessages.value.values.flatten().single { it.id == "ok-1" }.deliveryStatus
        fun tickUntil(count: Int) {
            router.tickOutbox(now)
            waitFor { alice.link.published.size == count }
            Thread.sleep(100)
        }
        try {
            AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "ok-1", sender = "me", content = "salut",
                timestamp = java.util.Date(), isRelay = false, isPrivate = true, recipientNickname = "Bob",
                senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
            // Explicit rejection: pending, then a fresh wrap after 30 s.
            alice.link.answer = RelayPublishOutcome.REJECTED
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "ok-1"))
            waitFor { alice.link.published.size == 1 }
            Thread.sleep(100)
            assertEquals(DeliveryStatus.Sending, status())
            now += 29_999
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(1, alice.link.published.size)

            // Timeout: the same signed wrap goes out again, 60 s later.
            alice.link.answer = RelayPublishOutcome.UNCONFIRMED
            now += 1
            tickUntil(2)
            assertNotEquals(alice.link.published[0].id, alice.link.published[1].id)
            now += 59_999
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(2, alice.link.published.size)

            // Nothing could leave (no relay connected): no backoff step is spent.
            alice.link.answer = RelayPublishOutcome.NOT_SENT
            now += 1
            router.tickOutbox(now)
            Thread.sleep(200)
            alice.link.answer = RelayPublishOutcome.ACCEPTED
            tickUntil(3)
            assertEquals(alice.link.published[1].id, alice.link.published[2].id)
            waitFor { status() == DeliveryStatus.Sent }
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `the composer sends to any enrolled contact, queued without a route, over Internet once it is on`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        val compose = WeshPrivateCompose(WeshPrivateSender(alice.contacts)) { router }
        fun status(id: String) = AppStateStore.privateMessages.value.values.flatten().single { it.id == id }.deliveryStatus
        fun own(id: String) = AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = id, sender = "me",
            content = "salut", timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
            senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
        try {
            // No Bluetooth session, Internet off: the contact is still writable and the message waits.
            alice.link.on = false
            assertTrue(compose.canCompose(bobConversation))
            own("c-1")
            assertEquals(MessageRouter.RouteResult.QUEUED, compose.route("salut", bobConversation, "Bob", "c-1"))
            // Réessayer on a waiting message replaces it in the queue instead of adding a copy.
            assertEquals(MessageRouter.RouteResult.QUEUED, compose.route("salut", bobConversation, "Bob", "c-1"))
            assertEquals(DeliveryStatus.Sending, status("c-1"))

            alice.link.on = true
            router.tickOutbox()
            waitFor { status("c-1") == DeliveryStatus.Sent }
            Thread.sleep(200)
            assertEquals(1, alice.link.published.size)

            // A stranger is never writable; a forced send fails visibly.
            val stranger = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 9 })
            assertFalse(compose.canCompose(stranger))
            own("c-2")
            alice.contacts.remove(bobConversation)
            assertEquals(MessageRouter.RouteResult.DROPPED, compose.route("salut", bobConversation, "Bob", "c-2"))
            assertTrue(status("c-2") is DeliveryStatus.Failed)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a conversation deleted while its Internet send is in flight never sends it again`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        var now = 1_000_000L
        router.clock = { now }
        try {
            val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
            alice.link.hold = hold
            alice.link.answer = RelayPublishOutcome.UNCONFIRMED
            AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "del-1", sender = "me", content = "salut",
                timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
                senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "del-1"))
            waitFor { alice.link.published.size == 1 }
            router.cancelWeshConversation(bobConversation)
            hold.complete(Unit)
            Thread.sleep(200)
            alice.link.hold = null
            now += AppConstants.Router.WESH_INTERNET_RETRY_MAX_MS
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(1, alice.link.published.size)
            // Never a false « En attente »: the cancelled attempt ends « Échec ».
            assertTrue(AppStateStore.privateMessages.value.values.flatten().single { it.id == "del-1" }.deliveryStatus
                is DeliveryStatus.Failed)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `Réessayer on a waiting message keeps its 24 h deadline`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        val expired = CopyOnWriteArrayList<String>()
        router.onMessageExpired = { _, id -> expired += id }
        var now = 1_000_000L
        router.clock = { now }
        try {
            alice.link.on = false
            assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("salut", bobConversation, "Bob", "ttl-1"))
            now += 23L * 3600_000L
            assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("salut", bobConversation, "Bob", "ttl-1"))
            now += 3600_000L + 1
            router.tickOutbox(now)
            assertEquals(listOf("ttl-1"), expired)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    /** Bob in Bluetooth range over a bound session whose write ends with [outcome]. */
    private fun failingBluetoothRouter(
        outcome: com.bitchat.android.mesh.BoundSendOutcome = com.bitchat.android.mesh.BoundSendOutcome.NOT_SENT
    ): MessageRouter {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob"))
        whenever(mesh.hasEstablishedSession(bobPeer)).thenReturn(true)
        whenever(mesh.getPeerInfo(bobPeer)).thenReturn(PeerInfo(id = bobPeer, nickname = "Bob", isConnected = true,
            noisePublicKey = bobNoise, isDirectConnection = true, signingPublicKey = ByteArray(32),
            isVerifiedNickname = true, lastSeen = 1L))
        whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any())).thenReturn(outcome)
        }
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "bw-1", sender = "me", content = "salut",
            timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
            senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
        return MessageRouter(context, mesh, mock()).also { it.weshInternetLink = alice.link }
    }

    private fun bw1() = AppStateStore.privateMessages.value.values.flatten().single { it.id == "bw-1" }.deliveryStatus

    @Test fun `a failed Bluetooth write goes over Internet when that route exists`() {
        val router = failingBluetoothRouter()
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { bw1() == DeliveryStatus.Sent }
            assertEquals(1, alice.link.published.size)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a conversation deleted during a failing Bluetooth write never goes over Internet`() {
        val router = failingBluetoothRouter()
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        try {
            kotlinx.coroutines.runBlocking {
                whenever(router.meshForTesting.sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any())).thenAnswer {
                    router.cancelWeshConversation(bobConversation); com.bitchat.android.mesh.BoundSendOutcome.NOT_SENT
                }
            }
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { bw1() is DeliveryStatus.Failed }
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())
            assertNotNull(bobPeer)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a queued message whose Bluetooth write fails keeps its wrap, backoff and deadline over Internet`() {
        val router = failingBluetoothRouter()
        val mesh = router.meshForTesting
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        var now = 1_000_000L
        router.clock = { now }
        try {
            // No Bluetooth session yet: the first attempt goes over Internet and is not confirmed.
            whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(null)
            alice.link.answer = RelayPublishOutcome.UNCONFIRMED
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { alice.link.published.size == 1 }
            Thread.sleep(100)
            // Bob comes into range but the write fails: no Internet send before the backoff.
            whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
            router.tickOutbox(now)
            Thread.sleep(200)
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(1, alice.link.published.size)
            // After the backoff: the same signed wrap, then « Envoyé ».
            alice.link.answer = RelayPublishOutcome.ACCEPTED
            now += AppConstants.Router.WESH_INTERNET_RETRY_BASE_MS
            router.tickOutbox(now)
            waitFor { bw1() == DeliveryStatus.Sent }
            assertEquals(2, alice.link.published.size)
            assertEquals(alice.link.published[0].id, alice.link.published[1].id)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a Bluetooth write that may have left waits for its ack, then goes over Internet once Bob is gone`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.UNCONFIRMED)
        var now = 1_000_000L
        router.clock = { now }
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            Thread.sleep(300)
            router.tickOutbox(now)
            Thread.sleep(200)
            assertEquals(DeliveryStatus.Sending, bw1())
            assertTrue(alice.link.published.isEmpty())
            // Ack window over, Bob out of range: the same message ID goes over Internet.
            whenever(router.meshForTesting.getAuthenticatedRemoteStaticKey(ContactIdentityResolver.peerIdForNoiseKey(bobNoise)))
                .thenReturn(null)
            now += AppConstants.Router.WESH_BLUETOOTH_ACK_TIMEOUT_MS
            router.tickOutbox(now)
            waitFor { bw1() == DeliveryStatus.Sent }
            assertEquals(1, alice.link.published.size)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a peer that walks away mid-write sends over Internet, a changed key never does`() {
        val router = failingBluetoothRouter()
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        try {
            // The transport reports nothing sent while the key now proven in range is another one.
            kotlinx.coroutines.runBlocking {
                whenever(router.meshForTesting.sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any())).thenAnswer {
                    whenever(router.meshForTesting.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(ByteArray(32) { 7 })
                    com.bitchat.android.mesh.BoundSendOutcome.NOT_SENT
                }
            }
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { bw1() is DeliveryStatus.Failed }
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a Bluetooth write refused for a changed key ends Échec and never goes over Internet`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.AUTH_FAILED)
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { bw1() is DeliveryStatus.Failed }
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a contact back in Bluetooth range gets the waiting message over Bluetooth`() {
        val router = failingBluetoothRouter()
        var now = 1_000_000L
        router.clock = { now }
        try {
            alice.link.answer = RelayPublishOutcome.UNCONFIRMED
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { alice.link.published.size == 1 }
            Thread.sleep(100)
            kotlinx.coroutines.runBlocking {
                whenever(router.meshForTesting.sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any()))
                    .thenReturn(com.bitchat.android.mesh.BoundSendOutcome.SENT)
            }
            now += AppConstants.Router.weshBluetoothRetryDelayMs(1)
            router.tickOutbox(now)
            waitFor { bw1() == DeliveryStatus.Sent }
            assertEquals(1, alice.link.published.size)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    private fun status(id: String) = AppStateStore.privateMessages.value.values.flatten().single { it.id == id }.deliveryStatus

    private fun MessageRouter.settle() = waitFor { weshIdleForTesting }

    @Test fun `rule 4 - a Bluetooth message without an ack for 20 s is resent over Internet under the same ID`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.SENT)
        var now = 1_000_000L
        router.clock = { now }
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            waitFor { bw1() == DeliveryStatus.Sent }
            whenever(router.meshForTesting.getAuthenticatedRemoteStaticKey(ContactIdentityResolver.peerIdForNoiseKey(bobNoise)))
                .thenReturn(null)
            now += AppConstants.Router.WESH_BLUETOOTH_ACK_TIMEOUT_MS - 1
            router.tickOutbox(now)
            router.settle()
            assertTrue(alice.link.published.isEmpty())
            now += 1
            router.tickOutbox(now)
            waitFor { alice.link.published.size == 1 }
            assertEquals(WeshInternetInbox.Outcome.STORED,
                kotlinx.coroutines.runBlocking { bob.inbox.onGiftWrap(alice.link.published.single(), expectedToken = null) })
            assertEquals("bw-1", bob.stored.single().second.id)
            // One Bluetooth write only: the resend never goes back over Bluetooth.
            kotlinx.coroutines.runBlocking {
                verify(router.meshForTesting, org.mockito.kotlin.times(1)).sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any())
            }
        } finally { cleanupRouter() }
    }

    @Test fun `rule 4 - a Bluetooth message acked in time never goes over Internet`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.SENT)
        var now = 1_000_000L
        router.clock = { now }
        try {
            router.sendPrivate("salut", bobConversation, "Bob", "bw-1")
            waitFor { bw1() == DeliveryStatus.Sent }
            AppStateStore.updatePrivateMessageStatus(bobConversation, "bw-1", DeliveryStatus.Delivered("Bob", java.util.Date()))
            now += AppConstants.Router.WESH_BLUETOOTH_ACK_TIMEOUT_MS
            router.tickOutbox(now)
            router.settle()
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())
        } finally { cleanupRouter() }
    }

    @Test fun `lost ack retries a Bluetooth-only legacy contact under the same ID with a bounded cap`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.SENT)
        var now = 1_000_000L
        router.clock = { now }
        alice.link.on = false
        alice.enroll(bobNoise, null)
        try {
            router.sendPrivate("salut", bobConversation, "Bob", "bw-1")
            waitFor { bw1() == DeliveryStatus.Sent }
            router.settle()
            waitFor { router.hasPendingBluetoothFallbackForTesting("bw-1") }
            for (attempt in 1 until AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS) {
                now += AppConstants.Router.weshBluetoothRetryDelayMs(attempt)
                router.tickOutbox(now)
                router.settle()
            }
            kotlinx.coroutines.runBlocking {
                verify(router.meshForTesting, org.mockito.kotlin.times(AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS))
                    .sendPrivateMessageBoundOutcome(any(), any(), any(), org.mockito.kotlin.eq("bw-1"), any())
            }
            now += AppConstants.Router.weshBluetoothRetryDelayMs(AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS)
            router.tickOutbox(now)
            waitFor { bw1() is DeliveryStatus.Failed }
            assertTrue(alice.link.published.isEmpty())
        } finally { cleanupRouter() }
    }

    @Test fun `a live Bluetooth route wins while the Internet copy is in backoff`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.SENT)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        var now = 1_000_000L
        router.clock = { now }
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        try {
            router.sendPrivate("salut", bobConversation, "Bob", "bw-1")
            waitFor { bw1() == DeliveryStatus.Sent }

            // Bob leaves BLE range: after the application-ack deadline, one relay copy is tried
            // and enters its Internet backoff.
            whenever(router.meshForTesting.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(null)
            now += AppConstants.Router.weshBluetoothRetryDelayMs(1)
            router.tickOutbox(now)
            waitFor { alice.link.published.size == 1 }
            router.settle()

            // Bob comes back before that Internet backoff expires. BLE must win immediately and
            // retain the same message ID; the relay must not receive a second copy.
            whenever(router.meshForTesting.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
            router.tickOutbox(now)
            router.settle()
            kotlinx.coroutines.runBlocking {
                verify(router.meshForTesting, org.mockito.kotlin.times(2))
                    .sendPrivateMessageBoundOutcome(any(), any(), any(), org.mockito.kotlin.eq("bw-1"), any())
            }
            assertEquals(1, alice.link.published.size)
        } finally { cleanupRouter() }
    }

    @Test fun `rule 4 - an Internet resend that never reaches a relay ends silently at the TTL, still Envoyé`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.SENT)
        val expired = mutableListOf<String>()
        router.onMessageExpired = { _, id -> expired += id }
        var now = 1_000_000L
        router.clock = { now }
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        try {
            router.sendPrivate("salut", bobConversation, "Bob", "bw-1")
            waitFor { bw1() == DeliveryStatus.Sent }
            // Bob walks away; the Internet copy keeps failing until the 24 h TTL.
            whenever(router.meshForTesting.getAuthenticatedRemoteStaticKey(ContactIdentityResolver.peerIdForNoiseKey(bobNoise)))
                .thenReturn(null)
            now += AppConstants.Router.WESH_BLUETOOTH_ACK_TIMEOUT_MS
            router.tickOutbox(now)
            waitFor { alice.link.published.size == 1 }
            router.settle()
            now += AppConstants.Router.OUTBOX_MESSAGE_TTL_MS
            router.tickOutbox(now)
            router.settle()
            assertEquals(listOf("bw-1"), expired)
            // This test replaces the production expiry callback with a recorder.
            assertEquals(DeliveryStatus.Sent, bw1())
        } finally { cleanupRouter() }
    }

    @Test fun `a new message received over Internet notifies once, a copy or an erased store never`() {
        AppStateStore.clear()
        val notified = mutableListOf<Pair<String, String>>()
        val stillCurrent = mutableListOf<() -> Boolean>()
        val notify: WeshInternetNotifier = { conversationID, message, current ->
            notified += conversationID to message.id
            stillCurrent += current
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val name = "notify-${java.util.UUID.randomUUID()}.db"
        val repository = ConversationRepository(context = context, dispatcher = executor.asCoroutineDispatcher(),
            databaseName = name, storageCipher = InMemoryConversationStorageCipher())
        try {
            AppStateStore.setConversationRepositoryForTest(repository)
            val message = BitchatMessage(id = "n-1", sender = "Alice", content = "salut", timestamp = java.util.Date(),
                isPrivate = true, senderPeerID = aliceConversation, transport = com.bitchat.android.model.MessageTransport.INTERNET)
            assertTrue(kotlinx.coroutines.runBlocking { WeshInternetReceiver.deliverOnce(aliceConversation, message, notify) })
            // The same ID again (relay replay, rule 4 copy, Bluetooth copy first): acked, no second notification.
            assertTrue(kotlinx.coroutines.runBlocking { WeshInternetReceiver.deliverOnce(aliceConversation, message.copy(), notify) })
            assertEquals(listOf(aliceConversation to "n-1"), notified)
            assertTrue(stillCurrent.single()())
            // An erase after the store: a notification not shown yet is dropped.
            AppStateStore.fencePrivateConversationAdmissionForPanic()
            assertFalse(stillCurrent.single()())
            // During an erase nothing is stored, so nothing is shown.
            assertFalse(kotlinx.coroutines.runBlocking {
                WeshInternetReceiver.deliverOnce(aliceConversation, message.copy(id = "n-2"), notify)
            })
            assertEquals(1, notified.size)
        } finally {
            AppStateStore.resetPrivateConversationAdmissionForTest()
            AppStateStore.setConversationRepositoryForTest(null)
            repository.closeForTest()
            executor.shutdown()
            context.deleteDatabase(name)
            AppStateStore.clear()
        }
    }

    @Test fun `the UI notifier shows on Main, never on the delivering thread, and not after an erase`() {
        val mainThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it).also(mainThread::set) }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        try {
            val shown = java.util.concurrent.LinkedBlockingQueue<Pair<String, Thread>>()
            val notifier = WeshInternetReceiver.postOnMain(scope, main.asCoroutineDispatcher()) { _, message ->
                shown += message.id to Thread.currentThread()
            }
            val message = BitchatMessage(id = "m-1", sender = "Alice", content = "salut", timestamp = java.util.Date(),
                isPrivate = true, senderPeerID = aliceConversation)
            notifier(aliceConversation, message) { true }
            notifier(aliceConversation, message.copy(id = "m-2")) { false }
            notifier(aliceConversation, message.copy(id = "m-3")) { true }
            assertEquals("m-1" to mainThread.get(), shown.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("m-3" to mainThread.get(), shown.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(shown.isEmpty())
        } finally {
            scope.cancel()
            main.shutdown()
        }
    }

    @Test fun `rule 4 - the Internet copy of a message already received over Bluetooth makes no second bubble`() {
        AppStateStore.clear()
        try {
            val first = BitchatMessage(id = "dup-1", sender = "Alice", content = "salut", timestamp = java.util.Date(),
                isPrivate = true, senderPeerID = aliceConversation)
            assertTrue(AppStateStore.addPrivateMessage(aliceConversation, first))
            assertTrue(kotlinx.coroutines.runBlocking { WeshInternetReceiver.deliverOnce(aliceConversation, first.copy()) })
            assertEquals(1, AppStateStore.privateMessages.value.values.flatten().count { it.id == "dup-1" })
        } finally { AppStateStore.clear() }
    }

    @Test fun `rule 4 - the Internet copy of a message whose history was unloaded is still acknowledged`() {
        AppStateStore.clear()
        try {
            val first = BitchatMessage(id = "dup-2", sender = "Alice", content = "un", timestamp = java.util.Date(1),
                isPrivate = true, senderPeerID = aliceConversation)
            assertTrue(AppStateStore.addPrivateMessage(aliceConversation, first))
            assertTrue(AppStateStore.addPrivateMessage(aliceConversation, first.copy(id = "dup-3", content = "deux",
                timestamp = java.util.Date(2))))
            AppStateStore.releasePrivateConversationHistory(aliceConversation)
            assertTrue(kotlinx.coroutines.runBlocking { WeshInternetReceiver.deliverOnce(aliceConversation, first.copy()) })
            assertEquals(0, AppStateStore.privateMessages.value.values.flatten().count { it.id == "dup-2" })
        } finally { AppStateStore.clear() }
    }

    /** Alice's router with Bob out of Bluetooth range; relays answer [answer]. */
    private fun internetOnlyRouter(answer: RelayPublishOutcome): MessageRouter {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        alice.link.answer = answer
        return MessageRouter(context, mesh, mock()).also { it.weshInternetLink = alice.link }
    }

    private fun cleanupRouter() {
        WeshContactStore.resetForTesting()
        MessageRouter.disableSchedulerForTesting = false
        AppStateStore.clear()
    }

    @Test fun `a reconnect retries early only a message last published at least 5 min ago, and keeps its backoff`() {
        val router = internetOnlyRouter(RelayPublishOutcome.UNCONFIRMED)
        var now = 1_000_000L
        router.clock = { now }
        val start = now
        fun at(offsetMs: Long) { now = start + offsetMs; router.tickOutbox(now); router.settle() }
        fun reconnectAt(offsetMs: Long) { now = start + offsetMs; router.onWeshInternetReconnected(); router.settle() }
        try {
            router.sendPrivate("salut", bobConversation, "Bob", "e-1")
            router.settle()
            // Backoff: 30 s, 1, 2, 4 min after each failure: publications at 0, 0:30, 1:30, 3:30, 7:30.
            for (offset in listOf(30_000L, 90_000L, 210_000L, 450_000L)) at(offset)
            assertEquals(5, alice.link.published.size)
            reconnectAt(450_000L + 4 * 60_000L)  // last publication 4 min ago: no early retry
            assertEquals(5, alice.link.published.size)
            reconnectAt(450_000L + 5 * 60_000L)  // 5 min ago: one early retry
            assertEquals(6, alice.link.published.size)
            reconnectAt(450_000L + 5 * 60_000L + 30_000L)  // just published: none
            assertEquals(6, alice.link.published.size)
            at(450_000L + 8 * 60_000L - 1)  // the backoff step is unchanged: 8 min after 7:30
            assertEquals(6, alice.link.published.size)
            at(450_000L + 8 * 60_000L)
            assertEquals(7, alice.link.published.size)
        } finally { cleanupRouter() }
    }

    @Test fun `a flapping link never makes more than 60 publications of one message in its 24 h, then it fails`() {
        val router = internetOnlyRouter(RelayPublishOutcome.UNCONFIRMED)
        val expired = mutableListOf<String>()
        router.onMessageExpired = { _, id -> expired += id }
        var now = 1_000_000L
        router.clock = { now }
        val start = now
        try {
            router.sendPrivate("salut", bobConversation, "Bob", "f-1")
            router.settle()
            // The relay drops and comes back every minute for a day.
            while (now - start <= AppConstants.Router.OUTBOX_MESSAGE_TTL_MS) {
                now += 60_000L
                router.onWeshInternetReconnected()
                router.settle()
                router.tickOutbox(now)
                router.settle()
            }
            assertEquals(AppConstants.Router.WESH_INTERNET_MAX_PUBLICATIONS, alice.link.published.size)
            assertEquals(listOf("f-1"), expired)
        } finally { cleanupRouter() }
    }

    @Test fun `after a relaunch each pending message is published once, then backs off from 30 s`() = withPersistedRouter { router ->
        val start = System.currentTimeMillis()
        var now = start
        router.clock = { now }
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        val expired = mutableListOf<String>()
        router.onMessageExpired = { _, id -> expired += id }
        fun own(id: String, at: Long, status: DeliveryStatus?) = AppStateStore.addPrivateMessage(bobConversation,
            BitchatMessage(id = id, sender = "me", content = "salut $id", timestamp = java.util.Date(at), isPrivate = true,
                recipientNickname = "Bob", senderPeerID = alice.peerID, deliveryStatus = status))
        own("p-stale", now - AppConstants.Router.OUTBOX_MESSAGE_TTL_MS - 1, DeliveryStatus.Sending)
        own("p-sent", now, DeliveryStatus.Sent)
        own("p-acked", now, DeliveryStatus.Delivered("Bob", java.util.Date(now)))
        AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "p-in", sender = "Bob", content = "coucou",
            timestamp = java.util.Date(now), isPrivate = true, senderPeerID = bobConversation))
        // The process restarts: the outbox is memory only, the store keeps « En attente ».
        AppStateStore.releasePrivateConversationHistory(bobConversation)
        assertEquals(2, kotlinx.coroutines.runBlocking { router.restoreWeshOutbox() })
        assertEquals(listOf("p-stale"), expired)
        router.tickOutbox(now)
        waitFor { alice.link.published.size == 2 }
        router.settle()
        val opened = alice.link.published.map { kotlinx.coroutines.runBlocking { bob.inbox.onGiftWrap(it, expectedToken = null) } }
        assertEquals(listOf("p-new", "p-old"), bob.stored.map { it.second.id }.sorted())
        assertTrue(opened.all { it == WeshInternetInbox.Outcome.STORED })
        now = start + 29_999
        router.tickOutbox(now)
        router.settle()
        assertEquals(2, alice.link.published.size)
        now = start + 30_000
        router.tickOutbox(now)
        waitFor { alice.link.published.size == 4 }
        router.settle()
        // Restoring again in the same process adds nothing.
        assertEquals(0, kotlinx.coroutines.runBlocking { router.restoreWeshOutbox() })
    }

    @Test fun `Bluetooth ack retry budget survives relaunch and remains capped`() = withPersistedRouter { firstRouter ->
        val mesh = firstRouter.meshForTesting
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob"))
        whenever(mesh.getPeerInfo(bobPeer)).thenReturn(PeerInfo(id = bobPeer, nickname = "Bob", isConnected = true,
            noisePublicKey = bobNoise, isDirectConnection = true, signingPublicKey = ByteArray(32),
            isVerifiedNickname = true, lastSeen = 1L))
        whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
        whenever(mesh.hasEstablishedSession(bobPeer)).thenReturn(true)
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any()))
                .thenReturn(com.bitchat.android.mesh.BoundSendOutcome.SENT)
        }
        alice.enroll(bobNoise, null)
        alice.link.on = false
        AppStateStore.updatePrivateMessageStatus(bobConversation, "p-new", DeliveryStatus.Delivered("Bob", java.util.Date()))
        var now = System.currentTimeMillis()
        firstRouter.clock = { now }

        assertEquals(MessageRouter.RouteResult.MESH,
            firstRouter.sendPrivate("salut", bobConversation, "Bob", "p-old"))
        firstRouter.settle()

        fun persisted(): PendingOutgoingDelivery = kotlinx.coroutines.runBlocking {
            AppStateStore.pendingOutgoingPrivateMessages()!!.single { it.message.id == "p-old" }
        }
        waitFor { persisted().bluetoothAttempts == 1 }
        firstRouter.clearAll()
        AppStateStore.releasePrivateConversationHistory(bobConversation)

        var router = firstRouter
        repeat(AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS - 1) { expectedIndex ->
            router = MessageRouter(context, mesh, mock()).also {
                it.weshInternetLink = alice.link
                it.clock = { now }
            }
            assertEquals(1, kotlinx.coroutines.runBlocking { router.restoreWeshOutbox() })
            val state = persisted()
            assertEquals(expectedIndex + 1, state.bluetoothAttempts)
            now = state.bluetoothRetryAtMs
            router.tickOutbox(now)
            router.settle()
            waitFor { persisted().bluetoothAttempts == expectedIndex + 2 }
            router.clearAll()
        }

        router = MessageRouter(context, mesh, mock()).also {
            it.weshInternetLink = alice.link
            it.clock = { now }
        }
        assertEquals(1, kotlinx.coroutines.runBlocking { router.restoreWeshOutbox() })
        now = persisted().bluetoothRetryAtMs
        router.tickOutbox(now)
        assertTrue(kotlinx.coroutines.runBlocking {
            AppStateStore.pendingOutgoingPrivateMessages().orEmpty().none { it.message.id == "p-old" }
        })
        kotlinx.coroutines.runBlocking {
            verify(mesh, org.mockito.kotlin.times(AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS))
                .sendPrivateMessageBoundOutcome(any(), any(), any(), org.mockito.kotlin.eq("p-old"), any())
        }
    }

    @Test fun `an unconfirmed GATT handoff persists may-have-left and waits for its ack window`() =
        withPersistedRouter { router ->
            val mesh = router.meshForTesting
            val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
            whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob"))
            whenever(mesh.getPeerInfo(bobPeer)).thenReturn(PeerInfo(id = bobPeer, nickname = "Bob", isConnected = true,
                noisePublicKey = bobNoise, isDirectConnection = true, signingPublicKey = ByteArray(32),
                isVerifiedNickname = true, lastSeen = 1L))
            whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
            whenever(mesh.hasEstablishedSession(bobPeer)).thenReturn(true)
            kotlinx.coroutines.runBlocking {
                whenever(mesh.sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any()))
                    .thenReturn(com.bitchat.android.mesh.BoundSendOutcome.UNCONFIRMED)
            }
            var now = 1_000_000L
            router.clock = { now }

            assertEquals(MessageRouter.RouteResult.MESH,
                router.sendPrivate("salut", bobConversation, "Bob", "p-old"))
            router.settle()

            fun persisted(): PendingOutgoingDelivery = kotlinx.coroutines.runBlocking {
                AppStateStore.pendingOutgoingPrivateMessages()!!.single { it.message.id == "p-old" }
            }
            waitFor { persisted().bluetoothAttempts == 1 }
            val pending = persisted()
            assertTrue(pending.bluetoothMayHaveLeft)
            assertEquals(now + AppConstants.Router.weshBluetoothRetryDelayMs(1), pending.bluetoothRetryAtMs)
            assertEquals(DeliveryStatus.Sending, pending.message.deliveryStatus)

            router.tickOutbox(now)
            router.settle()
            now += AppConstants.Router.weshBluetoothRetryDelayMs(1) - 1
            router.tickOutbox(now)
            router.settle()
            assertTrue(alice.link.published.isEmpty())
            kotlinx.coroutines.runBlocking {
                verify(mesh, org.mockito.kotlin.times(1)).sendPrivateMessageBoundOutcome(
                    any(), any(), any(), org.mockito.kotlin.eq("p-old"), any()
                )
            }
        }

    @Test fun `restoreWeshOutboxOnce retries after a null store read instead of staying blocked`() = withPersistedRouter { router ->
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        AppStateStore.releasePrivateConversationHistory(bobConversation)
        // Store suspended when the first restoreWeshOutboxOnce runs: pendingOutgoingPrivateMessages() returns null.
        AppStateStore.fencePrivateConversationAdmissionForPanic()
        router.restoreWeshOutboxOnce()
        router.settle()
        Thread.sleep(100)
        assertTrue(alice.link.published.isEmpty())

        // Once admission is restored, a second restoreWeshOutboxOnce call is not blocked and restores the waiting messages.
        AppStateStore.resetPrivateConversationAdmissionForTest()
        router.restoreWeshOutboxOnce()
        router.settle()
        Thread.sleep(100)
        router.tickOutbox()
        waitFor { alice.link.published.size == 2 }
    }

    @Test fun `two copies of one waiting message merge into the stronger retry state`() {
        val wrap = NostrEvent(id = "w", pubkey = "p", createdAt = 1, kind = 1059, tags = emptyList(), content = "c", sig = "s")
        val worked = MessageRouter.QueuedMessage("salut", "Bob", "m-1", enqueuedAtMs = 1_000, internetAttempts = 3,
            internetRetryAtMs = 50_000, giftWrap = wrap, publications = 3, lastPublishedAtMs = 40_000)
        val restored = MessageRouter.QueuedMessage("salut", "Bob", "m-1", enqueuedAtMs = 1_000)
        for (merged in listOf(MessageRouter.strongerRetryState(worked, restored), MessageRouter.strongerRetryState(restored, worked))) {
            assertEquals(3, merged.internetAttempts)
            assertEquals(3, merged.publications)
            assertEquals(50_000L, merged.internetRetryAtMs)
            assertEquals(40_000L, merged.lastPublishedAtMs)
            assertEquals(wrap, merged.giftWrap)
        }
        // A later attempt that dropped a rejected wrap wins over an older cached one.
        val rejected = worked.copy(publications = 4, giftWrap = null)
        assertEquals(null, MessageRouter.strongerRetryState(worked, rejected).giftWrap)
        // A copy which may have left is the stronger safety state.
        assertTrue(MessageRouter.strongerRetryState(worked.copy(afterBluetoothWrite = true), restored).afterBluetoothWrite)
    }

    @Test fun `a restored copy racing a requeue keeps the backoff and the publication count`() = withPersistedRouter { router ->
        AppStateStore.updatePrivateMessageStatus(bobConversation, "p-new", DeliveryStatus.Sent)
        var now = System.currentTimeMillis()
        router.clock = { now }
        alice.link.answer = RelayPublishOutcome.UNCONFIRMED
        // Holding the router monitor parks the attempt right after its publish, before it leaves the
        // in-flight set and is requeued: the relaunch restore lands exactly there and adds nothing.
        synchronized(router) {
            router.sendPrivate("salut", bobConversation, "Bob", "p-old")
            waitFor { alice.link.published.size == 1 }
            assertEquals(0, kotlinx.coroutines.runBlocking { router.restoreWeshOutbox() })
        }
        router.settle()
        // The 30 s step of the first failed publication still holds.
        router.tickOutbox(now)
        router.settle()
        Thread.sleep(100)
        assertEquals(1, alice.link.published.size)
        now += 30_000
        router.tickOutbox(now)
        waitFor { alice.link.published.size == 2 }
    }

    @Test fun `a conversation deleted while the relaunch restore reads the store gets nothing queued`() = withPersistedRouter { router ->
        AppStateStore.releasePrivateConversationHistory(bobConversation)
        kotlinx.coroutines.runBlocking { AppStateStore.awaitConversationPersistence() }
        val gate = java.util.concurrent.CountDownLatch(1)
        persistedExecutor!!.execute { gate.await() }
        val restore = java.util.concurrent.CompletableFuture.supplyAsync {
            kotlinx.coroutines.runBlocking { router.restoreWeshOutbox() }
        }
        Thread.sleep(100)
        // The user deletes the conversation while the waiting messages are being read.
        router.cancelWeshConversation(bobConversation)
        gate.countDown()
        assertEquals(0, restore.get(5, java.util.concurrent.TimeUnit.SECONDS))
        router.tickOutbox()
        router.settle()
        Thread.sleep(200)
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `only a relay seen down that comes back counts as a reconnect`() {
        var reconnects = 0
        val watcher = WeshRelayReconnectWatcher { reconnects++ }
        watcher.apply(listOf("a" to true, "b" to false))   // first sight: not a reconnect
        assertEquals(0, reconnects)
        watcher.apply(listOf("a" to true, "b" to true))    // b came back
        assertEquals(1, reconnects)
        watcher.apply(listOf("a" to true, "b" to true))    // no change
        assertEquals(1, reconnects)
        watcher.apply(listOf("a" to false, "b" to false))
        watcher.apply(listOf("a" to true, "b" to true))    // both back at once: one early retry pass
        assertEquals(2, reconnects)
    }

    @Test fun `no stored status read crosses an erase`() {
        AppStateStore.clear()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val name = "fence-${java.util.UUID.randomUUID()}.db"
        val repository = ConversationRepository(context = context, dispatcher = executor.asCoroutineDispatcher(),
            databaseName = name, storageCipher = InMemoryConversationStorageCipher())
        try {
            AppStateStore.setConversationRepositoryForTest(repository)
            AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "e-old", sender = "me", content = "salut",
                timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
                senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
            kotlinx.coroutines.runBlocking { repository.awaitPendingWrites() }
            // The store's single thread is busy: the read waits behind it, and the erase lands meanwhile.
            val gate = java.util.concurrent.CountDownLatch(1)
            executor.execute { gate.await() }
            val pending = java.util.concurrent.CompletableFuture.supplyAsync {
                kotlinx.coroutines.runBlocking { AppStateStore.persistedAck(bobConversation, "e-old") }
            }
            Thread.sleep(100)
            AppStateStore.fencePrivateConversationAdmissionForPanic()
            gate.countDown()
            val read = pending.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(AppStateStore.PersistedAck.UNAVAILABLE, read)
        } finally {
            AppStateStore.resetPrivateConversationAdmissionForTest()
            AppStateStore.setConversationRepositoryForTest(null)
            repository.closeForTest()
            executor.shutdown()
            context.deleteDatabase(name)
            AppStateStore.clear()
        }
    }

    @Test fun `an attempt skipped while the store is unavailable never clears the in-flight mark of the next one`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            // A full outbox: the skipped attempt's requeue evicts the oldest entry, and the eviction
            // callback starts a second attempt at that exact point, before the first one has ended.
            alice.link.on = false
            repeat(AppConstants.Router.OUTBOX_MAX_PER_PEER) {
                assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("old", bobConversation, "Bob", "old-$it"))
            }
            alice.link.on = true
            alice.link.hold = hold
            val second = java.util.concurrent.CountDownLatch(1)
            router.onMessageExpired = { _, _ ->
                if (second.count == 1L) {
                    AppStateStore.resetPrivateConversationAdmissionForTest()
                    router.sendPrivate("salut", bobConversation, "Bob", "race-1")
                    second.countDown()
                }
            }
            AppStateStore.fencePrivateConversationAdmissionForPanic()
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "race-1"))
            assertTrue(second.await(5, java.util.concurrent.TimeUnit.SECONDS))
            waitFor { alice.link.published.size == 1 }
            Thread.sleep(200)
            // The second attempt is still in flight: a third one must not start a parallel publish.
            router.sendPrivate("salut", bobConversation, "Bob", "race-1")
            Thread.sleep(300)
            assertEquals(1, alice.link.published.size)
        } finally {
            hold.complete(Unit)
            alice.link.hold = null
            AppStateStore.resetPrivateConversationAdmissionForTest()
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a block during an in-flight Internet send keeps it off the relays`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = "bl-1", sender = "me", content = "salut",
            timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
            senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
        try {
            var calls = 0
            // The routing check passes; the block lands while the send is being prepared.
            alice.link.onIsOn = { if (++calls == 2) router.cancelWeshConversation(bobConversation) }
            assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "bl-1"))
            Thread.sleep(300)
            assertTrue(alice.link.published.isEmpty())
        } finally {
            alice.link.onIsOn = null
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a Bluetooth write that reached nothing without an Internet route stays waiting`() {
        val router = failingBluetoothRouter()
        alice.link.on = false
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            Thread.sleep(300)
            assertEquals(DeliveryStatus.Sending, bw1())
            assertTrue(alice.link.published.isEmpty())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    /** Alice's router with a real conversation database; Bob is out of Bluetooth range. */
    /** The store's single writer thread in [withPersistedRouter], to hold a read behind it. */
    private var persistedExecutor: java.util.concurrent.ExecutorService? = null

    private fun withPersistedRouter(body: (MessageRouter) -> Unit) {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        AppStateStore.clear()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor().also { persistedExecutor = it }
        val name = "ack-${java.util.UUID.randomUUID()}.db"
        val repository = ConversationRepository(context = context, dispatcher = executor.asCoroutineDispatcher(),
            databaseName = name, storageCipher = InMemoryConversationStorageCipher())
        try {
            AppStateStore.setConversationRepositoryForTest(repository)
            for (id in listOf("p-old", "p-new")) {
                AppStateStore.addPrivateMessage(bobConversation, BitchatMessage(id = id, sender = "me", content = "salut",
                    timestamp = java.util.Date(), isPrivate = true, recipientNickname = "Bob",
                    senderPeerID = alice.peerID, deliveryStatus = DeliveryStatus.Sending))
            }
            body(MessageRouter(context, mesh, mock()).also { it.weshInternetLink = alice.link })
            kotlinx.coroutines.runBlocking { repository.awaitPendingWrites() }
        } finally {
            AppStateStore.setConversationRepositoryForTest(null)
            repository.closeForTest()
            executor.shutdown()
            context.deleteDatabase(name)
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `with the conversation unloaded, a persisted Distribué stops the Internet resend`() = withPersistedRouter { router ->
        AppStateStore.updatePrivateMessageStatus(bobConversation, "p-old", DeliveryStatus.Delivered("Bob", java.util.Date()))
        AppStateStore.releasePrivateConversationHistory(bobConversation)
        assertTrue(AppStateStore.privateMessages.value.values.flatten().none { it.id == "p-old" })
        assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "p-old"))
        Thread.sleep(400)
        assertTrue(alice.link.published.isEmpty())
    }

    @Test fun `with the conversation unloaded, a persisted Envoyé is resent over Internet`() = withPersistedRouter { router ->
        AppStateStore.updatePrivateMessageStatus(bobConversation, "p-old", DeliveryStatus.Sent)
        AppStateStore.releasePrivateConversationHistory(bobConversation)
        assertEquals(MessageRouter.RouteResult.NOSTR, router.sendPrivate("salut", bobConversation, "Bob", "p-old"))
        waitFor { alice.link.published.size == 1 }
    }

    @Test fun `a message acked during the ack window is never sent again`() {
        val router = failingBluetoothRouter(com.bitchat.android.mesh.BoundSendOutcome.UNCONFIRMED)
        var now = 1_000_000L
        router.clock = { now }
        try {
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("salut", bobConversation, "Bob", "bw-1"))
            Thread.sleep(300)
            AppStateStore.updatePrivateMessageStatus(bobConversation, "bw-1", DeliveryStatus.Delivered("Bob", java.util.Date()))
            now += AppConstants.Router.WESH_BLUETOOTH_ACK_TIMEOUT_MS
            router.tickOutbox(now)
            Thread.sleep(300)
            kotlinx.coroutines.runBlocking {
                verify(router.meshForTesting, org.mockito.kotlin.times(1))
                    .sendPrivateMessageBoundOutcome(any(), any(), any(), any(), any())
            }
            assertTrue(alice.link.published.isEmpty())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
            AppStateStore.clear()
        }
    }

    @Test fun `a message replayed by the lookback after a restart is not acked again`() = runTest {
        alice.route.sendPrivate("salut", bobConversation, "ack-once")
        val wrap = alice.link.published.single()
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertEquals(1, bob.link.published.size)
        bob.inbox = bob.newInbox() // process restart: in-memory event IDs are gone, the ack record is durable
        assertEquals(WeshInternetInbox.Outcome.STORED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertEquals(1, bob.link.published.size)
    }

    @Test fun `a message ID that is not UUID-like is refused before any store or ack`() = runTest {
        alice.route.sendPrivate("x", bobConversation, "evil:id")
        alice.route.sendPrivate("y", bobConversation, "a".repeat(129))
        alice.route.sendPrivate("z", bobConversation, "id-\u0430\u0431")      // Cyrillic letters
        alice.route.sendPrivate("w", bobConversation, "id-\uFF11\uFF12")      // full-width digits
        for (wrap in alice.link.published) assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
        assertTrue(bob.acked.isEmpty())
    }

    @Test fun `files, verification, voice and peer state never arrive over Internet`() = runTest {
        for (type in listOf(NoisePayloadType.FILE_TRANSFER, NoisePayloadType.VERIFY_CHALLENGE,
                NoisePayloadType.VERIFY_RESPONSE, NoisePayloadType.VOICE_FRAME, NoisePayloadType.PEER_STATE)) {
            val wrap = NostrProtocol.createPrivateMessage(embed(type, ByteArray(48) { 7 }), bob.nostr.publicKeyHex, alice.nostr).single()
            assertEquals(type.name, WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        }
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `a seal forged in Alice's name is dropped`() = runTest {
        val attacker = NostrIdentity.generate()
        val embedded = com.bitchat.android.nostr.NostrEmbeddedBitChat.encodePMForNostr("forged", "forged-1", bobNoise.hex(), alice.peerID)!!
        val rumor = NostrEvent(pubkey = alice.nostr.publicKeyHex, createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = com.bitchat.android.nostr.NostrKind.DIRECT_MESSAGE, tags = listOf(listOf("p", bob.nostr.publicKeyHex)), content = embedded)
            .let { it.copy(id = it.computeEventIdHex()) }
        val gson = com.google.gson.Gson()
        val sealContent = com.bitchat.android.nostr.NostrCrypto.encryptNIP44(gson.toJson(rumor), bob.nostr.publicKeyHex, attacker.privateKeyHex)
        // Claims Alice as the seal author but is signed with the attacker's key.
        val seal = NostrEvent(pubkey = alice.nostr.publicKeyHex, createdAt = rumor.createdAt,
            kind = com.bitchat.android.nostr.NostrKind.SEAL, tags = emptyList(), content = sealContent)
            .sign(attacker.privateKeyHex).copy(pubkey = alice.nostr.publicKeyHex)
        val (wrapKey, wrapPub) = com.bitchat.android.nostr.NostrCrypto.generateKeyPair()
        val wrap = NostrEvent(pubkey = wrapPub, createdAt = rumor.createdAt, kind = com.bitchat.android.nostr.NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", bob.nostr.publicKeyHex)),
            content = com.bitchat.android.nostr.NostrCrypto.encryptNIP44(gson.toJson(seal), bob.nostr.publicKeyHex, wrapKey))
            .sign(wrapKey)
        assertEquals(WeshInternetInbox.Outcome.DROPPED, bob.inbox.onGiftWrap(wrap, expectedToken = null))
        assertTrue(bob.stored.isEmpty())
        assertTrue(bob.link.published.isEmpty())
    }

    @Test fun `the subscription exists only while On with my key, and Off unsubscribes and forgets`() {
        val events = mutableListOf<String>()
        var identity: NostrIdentity? = bob.nostr
        val subscription = WeshInternetSubscription(
            myIdentity = { identity },
            subscribe = { filter, _ ->
                events += if (com.google.gson.Gson().toJson(filter).contains(bob.nostr.publicKeyHex)) "subscribe:me" else "subscribe:other"
            },
            unsubscribe = { events += "unsubscribe" },
            onEvent = {},
            onOff = { events += "forget" },
            nowMs = { 1_000_000_000L }
        )
        subscription.apply(com.bitchat.android.net.WeshInternetState.Connecting(40))
        subscription.apply(com.bitchat.android.net.WeshInternetState.Error(com.bitchat.android.net.WeshInternetError.TOR_BLOCKED))
        assertTrue(events.isEmpty())
        subscription.apply(com.bitchat.android.net.WeshInternetState.On)
        assertEquals(listOf("subscribe:me"), events)
        subscription.apply(com.bitchat.android.net.WeshInternetState.Off)
        assertEquals(listOf("subscribe:me", "unsubscribe", "forget"), events)
        // After a panic wipe there is no key: On again subscribes nothing.
        identity = null
        subscription.apply(com.bitchat.android.net.WeshInternetState.On)
        assertEquals(3, events.size)
    }

    @Test fun `an erase or a new npub during the npub notice stops it before the radio`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob"))
        whenever(mesh.getPeerInfo(bobPeer)).thenReturn(PeerInfo(id = bobPeer, nickname = "x", isConnected = true,
            noisePublicKey = bobNoise, isDirectConnection = true, signingPublicKey = ByteArray(32),
            isVerifiedNickname = true, lastSeen = 1L))
        whenever(mesh.getAuthenticatedRemoteStaticKey(bobPeer)).thenReturn(bobNoise)
        whenever(mesh.hasEstablishedSession(bobPeer)).thenReturn(true)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        var myNpub: String? = alice.nostr.npub
        router.weshMyNpub = { myNpub }
        // What the send guard answers once the change lands, right before the radio write.
        val guards = CopyOnWriteArrayList<Boolean>()
        var change: () -> Unit = {}
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenAnswer { call ->
                change()
                val allowed = call.getArgument<() -> Boolean>(4)()
                guards += allowed
                allowed
            }
        }
        try {
            change = { router.clearAll() }
            router.announceWeshInternetKey(bobPeer)
            waitFor { guards.size == 1 }
            change = { myNpub = NostrIdentity.generate().npub }
            router.announceWeshInternetKey(bobPeer)
            waitFor { guards.size == 2 }
            assertEquals(listOf(false, false), guards.toList())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    @Test fun `my npub goes only to an enrolled contact over its bound session, and never with a favorite star`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        val strangerKey = ByteArray(32) { 9 }
        val strangerPeer = ContactIdentityResolver.peerIdForNoiseKey(strangerKey)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob", strangerPeer to "Stranger"))
        for ((peer, key) in listOf(bobPeer to bobNoise, strangerPeer to strangerKey)) {
            whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "x", isConnected = true,
                noisePublicKey = key, isDirectConnection = true, signingPublicKey = ByteArray(32),
                isVerifiedNickname = true, lastSeen = 1L))
            whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
            whenever(mesh.hasEstablishedSession(peer)).thenReturn(true)
        }
        val sent = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenAnswer { call ->
                sent += call.getArgument<String>(0) to call.getArgument<ByteArray>(2); true
            }
        }
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        var myNpub: String? = null
        router.weshMyNpub = { myNpub }
        try {
            router.announceWeshInternetKey(bobPeer) // Internet off: no npub, nothing sent
            Thread.sleep(200)
            assertTrue(sent.isEmpty())
            myNpub = alice.nostr.npub
            router.announceWeshInternetKey(strangerPeer)
            router.announceWeshInternetKey(bobPeer)
            waitFor { sent.size == 1 }
            assertEquals("[FAVORITED]:${alice.nostr.npub}", sent.single().first)
            assertTrue(sent.single().second.contentEquals(bobNoise))
            router.announceWeshInternetKey(bobPeer) // once per contact and npub
            Thread.sleep(200)
            assertEquals(1, sent.size)

            router.sendFavoriteNotification(strangerPeer, true)
            verify(mesh).sendPrivateMessage(org.mockito.kotlin.eq("[FAVORITED]:"), org.mockito.kotlin.eq(strangerPeer), any(), org.mockito.kotlin.isNull())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    @Test fun `turning Internet on tells every enrolled contact in Bluetooth range my npub, once`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        val bobPeer = ContactIdentityResolver.peerIdForNoiseKey(bobNoise)
        val strangerKey = ByteArray(32) { 9 }
        val strangerPeer = ContactIdentityResolver.peerIdForNoiseKey(strangerKey)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(bobPeer to "Bob", strangerPeer to "Stranger"))
        for ((peer, key) in listOf(bobPeer to bobNoise, strangerPeer to strangerKey)) {
            whenever(mesh.getPeerInfo(peer)).thenReturn(PeerInfo(id = peer, nickname = "x", isConnected = true,
                noisePublicKey = key, isDirectConnection = true, signingPublicKey = ByteArray(32),
                isVerifiedNickname = true, lastSeen = 1L))
            whenever(mesh.getAuthenticatedRemoteStaticKey(peer)).thenReturn(key)
            whenever(mesh.hasEstablishedSession(peer)).thenReturn(true)
        }
        val sent = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        kotlinx.coroutines.runBlocking {
            whenever(mesh.sendPrivateMessageBound(any(), any(), any(), any(), any())).thenAnswer { call ->
                sent += call.getArgument<String>(0) to call.getArgument<ByteArray>(2); true
            }
        }
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        router.weshMyNpub = { alice.nostr.npub }
        try {
            router.onWeshInternetOn()
            waitFor { sent.size == 1 }
            assertEquals("[FAVORITED]:${alice.nostr.npub}", sent.single().first)
            assertTrue(sent.single().second.contentEquals(bobNoise))
            router.onWeshInternetOn()
            Thread.sleep(200)
            assertEquals(1, sent.size)
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    @Test fun `the receiver calls the router once per transition to On`() {
        var calls = 0
        val watcher = WeshInternetOnWatcher { calls++ }
        watcher.apply(com.bitchat.android.net.WeshInternetState.Connecting(40))
        watcher.apply(com.bitchat.android.net.WeshInternetState.On)
        watcher.apply(com.bitchat.android.net.WeshInternetState.On)
        watcher.apply(com.bitchat.android.net.WeshInternetState.Connecting(90))
        watcher.apply(com.bitchat.android.net.WeshInternetState.On)
        assertEquals(2, calls)
    }

    private fun embed(type: NoisePayloadType, data: ByteArray): String {
        val packet = com.bitchat.android.protocol.BitchatPacket(
            version = 1u, type = com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value,
            senderID = com.bitchat.android.mesh.MeshPacketUtils.hexStringToByteArray(alice.peerID),
            recipientID = com.bitchat.android.mesh.MeshPacketUtils.hexStringToByteArray(ContactIdentityResolver.peerIdForNoiseKey(bobNoise)),
            timestamp = System.currentTimeMillis().toULong(), payload = byteArrayOf(type.value.toByte()) + data,
            signature = null, ttl = 7u)
        return "bitchat1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packet.toBinaryData()!!)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun `a panic during a send keeps nothing queued`() {
        val mesh: MeshService = mock()
        whenever(mesh.myPeerID).thenReturn(alice.peerID)
        ContactDirectory.initialize(context) { mesh }
        WeshContactStore.installForTesting(alice.contacts)
        MessageRouter.disableSchedulerForTesting = true
        val router = MessageRouter(context, mesh, mock())
        router.weshInternetLink = alice.link
        alice.link.on = false
        // The clock is read after the epoch capture: a panic lands there, mid-send.
        router.clock = { router.clearAll(); 1_000L }
        try {
            assertEquals(MessageRouter.RouteResult.DROPPED, router.sendPrivate("x", bobConversation, "Bob", "panic-1"))
            router.clock = { 2_000L }
            alice.link.on = true
            router.tickOutbox(2_000L)
            Thread.sleep(200)
            assertTrue(alice.link.published.isEmpty())
        } finally {
            WeshContactStore.resetForTesting()
            MessageRouter.disableSchedulerForTesting = false
        }
    }

    @After fun cleanup() {
        WeshContactStore.resetForTesting()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(condition())
    }
}
