package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.nostr.RelayPublishOutcome
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes messages between local mesh transports and Nostr, matching iOS behavior.
 */
class MessageRouter internal constructor(
    private val context: Context,
    private var mesh: MeshService,
    private val nostr: NostrTransport
) {
    enum class RouteResult {
        MESH,
        NOSTR,
        QUEUED,
        DROPPED
    }

    internal data class QueuedMessage(
        val content: String,
        val nickname: String,
        val messageID: String,
        val enqueuedAtMs: Long,
        /** Internet attempts no relay accepted; drives the backoff. */
        val internetAttempts: Int = 0,
        /** Earliest next Internet attempt. */
        val internetRetryAtMs: Long = 0L,
        /** Signed gift wrap of an unconfirmed attempt, republished unchanged; memory only. */
        val giftWrap: com.bitchat.android.nostr.NostrEvent? = null,
        /** A Bluetooth write that may have left waits this long for its ack before any retry. */
        val notBeforeMs: Long = 0L,
        /** Publications a relay may have seen (hard cap per message, early retries and re-wraps included). */
        val publications: Int = 0,
        val lastPublishedAtMs: Long = 0L,
        /** The backoff time an early retry replaced; restored after it, so the backoff is unchanged. */
        val scheduledRetryAtMs: Long? = null,
        /** Same-message Bluetooth attempts, including completed writes still awaiting an app ack. */
        val bluetoothAttempts: Int = 0,
        /**
         * A Bluetooth operation completed, or may have left, but has no application ack yet. It may
         * be retried over the same authenticated route; receivers deduplicate the stable message ID.
         */
        val afterBluetoothWrite: Boolean = false
    )

    private data class ConversationRetry(
        val handshakeAttempts: Int,
        val nextHandshakeAttemptAtMs: Long
    )

    companion object {
        private const val TAG = "MessageRouter"
        private const val OUTBOX_TICK_MS = AppConstants.Router.OUTBOX_TICK_MS
        private const val OUTBOX_MESSAGE_TTL_MS = AppConstants.Router.OUTBOX_MESSAGE_TTL_MS
        private const val OUTBOX_MAX_PER_PEER = AppConstants.Router.OUTBOX_MAX_PER_PEER
        private val HANDSHAKE_RETRY_BACKOFF_MS = AppConstants.Router.HANDSHAKE_RETRY_BACKOFF_MS

        @Volatile private var INSTANCE: MessageRouter? = null
        internal var disableSchedulerForTesting = false
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: MeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    MessageRouter(context.applicationContext, mesh, nostr).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID, and make sure the retry
            // scheduler is running (it is stopped together with MeshForegroundService).
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            instance.startOutboxScheduler()
            return instance
        }

        /** Two copies of one waiting message: one copy keeping the stronger retry state of both. */
        internal fun strongerRetryState(a: QueuedMessage, b: QueuedMessage): QueuedMessage {
            val latest = if (b.publications > a.publications) b else a
            return latest.copy(
                enqueuedAtMs = minOf(a.enqueuedAtMs, b.enqueuedAtMs),
                internetAttempts = maxOf(a.internetAttempts, b.internetAttempts),
                internetRetryAtMs = maxOf(a.internetRetryAtMs, b.internetRetryAtMs),
                // The copy that published last decides (it may have dropped a rejected wrap).
                giftWrap = if (a.publications != b.publications) latest.giftWrap else a.giftWrap ?: b.giftWrap,
                notBeforeMs = maxOf(a.notBeforeMs, b.notBeforeMs),
                publications = maxOf(a.publications, b.publications),
                lastPublishedAtMs = maxOf(a.lastPublishedAtMs, b.lastPublishedAtMs),
                scheduledRetryAtMs = listOfNotNull(a.scheduledRetryAtMs, b.scheduledRetryAtMs).maxOrNull(),
                bluetoothAttempts = maxOf(a.bluetoothAttempts, b.bluetoothAttempts),
                afterBluetoothWrite = a.afterBluetoothWrite || b.afterBluetoothWrite
            )
        }

        internal fun resetForTesting() {
            INSTANCE?.schedulerScope?.cancel()
            INSTANCE = null
        }
    }

    /**
     * Wesh product policy: private messages only to QR contacts. A live Bluetooth session with the
     * enrolled key wins; otherwise, while Internet mode is On, the npub bound by that contact's QR;
     * otherwise the message waits. The inherited favorite-based Nostr fallbacks stay closed.
     */
    internal var enforceWeshPolicy = com.bitchat.android.BuildConfig.WESH_POLICY
    internal var weshInternetLink: WeshInternetLink = WeshInternetLink.Live(context)
    internal val meshForTesting: MeshService get() = mesh
    /** My npub while Internet mode is active; read-only, never creates a key. */
    internal var weshMyNpub: () -> String? = { WeshQrIdentity.mine(context)?.npub }
    /** Bumped by every clear (panic): an Internet send that fails afterwards is never requeued. */
    private val weshEpoch = java.util.concurrent.atomic.AtomicLong()
    private val weshInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Bumped per conversation by a cancel (delete, block, remove): an attempt started before never requeues. */
    private val weshCancelled = ConcurrentHashMap<String, Long>()
    /** Conversation + npub pairs already told my npub in this process (re-sent after a panic). */
    private val weshAnnounced = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun weshInternet() = WeshInternetRoute(WeshContactStore.get(context), weshInternetLink) { mesh.myPeerID }
    // Outbox: conversationID -> queued messages, oldest first
    private val outbox = ConcurrentHashMap<String, MutableList<QueuedMessage>>()

    // Per-conversation handshake retry state for queued messages
    private val retryState = ConcurrentHashMap<String, ConversationRetry>()

    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var schedulerJob: kotlinx.coroutines.Job? = null

    // Injectable clock for tests
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // Called with the messageID of queued messages that expired or were evicted
    var onMessageExpired: ((conversationID: String, messageID: String) -> Unit)? = null

    init {
        startOutboxScheduler()
    }

    /**
     * The epoch bump fences every dispatch at once, without the monitor; the clear then takes the
     * monitor so it never interleaves with an outbox mutation. A flush in progress sees the new
     * epoch at its next entry and stops.
     */
    fun clearAll() {
        weshEpoch.incrementAndGet()
        weshRestoreStarted.set(false)
        synchronized(this) {
            outbox.clear()
            retryState.clear()
            weshInFlight.clear()
            weshAnnounced.clear()
            weshCancelled.clear()
        }
        Log.d(TAG, "Cleared all MessageRouter outbox messages and retry state")
    }

    // Listener for favorites changes to flush outbox when npub mapping appears/changes
    private val favoriteListener = object: com.bitchat.android.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            ContactIdentityResolver.peerIdForNoiseKeyHex(noiseKeyHex)?.let { flushOutboxFor(it) }
        }
        override fun onAllCleared() {
        }
    }

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String): RouteResult {
        val resolution = ContactDirectory.resolve(toPeerID)
        val conversationID = resolution.conversationID
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID

        if (enforceWeshPolicy) {
            // Captured first: a panic during this call fences both dispatches and the queue.
            val epoch = weshEpoch.get()
            val sender = WeshPrivateSender(WeshContactStore.get(context))
            if (!sender.enrolled(conversationID) || content.isBlank() || content.toByteArray().size > 2000) return RouteResult.DROPPED
            // A retry of a waiting message replaces its queued copy: one message, one route. It
            // keeps its 24 h deadline, its backoff count and its signed wrap.
            // Under the monitor cancelWeshConversation bumps it with: a delete or block from here on
            // stops every route of this send.
            val cancelled = synchronized(this) { weshCancelled[conversationID] ?: 0L }
            val previous = dequeueWesh(conversationID, messageID)
            val queued = previous?.copy(content = content, nickname = recipientNickname, internetRetryAtMs = 0L,
                notBeforeMs = 0L, scheduledRetryAtMs = null, afterBluetoothWrite = false)
                ?: QueuedMessage(content, recipientNickname, messageID, clock())
            if (sender.canSend(conversationID, mesh)) {
                dispatchWesh(queued, conversationID, epoch, cancelled)
                return RouteResult.MESH
            }
            if (weshInternet().canSend(conversationID)) {
                dispatchWeshInternet(queued, conversationID, epoch, since = cancelled)
                return RouteResult.NOSTR
            }
            return if (enqueueWesh(conversationID, queued, epoch, cancelled)) RouteResult.QUEUED else RouteResult.DROPPED
        }

        val hasMesh = meshTarget?.let { isConnected(mesh, it) } == true
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing PM via mesh to ${meshTarget} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, meshTarget, recipientNickname, messageID)
            return RouteResult.MESH
        } else if (canSendViaNostr(nostrTarget)) {
            Log.d(TAG, "Routing PM via Nostr to ${conversationID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, nostrTarget, recipientNickname, messageID)
            return RouteResult.NOSTR
        } else {
            Log.d(TAG, "Queued PM for ${conversationID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            enqueue(conversationID, QueuedMessage(content, recipientNickname, messageID, clock()))
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${conversationID.take(16)}…")
            if (hasMesh) meshTarget?.let { kickHandshake(conversationID, it, immediate = true) }
            return RouteResult.QUEUED
        }
    }

    /** [entry] carries the message's deadline, backoff and signed wrap through a failed write. */
    private fun dispatchWesh(entry: QueuedMessage, conversationID: String, epoch: Long, cancelled: Long) {
        val messageID = entry.messageID
        if (epoch != weshEpoch.get() || !weshInFlight.add(messageID)) return
        schedulerScope.launch {
            val outcome = try {
                if (epoch != weshEpoch.get()) null
                else WeshPrivateSender(WeshContactStore.get(context)).dispatchOutcome(entry.content, conversationID, messageID, mesh)
            } catch (e: Throwable) {
                synchronized(this@MessageRouter) { weshInFlight.remove(messageID) }
                throw e
            }
            // Leaving the in-flight set and the next step are one step under the router monitor:
            // a relaunch restore or a flush never sees this message as neither in flight nor queued.
            synchronized(this@MessageRouter) {
                weshInFlight.remove(messageID)
                val attempted = entry.copy(bluetoothAttempts = entry.bluetoothAttempts + 1)
                when (outcome) {
                    null -> Unit
                    // A GATT callback completed the transport operation, but only DELIVERED/READ is
                    // the application ack. Keep a same-ID retry even for legacy contacts with no npub.
                    com.bitchat.android.mesh.BoundSendOutcome.SENT -> {
                        val pending = attempted.copy(
                            notBeforeMs = clock() + AppConstants.Router.weshBluetoothRetryDelayMs(attempted.bluetoothAttempts),
                            internetRetryAtMs = 0L,
                            afterBluetoothWrite = true
                        )
                        enqueueWesh(conversationID, pending, epoch, cancelled)
                        // Persist the retry budget before durable « Envoyé » so even a process death
                        // between the two writes restores a bounded same-ID attempt.
                        persistBluetoothRetry(conversationID, pending)
                        AppStateStore.updatePrivateMessageStatus(
                            conversationID,
                            messageID,
                            com.bitchat.android.model.DeliveryStatus.Sent,
                            com.bitchat.android.model.MessageTransport.BLUETOOTH
                        )
                    }
                    // Nothing reached the radio: another route may take it.
                    com.bitchat.android.mesh.BoundSendOutcome.NOT_SENT -> {
                        val pending = attempted.copy(
                            notBeforeMs = clock() + AppConstants.Router.weshBluetoothRetryDelayMs(attempted.bluetoothAttempts),
                            afterBluetoothWrite = false
                        )
                        persistBluetoothRetry(conversationID, pending)
                        afterFailedBluetoothWrite(pending, conversationID, epoch, cancelled)
                    }
                    // Some bytes may have left: « En attente » under the same ID; after the ack window
                    // (rule 4) it is retried, over Bluetooth if in range, else Internet; receivers dedup.
                    com.bitchat.android.mesh.BoundSendOutcome.UNCONFIRMED -> {
                        val pending = attempted.copy(
                            notBeforeMs = clock() + AppConstants.Router.weshBluetoothRetryDelayMs(attempted.bluetoothAttempts),
                            afterBluetoothWrite = true
                        )
                        persistBluetoothRetry(conversationID, pending)
                        requeueWesh(pending, conversationID, epoch, cancelled)
                    }
                    // The session or key no longer matches: never elsewhere.
                    com.bitchat.android.mesh.BoundSendOutcome.AUTH_FAILED -> AppStateStore.updatePrivateMessageStatus(conversationID, messageID,
                        com.bitchat.android.model.DeliveryStatus.Failed(WeshPrivateSender.FAILED_WRITE))
                }
            }
        }
    }

    private fun persistBluetoothRetry(conversationID: String, entry: QueuedMessage) {
        AppStateStore.updateBluetoothRetryState(
            conversationID = conversationID,
            messageID = entry.messageID,
            attempts = entry.bluetoothAttempts,
            retryAtMs = entry.notBeforeMs,
            mayHaveLeft = entry.afterBluetoothWrite
        )
    }

    /**
     * After a Bluetooth write that reached nothing, when an Internet route exists for the contact: over Internet
     * from now on, with its deadline, backoff and wrap (« Envoyé » on relay OK, else waiting).
     * Without that route: « Échec » with Réessayer. Deleted, blocked or removed meanwhile: « Échec ».
     */
    private fun afterFailedBluetoothWrite(entry: QueuedMessage, conversationID: String, epoch: Long, cancelled: Long) {
        if (epoch != weshEpoch.get()) return
        val next = entry
        when {
            (weshCancelled[conversationID] ?: 0L) != cancelled -> AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID,
                com.bitchat.android.model.DeliveryStatus.Failed("Contact retiré ou bloqué"))
            next.bluetoothAttempts >= AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS &&
                !weshInternet().canSend(conversationID) -> AppStateStore.updatePrivateMessageStatus(
                    conversationID,
                    entry.messageID,
                    com.bitchat.android.model.DeliveryStatus.Failed(WeshPrivateSender.FAILED_NO_ACK)
                )
            // Rule 3: no route now, so it waits for the next bounded Bluetooth retry.
            !weshInternet().canSend(conversationID) -> requeueWesh(next, conversationID, epoch, cancelled)
            next.internetRetryAtMs > clock() -> requeueWesh(next, conversationID, epoch, cancelled)
            else -> dispatchWeshInternet(next, conversationID, epoch, since = cancelled)
        }
    }

    /**
     * « Envoyé » only once a relay answered `OK`. Otherwise the message goes back in the queue
     * (« En attente ») until the 24 h TTL: with a backoff step after anything that reached a relay,
     * keeping an unconfirmed wrap for the retry and dropping a rejected one.
     */
    private fun dispatchWeshInternet(
        entry: QueuedMessage,
        conversationID: String,
        epoch: Long = weshEpoch.get(),
        /** Cancel generation captured when the send began: a cancel since then stops it. */
        since: Long
    ) {
        val cancelled = since
        if ((weshCancelled[conversationID] ?: 0L) != since) {
            AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID,
                com.bitchat.android.model.DeliveryStatus.Failed("Contact retiré ou bloqué"))
            return
        }
        if (entry.publications >= AppConstants.Router.WESH_INTERNET_MAX_PUBLICATIONS) {
            // Past the hard cap: it waits for its TTL, then fails. Never reached from flushOutboxFor,
            // which checks the cap first (an enqueue here would mutate the list it iterates).
            enqueueWesh(conversationID, entry, epoch, since)
            return
        }
        if (epoch != weshEpoch.get() || !weshInFlight.add(entry.messageID)) return
        schedulerScope.launch {
            // Requeued only after leaving the in-flight set: a flush in between could otherwise
            // take the entry out of the outbox and then refuse to dispatch it.
            val retry = try {
                run attempt@{
                    if (epoch != weshEpoch.get()) return@attempt null
                    // Internet only (Bluetooth resends are always allowed, receivers deduplicate): the
                    // stored status decides. Acked or deleted: stop. Store unavailable: next tick.
                    when (AppStateStore.persistedAck(conversationID, entry.messageID)) {
                        AppStateStore.PersistedAck.ACKED -> return@attempt null
                        AppStateStore.PersistedAck.GONE -> {
                            Log.d(TAG, "Queued msg_id=${entry.messageID.take(8)}… no longer in its conversation; not sent")
                            return@attempt null
                        }
                        // Skips this attempt only: requeued unchanged once its in-flight mark is gone.
                        AppStateStore.PersistedAck.UNAVAILABLE -> return@attempt entry
                        AppStateStore.PersistedAck.PENDING -> Unit
                    }
                    // Re-checked right before the publish: a delete, block or erase since then wins.
                    val stillWanted = { epoch == weshEpoch.get() && (weshCancelled[conversationID] ?: 0L) == cancelled }
                    val send = weshInternet().sendPrivate(entry.content, conversationID, entry.messageID, entry.giftWrap,
                        allowed = stillWanted)
                    if (epoch != weshEpoch.get()) return@attempt null
                    val now = clock()
                    when (send.outcome) {
                        RelayPublishOutcome.ACCEPTED -> {
                            AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID, com.bitchat.android.model.DeliveryStatus.Sent,
                                com.bitchat.android.model.MessageTransport.INTERNET)
                            null
                        }
                        // Nothing left: no backoff step, and the signed wrap is kept rather than re-signed.
                        RelayPublishOutcome.NOT_SENT -> entry.copy(giftWrap = send.giftWrap ?: entry.giftWrap,
                            internetRetryAtMs = entry.scheduledRetryAtMs ?: entry.internetRetryAtMs, scheduledRetryAtMs = null)
                        RelayPublishOutcome.REJECTED, RelayPublishOutcome.UNCONFIRMED -> {
                            val published = entry.copy(
                                publications = entry.publications + 1,
                                lastPublishedAtMs = now,
                                giftWrap = send.giftWrap.takeIf { send.outcome == RelayPublishOutcome.UNCONFIRMED },
                                scheduledRetryAtMs = null
                            )
                            // An early retry leaves the backoff where it was; a scheduled one takes the next step.
                            if (entry.scheduledRetryAtMs != null) {
                                // Never in the past (an early attempt that left late): at least the first step.
                                published.copy(internetRetryAtMs = maxOf(entry.scheduledRetryAtMs,
                                    now + AppConstants.Router.weshInternetRetryDelayMs(1)))
                            } else {
                                val attempts = entry.internetAttempts + 1
                                published.copy(internetAttempts = attempts,
                                    internetRetryAtMs = now + AppConstants.Router.weshInternetRetryDelayMs(attempts))
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                synchronized(this@MessageRouter) { weshInFlight.remove(entry.messageID) }
                throw e
            }
            // One step under the router monitor: a relaunch restore or a flush never sees this
            // message as neither in flight nor queued, so its backoff and publication count hold.
            synchronized(this@MessageRouter) {
                weshInFlight.remove(entry.messageID)
                retry?.let { requeueWesh(it, conversationID, epoch, cancelled) }
            }
        }
    }

    private enum class AckState { ACKED, NOT_ACKED, UNKNOWN }

    /** From the loaded conversation, keyed like the store; UNKNOWN when its history is not loaded. */
    private fun ackState(conversationID: String, messageID: String): AckState {
        val canonical = ContactDirectory.canonicalConversationId(conversationID)
        val status = AppStateStore.privateMessages.value[canonical].orEmpty().firstOrNull { it.id == messageID }
            ?: return AckState.UNKNOWN
        return when (status.deliveryStatus) {
            is com.bitchat.android.model.DeliveryStatus.Delivered, is com.bitchat.android.model.DeliveryStatus.Read -> AckState.ACKED
            else -> AckState.NOT_ACKED
        }
    }

    private fun isAcked(conversationID: String, messageID: String): Boolean = ackState(conversationID, messageID) == AckState.ACKED

    /** Under the router monitor: never after a panic clear, never for a removed or blocked contact. */
    @Synchronized private fun requeueWesh(entry: QueuedMessage, conversationID: String, epoch: Long, cancelled: Long) {
        if (epoch != weshEpoch.get()) return
        // Deleted, blocked or removed since this attempt started: nothing more leaves, and the
        // message ends « Échec » (a no-op once it is deleted).
        if ((weshCancelled[conversationID] ?: 0L) != cancelled) {
            AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID,
                com.bitchat.android.model.DeliveryStatus.Failed("Contact retiré ou bloqué"))
            return
        }
        // A retry took over meanwhile and is in flight: one copy only. A queued copy is merged on enqueue.
        if (entry.messageID in weshInFlight) return
        if (WeshPrivateSender(WeshContactStore.get(context)).enrolled(conversationID)) {
            enqueueWesh(conversationID, entry, epoch, cancelled)
        } else {
            AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID,
                com.bitchat.android.model.DeliveryStatus.Failed("Contact retiré ou bloqué"))
        }
    }

    /**
     * Sends `[FAVORITED]:<my npub>` over the live Noise session with the enrolled key, so official
     * BitChat peers learn where to reply over Internet. Only while Internet mode is active (my key
     * exists from onboarding but is used only then), once per contact and npub. Never creates a key; a received notice is never trusted.
     */
    fun announceWeshInternetKey(peerID: String) {
        if (!enforceWeshPolicy) return
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        // Captured first: an erase from here on stops the notice, up to the radio write.
        val epoch = weshEpoch.get()
        schedulerScope.launch {
            val contacts = WeshContactStore.get(context)
            val sender = WeshPrivateSender(contacts)
            val npub = runCatching { weshMyNpub() }.getOrNull() ?: return@launch
            val contact = contacts.contact(conversationID) ?: return@launch
            val live = ContactDirectory.resolve(conversationID).meshPeerID ?: return@launch
            if (!sender.canSend(conversationID, mesh)) return@launch
            val announced = conversationID + npub
            if (!weshAnnounced.add(announced)) return@launch
            val sent = runCatching {
                mesh.sendPrivateMessageBound(FavoriteControlMessage.encode(true, npub), live, contact.key,
                    java.util.UUID.randomUUID().toString()) {
                    // Re-checked right before the write: still that contact, no erase, still my npub.
                    sender.canSend(conversationID, mesh) && epoch == weshEpoch.get() &&
                        runCatching { weshMyNpub() }.getOrNull() == npub
                }
            }.getOrDefault(false)
            if (!sent) weshAnnounced.remove(announced)
        }
    }

    /**
     * Internet just turned On: every enrolled contact with a live Bluetooth session learns my npub
     * now, not only at the next session (official BitChat replies over Internet only once it has it).
     * Once per contact and npub, like [announceWeshInternetKey].
     */
    fun onWeshInternetOn() {
        if (!enforceWeshPolicy) return
        runCatching { WeshContactStore.get(context).contacts.value }.getOrDefault(emptyList())
            .forEach { contact -> announceWeshInternetKey(contact.conversationID) }
        onWeshInternetReconnected()
    }

    /**
     * A relay reconnected or Internet turned On: one early retry for each waiting message whose
     * last publication is at least 5 min old; its backoff is otherwise unchanged (spec « Retry policy »).
     */
    fun onWeshInternetReconnected() {
        if (!enforceWeshPolicy) return
        synchronized(this) {
            val now = clock()
            outbox.values.forEach { queue ->
                queue.replaceAll { entry ->
                    if (entry.lastPublishedAtMs > 0L && entry.scheduledRetryAtMs == null &&
                        now - entry.lastPublishedAtMs >= AppConstants.Router.WESH_INTERNET_EARLY_RETRY_MIN_GAP_MS &&
                        entry.internetRetryAtMs > now &&
                        entry.publications < AppConstants.Router.WESH_INTERNET_MAX_PUBLICATIONS
                    ) entry.copy(scheduledRetryAtMs = entry.internetRetryAtMs, internetRetryAtMs = now) else entry
                }
            }
            tickOutbox(now)
        }
    }

    private val weshRestoreStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Once per process, after the conversation store is loaded: see [restoreWeshOutbox]. */
    fun restoreWeshOutboxOnce() {
        if (!enforceWeshPolicy || !weshRestoreStarted.compareAndSet(false, true)) return
        schedulerScope.launch {
            try {
                restoreWeshOutbox()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                weshRestoreStarted.set(false)
                throw cancelled
            } catch (e: Exception) {
                weshRestoreStarted.set(false)
                Log.w(TAG, "Outbox restore failed: ${e.message}")
            }
        }
    }

    /**
     * After a relaunch (the outbox is memory only): every message of mine still « En attente » in
     * the store goes back in the outbox with its original deadline, so it is published once at the
     * next pass, then backs off from 30 s. Past its 24 h TTL it fails. Returns how many were queued.
     */
    internal suspend fun restoreWeshOutbox(): Int {
        if (!enforceWeshPolicy) return 0
        val epoch = weshEpoch.get()
        // Captured before the read: a delete or block during it keeps that conversation's messages out.
        val cancelledBefore = synchronized(this) { HashMap(weshCancelled) }
        val pending = AppStateStore.pendingOutgoingPrivateMessages() ?: run {
            weshRestoreStarted.set(false)
            return 0
        }
        val sender = WeshPrivateSender(WeshContactStore.get(context))
        var restored = 0
        synchronized(this) {
            val now = clock()
            for (storedDelivery in pending) {
                val stored = storedDelivery.conversationID
                val message = storedDelivery.message
                if (epoch != weshEpoch.get()) {
                    weshRestoreStarted.set(false)
                    return 0
                }
                val conversationID = ContactDirectory.canonicalConversationId(stored)
                if (!sender.enrolled(conversationID)) continue
                if (message.id in weshInFlight || outbox[conversationID].orEmpty().any { it.messageID == message.id }) continue
                val enqueuedAt = message.timestamp.time
                if (now - enqueuedAt > OUTBOX_MESSAGE_TTL_MS) {
                    notifyExpired(conversationID, message.id)
                    continue
                }
                val entry = QueuedMessage(
                    content = message.content,
                    nickname = message.recipientNickname ?: "",
                    messageID = message.id,
                    enqueuedAtMs = enqueuedAt,
                    notBeforeMs = storedDelivery.bluetoothRetryAtMs,
                    bluetoothAttempts = storedDelivery.bluetoothAttempts,
                    afterBluetoothWrite = storedDelivery.bluetoothMayHaveLeft
                )
                if (enqueueWesh(conversationID, entry, epoch, cancelledBefore[conversationID] ?: 0L)) restored++
            }
        }
        if (restored > 0) Log.d(TAG, "Restored $restored waiting private message(s) to the outbox")
        return restored
    }

    /** Acquires the outbox monitor so tests cannot observe the remove-then-requeue transition. */
    internal val weshIdleForTesting: Boolean get() = synchronized(this) { weshInFlight.isEmpty() }

    /** Test-only visibility for the Rule 4 handoff from a completed Bluetooth write to its timer. */
    internal fun hasPendingBluetoothFallbackForTesting(messageID: String): Boolean = synchronized(this) {
        outbox.values.any { queue -> queue.any { it.messageID == messageID && it.afterBluetoothWrite } }
    }

    /** Queues only if no panic happened since [epoch]; false means nothing is kept. */
    @Synchronized private fun enqueueWesh(conversationID: String, entry: QueuedMessage, epoch: Long, cancelled: Long? = null): Boolean {
        if (epoch != weshEpoch.get()) return false
        // Deleted or blocked since the send began: nothing is kept.
        if (cancelled != null && (weshCancelled[conversationID] ?: 0L) != cancelled) return false
        // Already waiting (a retry, or a relaunch restore racing a requeue): one copy, which keeps
        // the stronger retry state of both, so neither the backoff nor the publication cap resets.
        val queue = outbox[conversationID]
        val index = queue?.indexOfFirst { it.messageID == entry.messageID } ?: -1
        if (queue != null && index >= 0) {
            queue[index] = MessageRouter.strongerRetryState(queue[index], entry)
            return true
        }
        enqueue(conversationID, entry)
        // clearAll bumps the epoch before clearing: a clear that raced this enqueue removes it here.
        if (epoch != weshEpoch.get()) {
            outbox[conversationID]?.removeAll { it.messageID == entry.messageID }
            return false
        }
        return true
    }


    @Synchronized private fun dequeueWesh(conversationID: String, messageID: String): QueuedMessage? {
        val queue = outbox[conversationID] ?: return null
        val previous = queue.firstOrNull { it.messageID == messageID } ?: return null
        queue.removeAll { it.messageID == messageID }
        return previous
    }

    @Synchronized fun cancelWeshConversation(conversationID: String) {
        weshCancelled.merge(conversationID, 1L, Long::plus)
        outbox.remove(conversationID)?.forEach { entry ->
            AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID, com.bitchat.android.model.DeliveryStatus.Failed("Contact retiré"))
        }
        retryState.remove(conversationID)
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        if (!runCatching { weshReadReceiptsEnabled() }.getOrDefault(false)) return
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing READ via mesh to ${meshTarget.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, meshTarget, mesh.getPeerNicknames()[meshTarget] ?: mesh.myPeerID)
        } else if (enforceWeshPolicy) {
            if (!weshInternetLink.isOn()) return
            val route = weshInternet()
            if (route.canSend(resolution.conversationID)) schedulerScope.launch {
                route.sendAck(com.bitchat.android.model.NoisePayloadType.READ_RECEIPT, receipt.originalMessageID, resolution.conversationID) {
                    runCatching { weshReadReceiptsEnabled() }.getOrDefault(false)
                }
            }
        } else {
            Log.d(TAG, "Routing READ via Nostr to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            nostr.sendReadReceipt(receipt, nostrTarget)
        }
    }

    /** Whether the user allows outgoing READ receipts (« Accusés de lecture (Lu) »). */
    internal var weshReadReceiptsEnabled: () -> Boolean =
        { com.bitchat.android.ui.WeshPreferences.readReceiptsEnabled(context) }
    /** Records a READ as sent unless an erase came first; the seen store in production. */
    internal var markWeshReadReceiptSent: (messageID: String, expectedToken: SeenReceiptAdmission.Token?) -> Boolean =
        { messageID, expectedToken -> SeenMessageStore.getInstance(context).markReadReceiptSent(messageID, expectedToken) }
    internal var hasWeshReadReceiptBeenSent: (messageID: String) -> Boolean =
        { messageID -> SeenMessageStore.getInstance(context).hasReadReceiptBeenSent(messageID) }
    /** READs being published: an open and an arrival racing for one message send it once. */
    private val weshReadInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * READ for a message that arrived over Internet: it goes back over Internet (spec §3 rule 5), only
     * to an enrolled contact while Internet is on. [expectedToken] is captured when the message was
     * read; after an erase nothing leaves. Recorded as sent once published, so it goes out once.
     */
    internal fun sendWeshInternetReadReceipt(messageID: String, conversationID: String, expectedToken: SeenReceiptAdmission.Token?) {
        if (!enforceWeshPolicy || expectedToken == null || !runCatching { weshReadReceiptsEnabled() }.getOrDefault(false)) return
        schedulerScope.launch { sendWeshInternetReadReceiptNow(messageID, conversationID, expectedToken) }
    }

    internal suspend fun sendWeshInternetReadReceiptNow(
        messageID: String,
        conversationID: String,
        expectedToken: SeenReceiptAdmission.Token?
    ): Boolean {
        if (expectedToken == null || !expectedToken.owner.admits(expectedToken)) return false
        if (!runCatching { weshReadReceiptsEnabled() }.getOrDefault(false)) return false
        if (runCatching { hasWeshReadReceiptBeenSent(messageID) }.getOrDefault(true)) return false
        if (!weshReadInFlight.add(messageID)) return false
        try {
            val route = weshInternet()
            if (!route.canSend(conversationID)) return false
            // Checked again right before it leaves: an erase while the wrap was built sends nothing.
            if (!route.sendAck(com.bitchat.android.model.NoisePayloadType.READ_RECEIPT, messageID, conversationID) {
                    expectedToken.owner.admits(expectedToken) && runCatching { weshReadReceiptsEnabled() }.getOrDefault(false)
                }) return false
            runCatching { markWeshReadReceiptSent(messageID, expectedToken) }
            return true
        } finally {
            weshReadInFlight.remove(messageID)
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a Nostr alias
        if (enforceWeshPolicy) return
        if (com.bitchat.android.nostr.NostrAliasRegistry.contains(toPeerID)) {
            val recipientHex = com.bitchat.android.nostr.NostrAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckToNostrKey(messageID, recipientHex, try { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (!(meshTarget != null && (mesh.getPeerInfo(meshTarget)?.isConnected == true) && mesh.hasEstablishedSession(meshTarget))) {
            nostr.sendDeliveryAck(messageID, resolution.noiseKeyHex ?: toPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (meshTarget != null && mesh.getPeerInfo(meshTarget)?.isConnected == true && mesh.hasEstablishedSession(meshTarget)) {
            // Wesh: my npub goes only to an enrolled contact, bound to its key, through
            // announceWeshInternetKey. A favorite star never reveals it, and never creates a key.
            val myNpub = if (enforceWeshPolicy) null else try {
                com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
            } catch (_: Exception) { null }
            val content = FavoriteControlMessage.encode(isFavorite, myNpub)
            val nickname = mesh.getPeerNicknames()[meshTarget] ?: meshTarget
            mesh.sendPrivateMessage(content, meshTarget, nickname, null)
        } else if (!enforceWeshPolicy) {
            nostr.sendFavoriteNotification(resolution.noiseKeyHex ?: toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID.
    // All outbox mutations happen under the router monitor so a concurrent enqueue cannot
    // be lost between the empty check and the map removal.
    @Synchronized
    fun flushOutboxFor(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val queued = outbox[conversationID] ?: outbox[peerID] ?: return
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing outbox for ${conversationID.take(16)}… count=${queued.size}")
        val epoch = weshEpoch.get()
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            // A panic cleared the router: nothing captured before it may leave.
            if (epoch != weshEpoch.get()) return
            val entry = iterator.next()
            val resolution = ContactDirectory.resolve(conversationID)
            val meshTarget = resolution.meshPeerID
            val nostrTarget = resolution.noiseKeyHex ?: conversationID
            if (enforceWeshPolicy) {
                val sender = WeshPrivateSender(WeshContactStore.get(context))
                if (!sender.enrolled(conversationID)) {
                    iterator.remove()
                    com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID,
                        com.bitchat.android.model.DeliveryStatus.Failed("Contact retiré ou bloqué"))
                } else if (isAcked(conversationID, entry.messageID)) {
                    // Its ack arrived (rule 4: resend only without one): nothing more to send.
                    iterator.remove()
                } else if (entry.notBeforeMs > clock()) {
                    // Inside the ack window of a Bluetooth write that may have left.
                } else if (entry.afterBluetoothWrite) {
                    if (sender.canSend(conversationID, mesh) &&
                        entry.bluetoothAttempts < AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS) {
                        // A transport callback is not a delivery ack. Retry on the same authenticated
                        // route and with the same message ID; receiver admission deduplicates it.
                        iterator.remove()
                        dispatchWesh(entry.copy(afterBluetoothWrite = false), conversationID, epoch,
                            weshCancelled[conversationID] ?: 0L)
                    } else if (entry.internetRetryAtMs > clock() ||
                        entry.publications >= AppConstants.Router.WESH_INTERNET_MAX_PUBLICATIONS) {
                        // Waiting for its next Internet attempt, or for its TTL past the cap.
                    } else if (weshInternet().canSend(conversationID)) {
                        iterator.remove()
                        dispatchWeshInternet(entry, conversationID, epoch, since = weshCancelled[conversationID] ?: 0L)
                    } else if (entry.bluetoothAttempts >= AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS) {
                        iterator.remove()
                        AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID,
                            com.bitchat.android.model.DeliveryStatus.Failed(WeshPrivateSender.FAILED_NO_ACK))
                    }
                } else if (sender.canSend(conversationID, mesh) &&
                    entry.bluetoothAttempts < AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS) {
                    // Bluetooth first whenever the contact is in range again, same message ID.
                    dispatchWesh(entry, conversationID, epoch, weshCancelled[conversationID] ?: 0L)
                    iterator.remove()
                } else if (entry.publications >= AppConstants.Router.WESH_INTERNET_MAX_PUBLICATIONS) {
                    // Past the Internet hard cap: it waits for its TTL, then fails.
                } else if (entry.internetRetryAtMs <= clock() && weshInternet().canSend(conversationID)) {
                    iterator.remove()
                    dispatchWeshInternet(entry, conversationID, epoch, since = weshCancelled[conversationID] ?: 0L)
                } else if (entry.bluetoothAttempts >= AppConstants.Router.WESH_BLUETOOTH_MAX_ATTEMPTS) {
                    iterator.remove()
                    AppStateStore.updatePrivateMessageStatus(conversationID, entry.messageID,
                        com.bitchat.android.model.DeliveryStatus.Failed(WeshPrivateSender.FAILED_NO_ACK))
                }
                continue
            }
            if (meshTarget != null && isReady(mesh, meshTarget)) {
                mesh.sendPrivateMessage(entry.content, meshTarget, entry.nickname, entry.messageID)
                iterator.remove()
            } else if (canSendViaNostr(nostrTarget)) {
                nostr.sendPrivateMessage(entry.content, nostrTarget, entry.nickname, entry.messageID)
                iterator.remove()
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            outbox.remove(peerID, queued)
            retryState.remove(conversationID)
            retryState.remove(peerID)
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    @Synchronized
    private fun enqueue(conversationID: String, entry: QueuedMessage) {
        val queue = outbox.getOrPut(conversationID) { mutableListOf() }
        queue.add(entry)
        while (queue.size > OUTBOX_MAX_PER_PEER) {
            val evicted = queue.removeAt(0)
            Log.w(TAG, "Outbox full for ${conversationID.take(16)}…; evicting oldest msg_id=${evicted.messageID.take(8)}…")
            notifyExpired(conversationID, evicted.messageID)
        }
    }

    private fun notifyExpired(conversationID: String, messageID: String) {
        try { onMessageExpired?.invoke(conversationID, messageID) } catch (_: Exception) { }
    }

    /**
     * Initiate a Noise handshake for a conversation with queued messages, applying
     * exponential backoff between attempts. [immediate] resets the backoff (peer just
     * appeared or a new message was queued). Kicks are suppressed while a previous
     * attempt is still inside its backoff window, so alias duplicates and frequent
     * peer-list updates cannot spam handshakes.
     */
    @Synchronized
    private fun kickHandshake(conversationID: String, meshTarget: String, immediate: Boolean) {
        val now = clock()
        val current = retryState[conversationID]
        if (current != null && now < current.nextHandshakeAttemptAtMs) return
        val attempts = if (immediate) 0 else (current?.handshakeAttempts ?: 0)
        try { mesh.initiateNoiseHandshake(meshTarget) } catch (_: Exception) { }
        val backoff = HANDSHAKE_RETRY_BACKOFF_MS[attempts.coerceAtMost(HANDSHAKE_RETRY_BACKOFF_MS.size - 1)]
        retryState[conversationID] = ConversationRetry(
            handshakeAttempts = attempts + 1,
            nextHandshakeAttemptAtMs = now + backoff
        )
        Log.d(TAG, "Handshake attempt ${attempts + 1} for ${conversationID.take(16)}…, next retry in ${backoff}ms")
    }

    @Synchronized
    private fun startOutboxScheduler() {
        if (disableSchedulerForTesting) return
        if (schedulerJob?.isActive == true) return
        schedulerJob = schedulerScope.launch {
            while (isActive) {
                delay(OUTBOX_TICK_MS)
                try { tickOutbox() } catch (e: Exception) {
                    Log.w(TAG, "Outbox scheduler tick failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop retrying while the mesh transports are down. Persistent network work must
     * follow the MeshForegroundService lifecycle; getInstance restarts the scheduler
     * and rebinds the mesh reference when the service comes back.
     */
    fun stopOutboxScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    internal val isSchedulerRunning: Boolean get() = schedulerJob?.isActive == true

    /**
     * One scheduler pass over the outbox: expire old entries, flush what can be sent,
     * and re-initiate handshakes (with backoff) for peers that are connected but have
     * no established session yet.
     */
    @Synchronized
    internal fun tickOutbox(nowMs: Long = clock()) {
        outbox.keys.toList().forEach { conversationID ->
            expireOldEntries(conversationID, nowMs)
            val queued = outbox[conversationID] ?: return@forEach
            if (queued.isEmpty()) return@forEach

            val resolution = ContactDirectory.resolve(conversationID)
            val meshTarget = resolution.meshPeerID

            if (meshTarget != null && isReady(mesh, meshTarget)) {
                flushOutboxFor(conversationID)
                return@forEach
            }
            if (canSendViaNostr(resolution.noiseKeyHex ?: conversationID) ||
                (enforceWeshPolicy && weshInternetLink.isOn() && weshInternet().canSend(conversationID))) {
                flushOutboxFor(conversationID)
                return@forEach
            }
            // Peer visible but no session: retry the handshake with backoff.
            if (meshTarget != null && isConnected(mesh, meshTarget)) {
                kickHandshake(conversationID, meshTarget, immediate = false)
            }
        }
    }

    private fun expireOldEntries(conversationID: String, nowMs: Long) {
        val queued = outbox[conversationID] ?: return
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.enqueuedAtMs > OUTBOX_MESSAGE_TTL_MS) {
                Log.w(TAG, "Expiring queued PM for ${conversationID.take(16)}… msg_id=${entry.messageID.take(8)}…")
                iterator.remove()
                notifyExpired(conversationID, entry.messageID)
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            retryState.remove(conversationID)
        }
    }

    private fun canSendViaNostr(peerID: String): Boolean {
        // Every Nostr route needs the runtime Internet opt-in; Wesh never routes via favorites.
        if (enforceWeshPolicy || !com.bitchat.android.net.WeshNetworkPolicy.allowsNetwork()) return false
        return try {
            val resolution = ContactDirectory.resolve(peerID)
            if (resolution.isMutualFavorite && resolution.nostrPubkey != null) return true
            val target = resolution.noiseKeyHex ?: peerID
            if (ContactIdentityResolver.isNoiseKeyHex(target)) {
                val noiseKey = ContactIdentityResolver.bytesFromHex(target) ?: return false
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (ContactIdentityResolver.isMeshPeerId(target)) {
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(target)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun isConnected(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isReady(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true &&
                service.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            kickHandshakeIfPending(pid)
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
            } catch (_: Exception) { null }
            noiseHex?.let {
                kickHandshakeIfPending(it)
                flushOutboxFor(it)
            }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        announceWeshInternetKey(peerID)
        resetRetry(peerID)
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
        } catch (_: Exception) { null }
        noiseHex?.let {
            resetRetry(it)
            flushOutboxFor(it)
        }
    }

    /** Reset handshake backoff for a conversation whose session just came up. */
    private fun resetRetry(peerID: String) {
        retryState.remove(ContactDirectory.canonicalConversationId(peerID))
        retryState.remove(peerID)
    }

    /**
     * A peer (re)appeared: if we still owe them queued messages and there is no working
     * session yet, restart the handshake immediately instead of waiting for the backoff.
     */
    @Synchronized
    private fun kickHandshakeIfPending(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val queued = outbox[conversationID] ?: outbox[peerID] ?: return
        if (queued.isEmpty()) return
        val resolution = ContactDirectory.resolve(conversationID)
        val meshTarget = resolution.meshPeerID ?: return
        if (isReady(mesh, meshTarget)) return
        if (!isConnected(mesh, meshTarget)) return
        Log.d(TAG, "Peer ${meshTarget.take(8)}… reappeared with ${queued.size} queued PM(s); re-initiating handshake")
        kickHandshake(conversationID, meshTarget, immediate = true)
    }
}
