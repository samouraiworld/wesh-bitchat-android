package com.bitchat.android.ui.pave

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.bitchat.android.BuildConfig
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.net.WeshInternetMode
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.VerificationService
import com.bitchat.android.services.WeshContactStore
import com.bitchat.android.services.WeshMessageRequests
import com.bitchat.android.ui.MeetingNoteState
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.WeshDraftStore
import com.bitchat.android.ui.WeshMessagePolicy
import com.bitchat.android.ui.WeshPreferences
import com.bitchat.android.ui.weshNamesakes
import com.bitchat.android.util.dataFromHexString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Locale

/** The "Tout effacer" launcher shortcut: MainActivity raises it, the app opens the hold-to-erase screen. */
object PaveShortcuts {
    const val ACTION_HOLD_TO_ERASE = "org.samouraiworld.wesh.action.HOLD_TO_ERASE"
    private val _eraseRequested = MutableStateFlow(false)
    val eraseRequested: StateFlow<Boolean> = _eraseRequested.asStateFlow()

    fun requestErase() { _eraseRequested.value = true }
    fun consumeErase() { _eraseRequested.value = false }
}

/** Every Pavé destination. Back always goes to [parent]; Home has none. */
internal enum class PaveRoute(val parent: PaveRoute?) {
    Home(null), Public(Home), Private(Home), Requests(Home), Exchange(Home), Settings(Home),
    Profile(Settings), Discretion(Settings), Meeting(Settings), Blocked(Settings), Appearance(Settings),
    About(Settings), Licenses(About), Erase(Settings),
}

internal enum class PrivateOpenFollowUp { None, ReturnHome, EndSession, ReturnHomeAndEndSession }

internal fun canStartPrivateOpen(opening: String?): Boolean = opening == null

internal fun shouldAutoRoutePrivate(selected: String?, route: PaveRoute, opening: String?): Boolean =
    selected != null && route != PaveRoute.Exchange && opening == null

/**
 * Resolves the asynchronous private-open race after history loading finishes. Failure always
 * closes any partially opened session; leaving the loading screen closes a late successful open.
 */
internal fun privateOpenFollowUp(
    canonical: String,
    selected: String?,
    route: PaveRoute,
    failed: Boolean,
): PrivateOpenFollowUp = when {
    failed && route == PaveRoute.Private -> PrivateOpenFollowUp.ReturnHomeAndEndSession
    failed -> PrivateOpenFollowUp.EndSession
    selected == canonical && route != PaveRoute.Private -> PrivateOpenFollowUp.EndSession
    selected == null && route == PaveRoute.Private -> PrivateOpenFollowUp.ReturnHome
    selected != null && selected != canonical -> PrivateOpenFollowUp.ReturnHomeAndEndSession
    else -> PrivateOpenFollowUp.None
}

internal fun meetingNoteEditCallback(
    preferences: WeshPreferences,
    capturedEpoch: Long,
    onAccepted: () -> Unit
): (String) -> Unit = { text ->
    if (preferences.editMeetingDraft(text, capturedEpoch)) onAccepted()
}

internal fun composerDraftEditCallback(
    store: WeshDraftStore,
    destination: String?,
    onRejected: () -> Unit
): (String) -> Unit {
    val capturedEpoch = store.epoch
    return { text ->
        destination?.let { if (!store.save(capturedEpoch, it, text)) onRejected() }
    }
}

internal fun paveRouteFor(entry: PaveSettingsEntry): PaveRoute = when (entry) {
    PaveSettingsEntry.Profile -> PaveRoute.Profile
    PaveSettingsEntry.Connexion -> PaveRoute.Settings
    PaveSettingsEntry.Discretion -> PaveRoute.Discretion
    PaveSettingsEntry.Meeting -> PaveRoute.Meeting
    PaveSettingsEntry.Blocked -> PaveRoute.Blocked
    PaveSettingsEntry.Appearance -> PaveRoute.Appearance
    PaveSettingsEntry.About -> PaveRoute.About
}

