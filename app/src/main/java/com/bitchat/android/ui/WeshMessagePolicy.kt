package com.bitchat.android.ui

import com.bitchat.android.util.booleanOr
import com.bitchat.android.util.stringOr
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.BitchatMessage
import java.util.Date
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.hexEncodedString

/** UI decisions depend on authenticated identity, never a nickname or parsed QR alone. */
internal object WeshMessagePolicy {
    fun sessionMatches(sessionKey: ByteArray?, expectedNoiseHex: String): Boolean =
        sessionKey?.size == 32 && sessionKey.hexEncodedString().equals(expectedNoiseHex, true)

    fun canSendPrivate(sessionKey: ByteArray?, expectedKey: ByteArray?, verified: Set<String>): Boolean =
        sessionKey != null && expectedKey != null && sessionKey.contentEquals(expectedKey) &&
            verified.contains(ContactIdentityResolver.fingerprintHex(sessionKey))

    data class PublicSendRequest(val content: String) {
        fun canConfirm(destination: String, currentDraft: String, bluetoothReady: Boolean): Boolean =
            destination == "public" && currentDraft == content && bluetoothReady && content.isNotBlank() && content.toByteArray().size <= 2000
    }

    suspend fun sendPublicText(
        content: String, nickname: String, myPeerID: String,
        store: (BitchatMessage) -> Unit,
        bluetoothSend: suspend (String) -> Boolean,
        authorized: () -> Boolean = { true }
    ): Boolean {
        if (content.isBlank() || content.toByteArray(Charsets.UTF_8).size > 2000) return false
        if (!authorized() || !bluetoothSend(content) || !authorized()) return false
        store(BitchatMessage(sender = nickname, content = content, timestamp = Date(),
            isRelay = false, senderPeerID = myPeerID))
        return true
    }

    /**
     * The Wesh public room: public text not from a blocked peer, and no system line. No local system
     * line sets senderPeerID to ours today, so every public system line is hidden; one without a known
     * local origin could carry a remote name or a BitChat notice.
     */
    fun showsInPublicRoom(message: BitchatMessage, myPeerID: String, isBlocked: (String) -> Boolean): Boolean =
        !message.isPrivate && message.channel == null &&
            (message.sender != "system" || message.senderPeerID == myPeerID) &&
            (message.senderPeerID == null || !isBlocked(message.senderPeerID))

    fun deliveryLabel(status: DeliveryStatus?): String = when (status) {
        null, DeliveryStatus.Sending -> "En attente d’envoi"
        DeliveryStatus.Sent -> "Transmis · réception non confirmée"
        is DeliveryStatus.Delivered -> "Réception confirmée"
        is DeliveryStatus.Read -> "Lu"
        is DeliveryStatus.Failed -> "Échec de l’envoi"
        is DeliveryStatus.PartiallyDelivered -> "Réception partielle"
    }
}

internal enum class MeetingNoteState { Unloaded, Loading, Ready, Unavailable, Fenced }