/** Short fingerprint from a full SHA-256 fingerprint in hex (requests carry it that way). */
internal fun paveShortFingerprintFromHex(hex: String?): String? {
    val clean = hex?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (clean.length < 16 || clean.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return clean.take(16).uppercase(Locale.ROOT).chunked(4).joinToString(" ")
}

/** Delivery states as the spec names them. The public room has no receipts: never "Distribué" there. */
internal fun paveDelivery(status: DeliveryStatus?, public: Boolean): PaveDelivery =
    PaveDelivery.from(status, isPrivate = !public)

/**
 * Whether the composer takes typing: the conversation can send (Bluetooth ready, or a verified
 * contact), no public send is in flight, and drafts can be stored. Never the draft itself: an empty
 * field must take the first character, and the send button already checks the draft.
 */
internal fun paveComposerEnabled(conversationCanSend: Boolean, sending: Boolean, editable: Boolean): Boolean =
    conversationCanSend && !sending && editable

/** The open conversation's composer: [draft] fills the field, it never decides whether the field takes typing. */
internal fun paveComposerState(
    draft: String,
    conversationCanSend: Boolean,
    sending: Boolean,
    editable: Boolean,
    blockedReason: String?,
    feedback: String?,
    onRetryPersistence: (() -> Unit)?,
) = PaveComposerState(
    draft = draft,
    canSend = paveComposerEnabled(conversationCanSend, sending, editable),
    editable = editable,
    blockedReason = blockedReason,
    feedback = feedback,
    onRetryPersistence = onRetryPersistence,
)

/**
 * The fingerprint shown under a remote nickname: from the key the peer proved in its Noise session
 * only. An announced key can be copied by anyone, so without proof the line reads "Empreinte
 * indisponible" (null here).
 */
internal fun paveSenderFingerprint(peerID: String?, authenticatedKey: (String) -> ByteArray?): String? =
    peerID?.let { PaveFingerprint.short(authenticatedKey(it)) }

internal fun parseAppearance(name: String): WeshAppearance =
    WeshAppearance.entries.firstOrNull { it.name == name } ?: WeshAppearance.Nuit

/**
 * Wesh on Pavé Fluo: every screen of the app once onboarding is done. All state and actions come
 * from [ChatViewModel]; the safety rules of the previous surface (draft capture, first public send
 * disclosure, QR-only private chats, local block and report, erase recovery) are kept as they were.
 */
@Composable
fun WeshPaveApp(viewModel: ChatViewModel) {
    val prefs = viewModel.weshPreferences
    var appearance by rememberSaveable { mutableStateOf(parseAppearance(prefs.appearance)) }
    WeshPaveTheme(appearance.resolve()) {
        PaveNavHost(viewModel, appearance) { picked ->
            appearance = picked
            prefs.appearance = picked.name
        }
    }
}

@Composable
private fun PaveNavHost(viewModel: ChatViewModel, appearance: WeshAppearance, onAppearance: (WeshAppearance) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = viewModel.weshPreferences
    val preferencesEpoch by prefs.resetEpoch.collectAsState()
    val savedMeetingNote by prefs.savedMeetingNote.collectAsState()
    val meetingState by prefs.meetingNoteState.collectAsState()
    val publicMessages by viewModel.messages.collectAsState()
    val publicSending by viewModel.weshPublicSending.collectAsState()
    val privateChats by viewModel.privateChats.collectAsState()
    val peers by viewModel.connectedPeers.collectAsState()
    val contacts by viewModel.weshContacts.collectAsState()
    val drafts by viewModel.weshDraftStore.drafts.collectAsState()
    val draftPersistence by viewModel.weshDraftStore.persistence.collectAsState()
    val panicRecoveryRequired by com.bitchat.android.startup.WeshRuntime.draftWipeRequired.collectAsState()
    val panicInProgress by viewModel.weshPanicInProgress.collectAsState()
    val panicRecovery by com.bitchat.android.startup.WeshRuntime.recoveryState.collectAsState()
    val requestedContactSheet by viewModel.weshContactSheet.collectAsState()
    val requests by viewModel.weshRequests.collectAsState()
    val sessionHidden by viewModel.weshSessionHiddenPeers.collectAsState()
    val contactStoreFailed by viewModel.weshContactStoreFailed.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val selectedPrivate by viewModel.selectedPrivateChatPeer.collectAsState()
    val sessionStates by viewModel.peerSessionStates.collectAsState()
    val bluetoothReadyState by viewModel.weshBluetoothReadyState.collectAsState()
    val eraseRequested by PaveShortcuts.eraseRequested.collectAsState()
    val peerNicknames by viewModel.peerNicknames.collectAsState()

    var route by rememberSaveable { mutableStateOf(PaveRoute.Home) }
    var connexionOpen by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var publicRequest by remember { mutableStateOf<WeshMessagePolicy.PublicSendRequest?>(null) }
    var publicCapture by remember { mutableStateOf<WeshDraftStore.Capture?>(null) }
    var messageAction by remember { mutableStateOf<Pair<String, PaveReportTarget>?>(null) }
    var reportOffer by remember { mutableStateOf<PaveReportTarget?>(null) }
    var confirmAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var requestToBlock by remember { mutableStateOf<WeshMessageRequests.Request?>(null) }
    // Contact identifiers and locally typed notes stay out of Android's saved-instance bundle.
    var contactCardFor by remember { mutableStateOf<String?>(null) }
    // Bumped by local actions (block, unblock) whose effect is not observable as a flow.
    var revision by remember { mutableIntStateOf(0) }

    val myPeerID = viewModel.myPeerID
    val bluetoothReady = bluetoothReadyState || viewModel.weshBluetoothReady()
    val internet = remember {
        LiveWeshInternetModeState(
            WeshInternetMode.get(context),
            NostrRelayManager.shared.connectedCount,
            scope,
        )
    }
    val internetEnabled by internet.enabled.collectAsState()
    val internetStatus by internet.status.collectAsState()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    fun time(message: BitchatMessage) = timeFormat.format(message.timestamp)
    fun peerFingerprint(peerID: String?) = paveSenderFingerprint(peerID, viewModel::weshAuthenticatedKey)

    LaunchedEffect(eraseRequested) {
        if (eraseRequested) {
            route = PaveRoute.Erase
            PaveShortcuts.consumeErase()
        }
    }
    // The chat whose history is loading: its screen is already up, named, with a spinner.
    var opening by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedContactSheet) { if (requestedContactSheet) route = PaveRoute.Exchange }
    LaunchedEffect(selectedPrivate) {
        // An explicit open owns navigation until its completion policy runs. In particular, a
        // late success after Back must be closed, not allowed to reopen the screen for one frame.
        if (shouldAutoRoutePrivate(selectedPrivate, route, opening)) route = PaveRoute.Private
        if (selectedPrivate == null && route == PaveRoute.Private && opening == null) route = PaveRoute.Home
    }

    fun go(next: PaveRoute) {
        feedback = null
        contactCardFor = null
        if (route == PaveRoute.Private && next != PaveRoute.Private) viewModel.endPrivateChat()
        if (route == PaveRoute.Exchange) {
            viewModel.weshContactSheet.value = false
            viewModel.weshIncomingQR.value = null
        }
        route = next
    }
    // Opens the chat screen at once while startPrivateChat loads the history, one open at a time.
    // A chat the person left before it loaded is ended; one that could not open goes back Home.
    fun openPrivate(conversationID: String) {
        if (!canStartPrivateOpen(opening)) return
        val canonical = com.bitchat.android.services.ContactDirectory.canonicalConversationId(conversationID)
        val seenToken = com.bitchat.android.services.SeenMessageStore.captureReceiptToken()
        opening = canonical
        go(PaveRoute.Private)
        scope.launch {
            try {
                var failed = false
                try {
                    viewModel.startPrivateChat(conversationID, seenToken)
                } catch (cancelled: CancellationException) {
                    // A disposed screen must not leave a session selected after its UI disappeared.
                    viewModel.endPrivateChat()
                    if (route == PaveRoute.Private) route = PaveRoute.Home
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    // Do not put storage paths, contact data or provider wording in device logs.
                    Log.w("WeshPaveApp", "Private conversation could not be opened (${error.javaClass.simpleName})")
                }
                val selected = viewModel.selectedPrivateChatPeer.value
                when (privateOpenFollowUp(canonical, selected, route, failed)) {
                    PrivateOpenFollowUp.None -> Unit
                    PrivateOpenFollowUp.ReturnHome -> route = PaveRoute.Home
                    PrivateOpenFollowUp.EndSession -> viewModel.endPrivateChat()
                    PrivateOpenFollowUp.ReturnHomeAndEndSession -> {
                        viewModel.endPrivateChat()
                        route = PaveRoute.Home
                    }
                }
            } finally {
                // Keep this non-null until the follow-up above has closed a late session or moved
                // Home. LaunchedEffect must not turn a late selection back into navigation.
                opening = null
            }
        }
    }
    BackHandler(route != PaveRoute.Home || connexionOpen) {
        if (connexionOpen) connexionOpen = false else go(route.parent ?: PaveRoute.Home)
    }
    BackHandler(contactCardFor != null) { contactCardFor = null }

    // Erase states come first: nothing else may show while data is being wiped or must be recovered.
    if (panicInProgress) {
        ErasingScreen()
        return
    }
    if (panicRecoveryRequired) {
        com.bitchat.android.startup.StartupScreen(
            failed = true, onRetry = {}, draftWipeRequired = true, recovery = panicRecovery,
            onEraseAll = { com.bitchat.android.startup.WeshRuntime.clearInterruptedPanic(context) },
        )
        return
    }

    val privatePeer = selectedPrivate
    val publicOpen = route == PaveRoute.Public
    val destination = if (publicOpen) "public" else viewModel.weshDraftDestination(privatePeer)
    val draft = destination?.let(drafts::get).orEmpty()
    val canSendConversation = if (publicOpen) bluetoothReady else privatePeer != null && revision >= 0 &&
        sessionStates.let { viewModel.weshCanSendPrivate(privatePeer) }
    val editable = destination != null && draftPersistence != WeshDraftStore.Persistence.WipeRequired
    val persistenceProblem = when (draftPersistence) {
        WeshDraftStore.Persistence.Error -> stringResource(R.string.wesh_pave_fluo_draft_error)
        WeshDraftStore.Persistence.WipeRequired -> stringResource(R.string.wesh_pave_fluo_draft_wipe_required)
        else -> null
    }
    val composer = paveComposerState(
        draft = draft,
        conversationCanSend = canSendConversation,
        sending = publicSending,
        editable = editable,
        blockedReason = persistenceProblem ?: when {
            canSendConversation -> null
            publicOpen -> stringResource(R.string.wesh_pave_fluo_bluetooth_needed)
            privatePeer == null && opening != null -> null
            else -> stringResource(R.string.wesh_pave_fluo_private_needs_qr)
        },
        feedback = feedback,
        onRetryPersistence = if (draftPersistence == WeshDraftStore.Persistence.Error) viewModel::retryWeshDrafts else null,
    )
    val msgDestinationChanged = stringResource(R.string.wesh_pave_fluo_destination_changed)
    val msgDraftChanged = stringResource(R.string.wesh_pave_fluo_draft_changed)
    val msgPublicFailed = stringResource(R.string.wesh_pave_fluo_public_failed)
    val msgPrivateFailed = stringResource(R.string.wesh_pave_fluo_private_failed)
    val msgDraftTooLong = stringResource(R.string.wesh_pave_fluo_draft_too_long)
    val mediaUnsupported = stringResource(R.string.wesh_pave_fluo_media_unsupported)
    val msgReported = stringResource(R.string.wesh_pave_fluo_reported)
    val msgHiddenSession = stringResource(R.string.wesh_pave_fluo_hidden_session)
    val msgBlockUnknown = stringResource(R.string.wesh_pave_fluo_block_unknown)

    fun send() {
        if (viewModel.selectedPrivateChatPeer.value != privatePeer) { feedback = msgDestinationChanged; return }
        val capture = destination?.let(viewModel.weshDraftStore::capture)
        if (capture == null || capture.text != draft) { feedback = msgDraftChanged; return }
        if (publicOpen) {
            if (!prefs.publicDisclosureAccepted) {
                publicCapture = capture
                publicRequest = WeshMessagePolicy.PublicSendRequest(capture.text)
            } else viewModel.sendWeshPublic(capture.text) { accepted ->
                if (accepted) viewModel.weshDraftStore.clearIfUnchanged(capture) else feedback = msgPublicFailed
            }
        } else if (privatePeer != null) {
            viewModel.sendWeshPrivate(capture.text, privatePeer) { accepted ->
                if (accepted) viewModel.weshDraftStore.clearIfUnchanged(capture) else feedback = msgPrivateFailed
            }
        }
    }
    val onDraftChange = composerDraftEditCallback(viewModel.weshDraftStore, destination) { feedback = msgDraftTooLong }

    Box(Modifier.fillMaxSize()) {
        when (route) {
            PaveRoute.Home -> {
                val lastPublic = publicMessages.lastOrNull { isPublicText(it, myPeerID, viewModel::weshIsBlocked) }
                val lastPublicOwn = lastPublic?.senderPeerID == myPeerID
                HomeScreen(
                    state = PaveHomeState(
                        phonesInReach = peers.size,
                        bluetoothOn = bluetoothReady,
                        publicPreviewSender = if (lastPublicOwn) null else lastPublic?.sender,
                        publicPreviewText = lastPublic?.content,
                        publicTime = lastPublic?.let(::time),
                        contacts = contacts.map { contact ->
                            val summary = conversations.firstOrNull { it.conversationID == contact.conversationID }
                            PaveContactItem(
                                id = contact.conversationID,
                                nickname = contact.displayName,
                                fingerprint = PaveFingerprint.short(contact.key),
                                verified = true,
                                lastMessage = if (viewModel.weshIsBlocked(contact.conversationID)) stringResource(R.string.wesh_pave_fluo_contact_blocked)
                                    else summary?.latestMessagePreview?.takeIf { it.isNotBlank() },
                                time = null,
                            )
                        },
                        requestCount = requests.size,
                        contactStoreFailed = contactStoreFailed,
                        internetPill = paveHomeInternetPill(internet.available, internetEnabled, internetStatus),
                    ),
                    onOpenPublicRoom = { viewModel.endPrivateChat(); go(PaveRoute.Public) },
                    onOpenContact = { id -> openPrivate(id) },
                    onOpenRequests = { go(PaveRoute.Requests) },
                    onExchange = { go(PaveRoute.Exchange) },
                    onOpenSettings = { go(PaveRoute.Settings) },
                    onRetryContacts = viewModel::retryWeshContacts,
                    onInternetPill = {
                        when (internetStatus.pillAction()) {
                            PaveInternetPillAction.Retry -> internet.retry()
                            PaveInternetPillAction.OpenConnexion -> connexionOpen = true
                        }
                    },
                )
            }
            PaveRoute.Public -> {
                val rendered = publicMessages.filter { isPublicText(it, myPeerID, viewModel::weshIsBlocked) }
                PublicRoomScreen(
                    phonesInReach = peers.size,
                    messages = rendered
                        .map { m ->
                            val own = m.senderPeerID == myPeerID
                            PaveMessageItem(
                                id = m.id, text = m.content, own = own, time = time(m),
                                sender = if (own) null else m.sender,
                                senderFingerprint = if (own) null else peerFingerprint(m.senderPeerID),
                                delivery = if (own) paveDelivery(m.deliveryStatus, public = true) else null,
                            )
                        },
                    composer = composer,
                    onDraftChange = onDraftChange,
                    onSend = ::send,
                    onBack = { go(PaveRoute.Home) },
                    onMessageActions = { id -> messageAction = paveReportedPublicMessage(rendered, id, ::peerFingerprint) },
                )
            }
            PaveRoute.Private -> {
                val shown = privatePeer ?: opening
                val contact = contacts.firstOrNull { it.conversationID == shown }
                val title = contact?.displayName ?: conversations.firstOrNull { it.conversationID == shown }?.displayName.orEmpty()
                if (privatePeer != null && contactCardFor == privatePeer) ContactSettingsRoute(
                    viewModel, privatePeer, contact,
                    onBlock = { contactCardFor = null; confirmAction = "block" to privatePeer },
                    onRemove = { contactCardFor = null; confirmAction = "remove" to privatePeer },
                    onBack = { contactCardFor = null },
                ) else PrivateChatScreen(
                    nickname = title,
                    fingerprint = contact?.let { PaveFingerprint.short(it.key) },
                    verified = contact != null,
                    messages = privateChats[privatePeer].orEmpty().mapNotNull { m ->
                        val own = m.senderPeerID == myPeerID
                        // A QR contact's media is never stored or shown: one short line says it arrived.
                        val text = when {
                            m.type == BitchatMessageType.Message -> m.content
                            !own && contact != null -> mediaUnsupported
                            else -> return@mapNotNull null
                        }
                        PaveMessageItem(
                            id = m.id, text = text, own = own, time = time(m), sender = null, senderFingerprint = null,
                            delivery = if (own) paveDelivery(m.deliveryStatus, public = false) else null,
                            transport = PaveTransport.from(m.transport),
                            canRetry = own && canSendConversation &&
                                (m.deliveryStatus is DeliveryStatus.Failed || m.deliveryStatus is DeliveryStatus.Sending),
                        )
                    },
                    composer = composer,
                    onDraftChange = onDraftChange,
                    onSend = ::send,
                    onRetry = { id -> privatePeer?.let { viewModel.retryWeshPrivate(it, id) } },
                    onBack = { go(PaveRoute.Home) },
                    onOpenContact = { contactCardFor = privatePeer },
                    loading = privatePeer == null && opening != null,
                )
            }
            PaveRoute.Requests -> RequestsScreen(
                requests = requests.map { r ->
                    PaveRequestItem(
                        id = r.fingerprint ?: r.peerID,
                        nickname = r.nickname,
                        fingerprint = paveShortFingerprintFromHex(r.fingerprint),
                        pendingMessages = null,
                        verifiedNamesakeFingerprint = contacts.firstOrNull { weshNamesakes(it.nickname, r.nickname) || it.alias?.let { a -> weshNamesakes(a, r.nickname) } == true }?.let { PaveFingerprint.short(it.key) },
                    )
                },
                onBack = { go(PaveRoute.Home) },
                onExchange = { go(PaveRoute.Exchange) },
                onDismiss = { id -> requests.firstOrNull { (it.fingerprint ?: it.peerID) == id }?.let(viewModel::weshDismissRequest) },
                onBlock = { id -> requestToBlock = requests.firstOrNull { (it.fingerprint ?: it.peerID) == id } },
            )
            PaveRoute.Exchange -> ExchangeRoute(viewModel, nickname, onClose = { go(PaveRoute.Home) }, onOpenChat = { conversationID ->
                openPrivate(conversationID)
            })
            PaveRoute.Settings -> {
                var autoStartOnBoot by remember { mutableStateOf(MeshServicePreferences.isAutoStartEnabled()) }
                SettingsScreen(
                    summary = PaveSettingsSummary(
                        nickname = nickname,
                        previewsHidden = !prefs.notificationShowsSender,
                        meeting = when (meetingState) {
                            MeetingNoteState.Ready -> savedMeetingNote.takeIf { it.isNotBlank() }
                            MeetingNoteState.Unavailable -> stringResource(R.string.wesh_pave_fluo_meeting_unavailable)
                            MeetingNoteState.Unloaded, MeetingNoteState.Loading -> stringResource(R.string.wesh_pave_fluo_meeting_loading)
                            MeetingNoteState.Fenced -> null
                        },
                        blockedCount = if (revision >= 0) viewModel.weshBlockedFingerprints().size + sessionHidden.size else 0,
                        appearance = appearance,
                    ),
                    onErase = { go(PaveRoute.Erase) },
                    onOpen = { entry ->
                        if (entry == PaveSettingsEntry.Connexion) connexionOpen = true else go(paveRouteFor(entry))
                    },
                    autoStartOnBoot = autoStartOnBoot,
                    onAutoStartOnBoot = { enabled ->
                        MeshServicePreferences.setAutoStartEnabled(enabled)
                        autoStartOnBoot = enabled
                    },
                    onBack = { go(PaveRoute.Home) },
                )
            }
            PaveRoute.Profile -> ProfileRoute(viewModel, nickname, onBack = { go(PaveRoute.Settings) })
            PaveRoute.Discretion -> {
                var muted by remember(preferencesEpoch) { mutableStateOf(prefs.publicMuted) }
                var showSender by remember(preferencesEpoch) { mutableStateOf(prefs.notificationShowsSender) }
                var readReceipts by remember(preferencesEpoch) { mutableStateOf(prefs.readReceiptsEnabled) }
                DiscretionScreen(
                    publicMuted = muted, onPublicMuted = { muted = it; prefs.publicMuted = it },
                    showSender = showSender, onShowSender = { showSender = it; prefs.notificationShowsSender = it },
                    readReceipts = readReceipts, onReadReceipts = { readReceipts = it; prefs.readReceiptsEnabled = it },
                    onBack = { go(PaveRoute.Settings) },
                )
            }
            PaveRoute.Meeting -> MeetingRoute(viewModel, onBack = { go(PaveRoute.Settings) })
            PaveRoute.Blocked -> BlockedScreen(
                items = if (revision < 0) emptyList() else
                    viewModel.weshBlockedFingerprints().map { fp ->
                        PaveBlockedItem(fp, viewModel.weshContactName(fp).orEmpty(), paveShortFingerprintFromHex(fp), sessionOnly = false)
                    } + sessionHidden.map { peerID ->
                        PaveBlockedItem(peerID, peerNicknames[peerID].orEmpty(), peerFingerprint(peerID), sessionOnly = true)
                    },
                onUnblock = { item ->
                    if (item.sessionOnly) viewModel.weshUnhidePeer(item.id) else viewModel.weshUnblock(item.id)
                    revision++
                },
                onBack = { go(PaveRoute.Settings) },
            )
            PaveRoute.Appearance -> AppearanceScreen(appearance, onAppearance, onBack = { go(PaveRoute.Settings) })
            PaveRoute.About -> AboutScreen(BuildConfig.VERSION_NAME, onBack = { go(PaveRoute.Settings) }, onOpenLicenses = { go(PaveRoute.Licenses) })
            PaveRoute.Licenses -> LicensesScreen(onBack = { go(PaveRoute.About) })
            PaveRoute.Erase -> HoldToEraseScreen(onErase = { viewModel.panicClearAllData() }, onClose = { go(PaveRoute.Settings) })
        }
    }

    if (connexionOpen) {
        ConnexionSheet(PaveBluetoothInfo(bluetoothReady, peers.size), internet, onDismiss = { connexionOpen = false })
    }

    publicRequest?.let { request ->
        PaveDialog(
            title = stringResource(R.string.wesh_pave_fluo_public_confirm_title),
            body = stringResource(R.string.wesh_pave_fluo_public_confirm_body),
            confirm = stringResource(R.string.wesh_pave_send_public),
            onConfirm = {
                val capture = publicCapture
                val stillPublic = route == PaveRoute.Public && viewModel.selectedPrivateChatPeer.value == null
                if (capture != null && viewModel.weshDraftStore.isCurrent(capture) && request.canConfirm(
                        if (stillPublic) "public" else "other",
                        viewModel.weshDraftStore.drafts.value["public"].orEmpty(),
                        viewModel.weshBluetoothReady(),
                    )
                ) {
                    viewModel.sendWeshPublic(request.content) { accepted ->
                        if (accepted) {
                            prefs.publicDisclosureAccepted = true
                            viewModel.weshDraftStore.clearIfUnchanged(capture)
                        } else feedback = msgPublicFailed
                    }
                } else feedback = msgDestinationChanged
                publicRequest = null
            },
            onDismiss = { publicRequest = null },
        )
    }

    messageAction?.let { (peerID, target) ->
        PaveDialog(
            title = stringResource(R.string.wesh_pave_fluo_message_actions_title),
            body = stringResource(R.string.wesh_pave_fluo_report_body),
            confirm = stringResource(R.string.wesh_pave_fluo_report_action),
            onConfirm = {
                val (blocked, offer) = paveBlockThenOfferReport(target) { viewModel.weshBlockPublicSender(peerID) }
                feedback = if (blocked) msgReported else msgHiddenSession
                revision++
                messageAction = null
                reportOffer = offer
            },
            onDismiss = { messageAction = null },
        )
    }

    confirmAction?.let { (action, target) ->
        val remove = action == "remove"
        PaveDialog(
            title = stringResource(if (remove) R.string.wesh_pave_fluo_contact_remove else R.string.wesh_pave_fluo_contact_block),
            body = stringResource(if (remove) R.string.wesh_pave_fluo_contact_remove_body else R.string.wesh_pave_fluo_block_body),
            confirm = stringResource(if (remove) R.string.wesh_pave_fluo_contact_remove else R.string.wesh_pave_requests_block),
            onConfirm = {
                if (remove) {
                    viewModel.removeWeshContact(target)
                    if (privatePeer == target) go(PaveRoute.Home)
                } else {
                    val contact = contacts.firstOrNull { it.conversationID == target }
                    val reported = PaveReportTarget(contact?.nickname, contact?.let { PaveFingerprint.short(it.key) }, PaveReportSource.Contact)
                    val (blocked, offer) = paveBlockThenOfferReport(reported) { viewModel.weshBlock(target) }
                    if (!blocked) feedback = msgBlockUnknown
                    reportOffer = offer
                }
                revision++
                confirmAction = null
            },
            onDismiss = { confirmAction = null },
        )
    }

    requestToBlock?.let { request ->
        PaveDialog(
            title = stringResource(R.string.wesh_pave_requests_block),
            body = stringResource(R.string.wesh_pave_fluo_block_request_body),
            confirm = stringResource(R.string.wesh_pave_requests_block),
            onConfirm = {
                val (blocked, offer) = paveBlockThenOfferReport(paveReportTarget(request)) { viewModel.weshBlockRequest(request) }
                if (!blocked) feedback = msgBlockUnknown
                revision++
                requestToBlock = null
                reportOffer = offer
            },
            onDismiss = { requestToBlock = null },
        )
    }

    reportOffer?.let { target ->
        PaveReportSendDialog(target, onFeedback = { feedback = it }, onDismiss = { reportOffer = null })
    }
}

private fun isPublicText(m: BitchatMessage, myPeerID: String, isBlocked: (String) -> Boolean) =
    m.type == BitchatMessageType.Message && WeshMessagePolicy.showsInPublicRoom(m, myPeerID, isBlocked)

@Composable
private fun PaveDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = WeshType.headline) },
        text = { Text(body, style = WeshType.body, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = WeshType.label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wesh_pave_cancel), style = WeshType.label) } },
    )
}

/** Shown while the real erase runs; then the result screen. Nothing else is reachable meanwhile. */
@Composable
private fun ErasingScreen() {
    val c = WeshPave.colors
    Column(
        Modifier.fillMaxSize().background(c.bg).paveGrain(c.isNight).verticalScroll(rememberScrollState()).padding(WeshSpace.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.wesh_pave_fluo_erasing).uppercase(Locale.ROOT),
            style = WeshType.display,
            color = c.ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Échanger, wired to the QR verification flow of [ChatViewModel]. */
@Composable
private fun ExchangeRoute(viewModel: ChatViewModel, nickname: String, onClose: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    var qr by remember { mutableStateOf<String?>(null) }
    var renewsIn by remember { mutableIntStateOf(5) }
    var pending by remember { mutableStateOf<VerificationService.VerificationQR?>(null) }
    var verified by remember { mutableStateOf(false) }
    var theyScanned by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val openedAt = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val incoming by viewModel.weshIncomingQR.collectAsState()
    val myFingerprint = remember { paveShortFingerprintFromHex(runCatching { viewModel.meshServiceFacade.getIdentityFingerprint() }.getOrNull()) }
    val msgInvalid = stringResource(R.string.wesh_pave_fluo_qr_invalid)
    val msgNotFound = stringResource(R.string.wesh_pave_fluo_qr_not_found)
    val msgExpired = stringResource(R.string.wesh_pave_fluo_qr_expired)
    val msgPending = stringResource(R.string.wesh_pave_fluo_qr_pending)

    LaunchedEffect(nickname) {
        // Rebuild every minute, and at once when an erase fence changes whether my npub is shown.
        var shownActive: Boolean? = null
        var builtAt = 0L
        while (true) {
            val active = com.bitchat.android.services.WeshQrIdentity.showsNpub()
            val now = System.currentTimeMillis()
            if (active != shownActive || now - builtAt >= 60_000) {
                qr = com.bitchat.android.services.WeshQrIdentity.myQR(context, nickname)
                shownActive = active
                builtAt = now
            }
            renewsIn = (5 - ((now - builtAt) / 60_000).toInt()).coerceIn(1, 5)
            theyScanned = viewModel.weshScannedMeSince(openedAt.longValue, pending?.noiseKeyHex)
            delay(1_000)
        }
    }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        while (true) {
            when (viewModel.weshVerificationProgress(p.noiseKeyHex)) {
                "verified" -> { verified = true; status = null; return@LaunchedEffect }
                "pending" -> status = msgPending
                "expired" -> { status = msgExpired; return@LaunchedEffect }
                else -> { status = msgNotFound; return@LaunchedEffect }
            }
            delay(250)
        }
    }
    fun scan(raw: String) {
        if (verified) return
        val parsed = VerificationService.verifyScannedQR(raw)
        when {
            parsed == null -> status = msgInvalid
            viewModel.beginQRVerification(parsed) -> { pending = parsed; status = msgPending }
            else -> status = msgNotFound
        }
    }

    Column(Modifier.fillMaxSize().background(WeshPave.colors.bg)) {
        ExchangeScreen(
            myQrPayload = qr,
            myNickname = nickname,
            myFingerprint = myFingerprint,
            renewsInMinutes = renewsIn,
            progress = ExchangeProgress(theyScannedMe = theyScanned, iScannedThem = verified),
            contactNickname = pending?.nickname,
            contactFingerprint = pending?.noiseKeyHex?.dataFromHexString()?.let(PaveFingerprint::short),
            onScanned = ::scan,
            onCompleted = {
                val key = pending?.noiseKeyHex?.dataFromHexString()
                if (key != null) onOpenChat(ContactIdentityResolver.contactConversationIdForNoiseKey(key)) else onClose()
            },
            onClose = onClose,
            modifier = Modifier.weight(1f),
        )
        status?.let {
            Text(
                it,
                style = WeshType.body,
                color = WeshPave.colors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WeshPave.colors.surfaceRaised)
                    .navigationBarsPadding()
                    .padding(WeshSpace.gutter)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    incoming?.let { raw ->
        PaveDialog(
            title = stringResource(R.string.wesh_pave_fluo_link_title),
            body = stringResource(R.string.wesh_pave_fluo_link_body),
            confirm = stringResource(R.string.wesh_pave_fluo_link_confirm),
            onConfirm = { viewModel.weshIncomingQR.value = null; scan(raw) },
            onDismiss = { viewModel.weshIncomingQR.value = null },
        )
    }
}

@Composable
private fun ProfileRoute(viewModel: ChatViewModel, nickname: String, onBack: () -> Unit) {
    val epoch = viewModel.weshPreferences.resetEpoch.collectAsState().value
    var edited by rememberSaveable(nickname) { mutableStateOf(nickname) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val myFingerprint = remember { paveShortFingerprintFromHex(runCatching { viewModel.meshServiceFacade.getIdentityFingerprint() }.getOrNull()) }
    val saved = stringResource(R.string.wesh_pave_fluo_nickname_saved)
    val invalid = stringResource(R.string.wesh_pave_fluo_nickname_invalid)
    ProfileScreen(
        nickname = edited,
        onNicknameChange = { edited = NicknameGenerator.clean(it) },
        myFingerprint = myFingerprint,
        onSave = {
            val clean = edited.trim()
            if (NicknameGenerator.isValid(clean)) {
                if (viewModel.setNickname(clean, epoch)) {
                    feedback = saved; error = false
                }
            } else { feedback = invalid; error = true }
        },
        feedback = feedback,
        feedbackIsError = error,
        onBack = onBack,
    )
}

/** Ce contact: edits the local name and memo, which [ChatViewModel.setWeshContactNote] bounds and stores. */
@Composable
private fun ContactSettingsRoute(
    viewModel: ChatViewModel,
    conversationID: String,
    contact: WeshContactStore.Contact?,
    onBlock: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var alias by remember(conversationID) { mutableStateOf(contact?.alias.orEmpty()) }
    var memo by remember(conversationID) { mutableStateOf(contact?.memo.orEmpty()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.wesh_pave_fluo_contact_note_saved)
    val failed = stringResource(R.string.wesh_pave_fluo_contact_note_failed)
    val refused = stringResource(R.string.wesh_pave_fluo_contact_alias_refused)
    // Every surface shows the name through the remote rule: refuse one it would only show as unnamed.
    val cleanAlias = WeshContactStore.cleanAlias(alias)
    val unshowable = cleanAlias != null && remoteDisplayName(cleanAlias) in
        setOf(stringResource(R.string.wesh_pave_unnamed), stringResource(R.string.wesh_pave_unnamed_reserved))
    ContactSettingsScreen(
        qrName = contact?.nickname,
        alias = alias,
        onAliasChange = { alias = NicknameGenerator.clean(it); feedback = null },
        memo = memo,
        onMemoChange = { memo = WeshContactStore.codePointPrefix(it, WeshContactStore.MAX_MEMO_LENGTH); feedback = null },
        saving = saving,
        onSave = {
            if (saving) return@ContactSettingsScreen
            if (unshowable) {
                feedback = refused
                error = true
                return@ContactSettingsScreen
            }
            val requestedAlias = alias
            val requestedMemo = memo
            saving = true
            scope.launch {
                try {
                    val stored = viewModel.setWeshContactNote(conversationID, requestedAlias, requestedMemo)
                    if (stored) {
                        alias = WeshContactStore.cleanAlias(requestedAlias).orEmpty()
                        memo = WeshContactStore.cleanMemo(requestedMemo).orEmpty()
                    }
                    feedback = if (stored) saved else failed
                    error = !stored
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    feedback = failed
                    error = true
                } finally {
                    if (currentCoroutineContext().isActive) saving = false
                }
            }
        },
        feedback = feedback,
        feedbackIsError = error,
        onBlock = onBlock,
        onRemove = onRemove,
        onBack = onBack,
    )
}

@Composable
private fun MeetingRoute(viewModel: ChatViewModel, onBack: () -> Unit) {
    val prefs = viewModel.weshPreferences
    val epoch = prefs.resetEpoch.collectAsState().value
    val note by prefs.meetingDraft.collectAsState()
    val loadState by prefs.meetingNoteState.collectAsState()
    // Re-entering the screen restarts a load cancelled with the previous route.
    LaunchedEffect(prefs, epoch) { prefs.loadMeetingNote(epoch) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var feedback by remember(epoch) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.wesh_pave_fluo_meeting_saved)
    val unconfirmed = stringResource(R.string.wesh_pave_fluo_meeting_unconfirmed)
    MeetingNoteScreen(
        ready = loadState == MeetingNoteState.Ready,
        loading = loadState == MeetingNoteState.Unloaded || loadState == MeetingNoteState.Loading,
        onRetry = if (loadState == MeetingNoteState.Unavailable) ({
            scope.launch { prefs.loadMeetingNote(epoch) }
            Unit
        }) else null,
        note = note,
        onNoteChange = meetingNoteEditCallback(prefs, epoch) { feedback = null },
        saving = saving,
        onSave = {
            saving = true
            feedback = null
            scope.launch {
                try {
                    val confirmed = prefs.saveMeetingDraft(epoch)
                    if (prefs.resetEpoch.value == epoch) {
                        feedback = if (confirmed) saved else unconfirmed
                        error = !confirmed
                    }
                } finally { saving = false }
            }
        },
        feedback = feedback,
        feedbackIsError = error,
        onBack = onBack,
    )
}