internal class WeshPreferences(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commitEditor: suspend (SharedPreferences.Editor) -> Boolean = { it.commit() },
    private val meetingBackendFactory: () -> WeshMeetingNoteBackend = { error("Meeting-note storage unavailable") },
    private val beforeNoteLoad: suspend () -> Unit = {}
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
        meetingBackendFactory = { EncryptedWeshMeetingNoteBackend(context.applicationContext) },
        beforeNoteLoad = { com.bitchat.android.startup.WeshRuntime.awaitTransportReady(context.applicationContext) }
    )

    companion object {
        const val FILE = "wesh_ui"
        private const val NOTIFICATION_SENDER = "notification_sender"
        private const val READ_RECEIPTS = "read_receipts"
        private const val LEGACY_MEETING_NOTE = "meeting_note"
        @Volatile private var shared: WeshPreferences? = null

        fun getInstance(context: Context): WeshPreferences = shared ?: synchronized(this) {
            shared ?: WeshPreferences(context.applicationContext).also { shared = it }
        }

        /** Notifications show no sender unless the person explicitly opted in on this phone. */
        fun notificationShowsSender(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .booleanOr(NOTIFICATION_SENDER, false)

        /** Read receipts (« Lu ») go out unless the person turned them off on this phone. */
        fun readReceiptsEnabled(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .booleanOr(READ_RECEIPTS, true)
    }

    private val lock = Any()
    private val io = Mutex()
    private var fenced = false
    private var meetingRevision = 0L
    private var draftRestored = false
    private var loadAttempt = 0L
    private data class MeetingSnapshot(val text: String, val epoch: Long, val revision: Long)
    private val _resetEpoch = MutableStateFlow(0L)
    val resetEpoch = _resetEpoch.asStateFlow()
    private val _meetingDraft = MutableStateFlow("")
    val meetingDraft = _meetingDraft.asStateFlow()
    private val _savedMeetingNote = MutableStateFlow("")
    val savedMeetingNote = _savedMeetingNote.asStateFlow()
    private val _meetingNoteState = MutableStateFlow(MeetingNoteState.Unloaded)
    val meetingNoteState = _meetingNoteState.asStateFlow()

    // Only the IO mutex owns backend instances and uncertain legacy cleanup.
    private var meetingBackend: WeshMeetingNoteBackend? = null
    private var backendEpoch: Long? = null
    private var pendingLegacyRemoval = false

    /** Serializes synchronous UI mutations with the panic fence; never use for suspending or blocking IO. */
    internal fun withCurrentEpoch(expectedEpoch: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (fenced || expectedEpoch != _resetEpoch.value) return@synchronized false
        action()
        true
    }

    var publicMuted: Boolean
        get() = prefs.booleanOr("public_muted", false)
        set(value) { synchronized(lock) { if (!fenced) prefs.edit { putBoolean("public_muted", value) } } }
    var publicDisclosureAccepted: Boolean
        get() = prefs.booleanOr("public_disclosure", false)
        set(value) { synchronized(lock) { if (!fenced) prefs.edit { putBoolean("public_disclosure", value) } } }
    var notificationShowsSender: Boolean
        get() = prefs.booleanOr(NOTIFICATION_SENDER, false)
        set(value) { synchronized(lock) { if (!fenced) prefs.edit { putBoolean(NOTIFICATION_SENDER, value) } } }
    var readReceiptsEnabled: Boolean
        get() = prefs.booleanOr(READ_RECEIPTS, true)
        set(value) { synchronized(lock) { if (!fenced) prefs.edit { putBoolean(READ_RECEIPTS, value) } } }
    var welcomed: Boolean
        get() = prefs.getBoolean("welcomed", false)
        set(value) { synchronized(lock) { if (!fenced) prefs.edit { putBoolean("welcomed", value) } } }
    var appearance: String
        get() = prefs.stringOr("appearance", "Nuit") ?: "Nuit"
        set(value) { synchronized(lock) { if (!fenced) prefs.edit { putString("appearance", value) } } }
    /** Cached confirmed text only; opening storage and Keystore always belongs to IO. */
    val meetingNote: String get() = _savedMeetingNote.value

    suspend fun loadMeetingNote(expectedEpoch: Long = _resetEpoch.value): Boolean {
        val attempt = synchronized(lock) {
            if (!isCurrentEpoch(expectedEpoch)) return false
            if (_meetingNoteState.value == MeetingNoteState.Ready) return true
            _meetingNoteState.value = MeetingNoteState.Loading
            ++loadAttempt
        }
        val loaded = try {
            withContext(ioDispatcher) {
                // Startup can wait for explicit recovery. Never hold the note
                // mutex while waiting: panic must still be able to erase it.
                beforeNoteLoad()
                io.withLock {
                    if (!isCurrentEpoch(expectedEpoch)) return@withLock false
                    if (_meetingNoteState.value == MeetingNoteState.Ready) return@withLock true
                    val backend = backendForLoad(expectedEpoch)
                    var saved = backend.load()
                    if (!isCurrentEpoch(expectedEpoch)) return@withLock false
                    val legacy = prefs.all
                    if (legacy.containsKey(LEGACY_MEETING_NOTE)) {
                        val text = legacy[LEGACY_MEETING_NOTE] as? String
                            ?: error("Invalid legacy meeting note")
                        if (saved == null) {
                            backend.write(text)
                            if (!isCurrentEpoch(expectedEpoch)) return@withLock false
                            saved = backend.load()
                            check(saved == text) { "Meeting note migration not confirmed" }
                        } else {
                            check(saved == text) { "Meeting note copies disagree" }
                        }
                        pendingLegacyRemoval = true
                    }
                    if (!isCurrentEpoch(expectedEpoch)) return@withLock false
                    if (pendingLegacyRemoval) {
                        check(saved != null) { "Meeting note migration not confirmed" }
                        // A failed commit can change only the RAM cache. Keep
                        // retrying a real remove+commit even if the key looks absent.
                        val committed = commitEditor(prefs.edit().remove(LEGACY_MEETING_NOTE))
                        check(committed && !prefs.contains(LEGACY_MEETING_NOTE)) { "Legacy note removal not confirmed" }
                        pendingLegacyRemoval = false
                    }
                    synchronized(lock) {
                        if (!isCurrentEpoch(expectedEpoch)) return@synchronized false
                        _savedMeetingNote.value = saved ?: ""
                        if (!draftRestored) {
                            _meetingDraft.value = saved ?: ""
                            draftRestored = true
                        }
                        _meetingNoteState.value = MeetingNoteState.Ready
                        true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (isCurrentEpoch(expectedEpoch) && loadAttempt == attempt && _meetingNoteState.value == MeetingNoteState.Loading) {
                    _meetingNoteState.value = MeetingNoteState.Unloaded
                }
            }
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) {
                if (isCurrentEpoch(expectedEpoch) && loadAttempt == attempt && _meetingNoteState.value == MeetingNoteState.Loading) {
                    _meetingNoteState.value = MeetingNoteState.Unavailable
                }
            }
            false
        }
        return loaded && isCurrentEpoch(expectedEpoch)
    }

    private fun backendForLoad(epoch: Long): WeshMeetingNoteBackend {
        if (meetingBackend == null || backendEpoch != epoch) {
            meetingBackend = meetingBackendFactory()
            backendEpoch = epoch
            pendingLegacyRemoval = false
        }
        return checkNotNull(meetingBackend)
    }

    fun editMeetingDraft(value: String, expectedEpoch: Long): Boolean = synchronized(lock) {
        if (!isCurrentEpoch(expectedEpoch) || _meetingNoteState.value != MeetingNoteState.Ready) return@synchronized false
        _meetingDraft.value = value
        meetingRevision++
        true
    }

    suspend fun saveMeetingDraft(expectedEpoch: Long): Boolean {
        val snapshot = synchronized(lock) {
            if (!isCurrentEpoch(expectedEpoch) || _meetingNoteState.value != MeetingNoteState.Ready) return false
            MeetingSnapshot(_meetingDraft.value, expectedEpoch, meetingRevision)
        }
        val confirmed = withContext(ioDispatcher) {
            io.withLock {
                if (!isCurrent(snapshot)) return@withLock false
                try {
                    val backend = backendForLoad(snapshot.epoch)
                    backend.write(snapshot.text)
                    if (!isCurrentEpoch(snapshot.epoch)) return@withLock false
                    val persisted = backend.load()
                    check(persisted == snapshot.text) { "Meeting note save not confirmed" }
                    synchronized(lock) {
                        if (isCurrentEpoch(snapshot.epoch)) _savedMeetingNote.value = snapshot.text
                    }
                    isCurrent(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }
        return confirmed && isCurrent(snapshot)
    }

    private fun isCurrentEpoch(epoch: Long): Boolean = synchronized(lock) {
        !fenced && epoch == _resetEpoch.value
    }
    private fun isCurrent(snapshot: MeetingSnapshot): Boolean = synchronized(lock) {
        isCurrentEpoch(snapshot.epoch) && snapshot.revision == meetingRevision
    }

    /** Blocks stale callbacks and clears both cached copies before any suspending wipe work. */
    fun fenceForPanic() {
        synchronized(lock) {
            fenced = true
            _meetingDraft.value = ""
            _savedMeetingNote.value = ""
            draftRestored = false
            _meetingNoteState.value = MeetingNoteState.Fenced
            meetingRevision++
            _resetEpoch.value++
        }
    }
}
