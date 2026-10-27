package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.protocol.BitchatPacket


import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.Date
import com.bitchat.android.services.VerificationService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.hexEncodedString

private data class ConversationLiveIdentityState(
    val connectedPeerIDs: List<String>,
    val peerNicknames: Map<String, String>,
    val persistedDisplayNames: Map<String, String>
)

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    initialMeshService: BluetoothMeshService,
    initialUnifiedMeshService: MeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

    val meshService: BluetoothMeshService = initialMeshService
    private val unifiedMeshService: MeshService = initialUnifiedMeshService
    private val mesh: MeshService
        get() = unifiedMeshService
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val CONVERSATION_DISCONNECT_GRACE_MS = 3_000L

        /** Internet-received messages are shown on Main, where [notifications] keeps its lists. */
        internal fun internetNotifierOnMain(
            scope: kotlinx.coroutines.CoroutineScope,
            notifications: NotificationManager,
            onShown: (BitchatMessage) -> Unit = {}
        ) =
            com.bitchat.android.services.WeshInternetReceiver.postOnMain(scope) { conversationID, message ->
                notifications.showPrivateMessageNotification(conversationID, message.sender.takeIf { it.isNotBlank() },
                    NotificationTextUtils.buildPrivateMessagePreview(message))
                onShown(message)
            }
    }

    fun getCurrentNpub(): String? {
        return try {
            NostrIdentityBridge
                .getCurrentNostrIdentity(getApplication())
                ?.npub
        } catch (_: Exception) {
            null
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String {
        return VerificationService.buildMyQRString(nickname, npub) ?: ""
    }

    // MARK: - State management
    private val state = ChatState(
        scope = viewModelScope,
    )

    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    internal val weshPreferences by lazy { WeshPreferences.getInstance(application.applicationContext) }
    private val identityManager by lazy { SecureIdentityStateManager(getApplication()) }
    private val seenMessageStore by lazy {
        com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
    }
    private val conversationListPreferences =
        com.bitchat.android.services.ConversationListPreferences.getInstance(getApplication())
    private val messageManager = MessageManager(state)

    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = hasEstablishedSessionOnAnyLocalTransport(peerID)
        override fun initiateHandshake(peerID: String) = initiateNoiseHandshakeOnBestLocalTransport(peerID)
        override fun getMyPeerID(): String = mesh.myPeerID
    }

    val privateChatManager = PrivateChatManager(
        state,
        messageManager,
        dataManager,
        noiseSessionDelegate,
        hasReadReceiptBeenSent = { messageID ->
            seenMessageStore.hasReadReceiptBeenSent(messageID)
        },
        markMessageReadLocally = { messageID, seenToken ->
            seenMessageStore.markReadLocally(messageID, seenToken)
        },
        sendInternetReadReceipt = ::sendWeshInternetReadReceipt,
        readReceiptsEnabled = { weshPreferences.readReceiptsEnabled }
    )
    private val notificationManager = NotificationManager(
      application.applicationContext,
      NotificationManagerCompat.from(application.applicationContext)
    )

    /** Internet-received private messages notify like Bluetooth ones while this UI is attached. */
    private val weshInternetNotifier = internetNotifierOnMain(viewModelScope, notificationManager) { message ->
        meshDelegateHandler.readInternetMessageIfFocused(message)
    }

    private val verificationHandler = VerificationHandler(
        context = application.applicationContext,
        scope = viewModelScope,
        getMeshService = { mesh },
        identityManager = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager
    )
    val verifiedFingerprints = verificationHandler.verifiedFingerprints
    private val weshContactStore = com.bitchat.android.services.WeshContactStore.get(application)
    val weshContacts = weshContactStore.contacts
    val weshContactStoreFailed = weshContactStore.loadFailed
    /** Unverified private senders: nickname and identity only, never their message text. */
    val weshRequests: StateFlow<List<com.bitchat.android.services.WeshMessageRequests.Request>> =
        combine(com.bitchat.android.services.WeshMessageRequests.requests, weshContactStore.contacts) { requests, contacts ->
            val enrolled = contacts.map { ContactIdentityResolver.fingerprintHex(it.key) }.toSet()
            requests.filter { it.fingerprint == null || it.fingerprint !in enrolled }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    internal val weshDraftStore = WeshDraftStore.get(application)
    val weshContactSheet = kotlinx.coroutines.flow.MutableStateFlow(false)
    val weshIncomingQR = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val weshPrivateSender = com.bitchat.android.services.WeshPrivateSender(weshContactStore)
    private val weshPrivateCompose = com.bitchat.android.services.WeshPrivateCompose(weshPrivateSender) {
        com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
    }

    // Delegate handler for mesh callbacks
    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMeshService = { mesh },
        publicNotificationsMuted = { weshPreferences.publicMuted },
        markMessageReadLocally = { messageID, seenToken ->
            seenMessageStore.markReadLocally(messageID, seenToken)
        },
        sendInternetReadReceipt = ::sendWeshInternetReadReceipt,
        readReceiptsEnabled = { weshPreferences.readReceiptsEnabled }
    )

    private fun sendWeshInternetReadReceipt(
        messageID: String,
        conversationID: String,
        expectedToken: com.bitchat.android.services.SeenReceiptAdmission.Token?
    ) {
        com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
            .sendWeshInternetReadReceipt(messageID, conversationID, expectedToken)
    }





    val messages: StateFlow<List<BitchatMessage>> = state.messages
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val nickname: StateFlow<String> = state.nickname
    val isConnected: StateFlow<Boolean> = state.isConnected
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    internal val conversationStoreState =
        com.bitchat.android.services.AppStateStore.conversationStoreState
    private val conversationPresencePeers = MutableStateFlow<List<String>>(emptyList())
    private val conversationPresenceRemovalJobs = mutableMapOf<String, Job>()
    private val conversationDirectoryRevision = MutableStateFlow(0L)
    private var favoriteRelationshipListenerRegistered = false
    private val favoriteRelationshipChangeListener = object : FavoritesChangeListener {
        override fun onFavoriteChanged(noiseKeyHex: String) {
            refreshConversationDirectoryState()
        }

        override fun onAllCleared() {
            refreshConversationDirectoryState()
        }
    }

    private fun refreshConversationDirectoryState() {
        viewModelScope.launch {
            refreshPeerFavoritedUs()
            conversationListPreferences.canonicalizeAliases()
            conversationDirectoryRevision.update { it + 1L }
        }
    }

    private val conversationLiveIdentityState = combine(
        conversationPresencePeers,
        state.peerNicknames,
        state.peerFingerprints,
        conversationDirectoryRevision,
        com.bitchat.android.services.AppStateStore.privateConversationDisplayNames
    ) { connectedPeerIDs, peerNicknames, _, _, persistedDisplayNames ->
        ConversationLiveIdentityState(
            connectedPeerIDs = connectedPeerIDs,
            peerNicknames = peerNicknames,
            persistedDisplayNames = persistedDisplayNames
                .mapKeys { (conversationID, _) -> conversationID.lowercase() }
        )
    }
    private val baseConversations = combine(
        state.unreadPrivateMessages,
        state.privateChats,
        state.nickname,
        conversationLiveIdentityState,
        com.bitchat.android.services.AppStateStore.unreadPrivateMessageCounts
    ) { unreadConversationIDs, chats, currentNickname, liveIdentity, unreadCounts ->
        val seenStore = seenMessageStore
        val connectedPeerByIdentity = buildMap {
            liveIdentity.connectedPeerIDs.forEach { peerID ->
                val identities = runCatching {
                    ContactDirectory.aliasesForConversation(peerID) +
                        ContactDirectory.canonicalConversationId(peerID)
                }.getOrDefault(setOf(peerID))
                identities.forEach { identity ->
                    putIfAbsent(identity.lowercase(), peerID)
                }
            }
        }
        buildConversationSummaries(
            unreadConversationIDs = unreadConversationIDs,
            privateChats = chats,
            currentUserIdentifiers = setOf(currentNickname, mesh.myPeerID),
            canonicalize = ContactDirectory::canonicalConversationId,
            isMessageRead = { message ->
                com.bitchat.android.services.AppStateStore.isPrivateMessageRead(message.id) ||
                    seenStore.hasBeenReadLocally(message.id)
            },
            persistedUnreadCounts = unreadCounts
        ).map { summary ->
            val resolution = ContactDirectory.resolve(summary.conversationID)
            val resolvedNostrPubkey = summary.nostrPubkey
                ?: resolution.nostrPubkey?.let(ContactIdentityResolver::nostrPubkeyHex)
            val aliases = buildSet {
                addAll(summary.identityAliases)
                add(summary.conversationID)
                add(resolution.conversationID)
                resolution.meshPeerID?.let(::add)
                resolution.noiseKeyHex?.let(::add)
                resolvedNostrPubkey
                    ?.let(ContactIdentityResolver::nostrAliasForPubkey)
                    ?.let(::add)
            }.mapTo(mutableSetOf()) { it.lowercase() }
            val connectedPeerID = aliases
                .asSequence()
                .mapNotNull(connectedPeerByIdentity::get)
                .firstOrNull()
            val persistedDisplayName = liveIdentity.persistedDisplayNames[
                summary.conversationID.lowercase()
            ] ?: aliases
                .asSequence()
                .mapNotNull(liveIdentity.persistedDisplayNames::get)
                .firstOrNull()

            summary.copy(
                displayName = resolveConversationDisplayName(
                    fallbackName = summary.displayName,
                    connectedPeerID = connectedPeerID,
                    peerNicknames = liveIdentity.peerNicknames,
                    resolvedContactName = resolution.displayName,
                    persistedDisplayName = persistedDisplayName
                ),
                nostrPubkey = resolvedNostrPubkey,
                transport = if (resolvedNostrPubkey != null) {
                    DirectMessageTransport.NOSTR
                } else {
                    summary.transport
                },
                identityAliases = aliases,
                isConnected = connectedPeerID != null,
                connectedPeerID = connectedPeerID
            )
        }
    }

    internal val conversations: StateFlow<List<ConversationSummary>> = combine(
        baseConversations,
        conversationListPreferences.pinned,
        conversationListPreferences.muted,
        conversationListPreferences.drafts
    ) { summaries, pinned, muted, drafts ->
        sortConversationSummaries(
            summaries.map { summary ->
                val key = summary.conversationID.lowercase()
                summary.copy(
                    isPinned = key in pinned,
                    isMuted = key in muted,
                    draft = drafts[key]
                )
            }
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerFavoritedUs: StateFlow<Set<String>> = state.peerFavoritedUs
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: StateFlow<Boolean> = state.showAppInfo
    val showMeshPeerList: StateFlow<Boolean> = state.showMeshPeerList
    val privateChatSheetPeer: StateFlow<String?> = state.privateChatSheetPeer
    val showVerificationSheet: StateFlow<Boolean> = state.showVerificationSheet
    val showSecurityVerificationSheet: StateFlow<Boolean> = state.showSecurityVerificationSheet
    val meshServiceFacade: MeshService
        get() = mesh
    val myPeerID: String
        get() = mesh.myPeerID

    fun getMeshPeerFingerprint(peerID: String): String? = mesh.getPeerFingerprint(peerID)

    fun getMeshPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? = mesh.getPeerInfo(peerID)

    fun initiateMeshHandshake(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    internal fun weshDraftDestination(privateConversation: String?): String? =
        privateConversation?.let(weshContactStore::contact)?.noiseHex?.let(WeshDraftStore::privateDestination)

    internal fun retryWeshDrafts() = weshDraftStore.retry()

    private suspend fun loadWeshDrafts() {
        if (!com.bitchat.android.BuildConfig.WESH_POLICY) return
        com.bitchat.android.startup.WeshRuntime.get(getApplication()).awaitReady()
        val key = mesh.getStaticNoisePublicKey()?.takeIf { it.size == 32 } ?: return
        weshDraftStore.load(key.hexEncodedString())
    }

    init {
        viewModelScope.launch {
            if (com.bitchat.android.BuildConfig.WESH_POLICY) weshPreferences.loadMeetingNote()
        }
        viewModelScope.launch { loadWeshDrafts() }
        observeConversationPresenceWithDisconnectGrace()
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        ContactDirectory.initialize(getApplication()) { mesh }
        com.bitchat.android.services.AppStateStore.canonicalizePrivateChats()
        observeConversationDisplayNames()
        // Application startup performs the initial restore. Repeat it for every new UI owner
        // because a quick reopen can reuse a process whose in-memory state was cleared during
        // controlled shutdown.
        com.bitchat.android.services.AppStateStore.reloadConversationPersistence(
            getApplication()
        )
        com.bitchat.android.services.WeshInternetReceiver.uiNotifier = weshInternetNotifier
        // Mark queued private messages as failed when the router gives up on them
        try {
            com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh).onMessageExpired = { conversationID, messageID ->
                com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                    conversationID,
                    messageID,
                    com.bitchat.android.model.DeliveryStatus.Failed("Message expired before delivery")
                )
            }
            // The outbox is memory only: after a relaunch, my waiting messages are queued again.
            com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh).restoreWeshOutboxOnce()
        } catch (_: Exception) { }
        // Hydrate UI state from process-wide AppStateStore to survive Activity recreation
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.peers.collect { peers ->
                state.setConnectedPeers(peers)
                state.setIsConnected(peers.isNotEmpty())
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.publicMessages.collect { msgs ->
                // Source of truth is AppStateStore; replace to avoid duplicate keys in LazyColumn
                state.setMessages(msgs)
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try {
                combine(
                    com.bitchat.android.services.AppStateStore.privateMessages,
                    com.bitchat.android.services.AppStateStore.unreadPrivateMessageCounts
                ) { byPeer, unreadCounts -> byPeer to unreadCounts }
                    .collect { (byPeer, unreadCounts) ->
                val (canonicalChats, unreadConversationIDs) = withContext(Dispatchers.IO) {
                    val canonical = ContactDirectory.canonicalizePrivateChats(byPeer)
                    val unread = try {
                        val myNick = state.getNicknameValue()
                        canonical
                            .filterValues { messages ->
                                messages.any { message ->
                                    !message.isFromSelf(myNick, mesh.myPeerID) &&
                                        message.sender != "system" &&
                                        !com.bitchat.android.services.AppStateStore
                                            .isPrivateMessageRead(message.id) &&
                                        !seenMessageStore.hasBeenReadLocally(message.id)
                                }
                            }
                            .keys + unreadCounts
                                .filterValues { it > 0 }
                                .keys
                    } catch (_: Exception) {
                        state.getUnreadPrivateMessagesValue()
                    }
                    canonical to unread
                }
                state.setPrivateChats(canonicalChats)
                // Recompute unread set using SeenMessageStore for robustness across Activity recreation
                state.setUnreadPrivateMessages(unreadConversationIDs)
            } } catch (_: Exception) { }
        }
    }

    /**
     * Mesh discovery can briefly drop a peer while transports hand over. Preserve its online
     * treatment for a short grace window to keep conversation rows from jumping between sections.
     * New connections still appear immediately.
     */
    private fun observeConversationPresenceWithDisconnectGrace() {
        viewModelScope.launch {
            state.connectedPeers.collect { connected ->
                val current = connected.toSet()
                current.forEach { peerID ->
                    conversationPresenceRemovalJobs.remove(peerID)?.cancel()
                }

                val displayed = conversationPresencePeers.value.toMutableList()
                connected.forEach { peerID ->
                    if (peerID !in displayed) displayed.add(peerID)
                }
                if (displayed != conversationPresencePeers.value) {
                    conversationPresencePeers.value = displayed
                }

                (displayed.toSet() - current).forEach { peerID ->
                    if (peerID in conversationPresenceRemovalJobs) return@forEach
                    conversationPresenceRemovalJobs[peerID] = launch {
                        delay(CONVERSATION_DISCONNECT_GRACE_MS)
                        if (peerID !in state.connectedPeers.value) {
                            conversationPresencePeers.value =
                                conversationPresencePeers.value - peerID
                        }
                        conversationPresenceRemovalJobs.remove(peerID)
                    }
                }
            }
        }
    }

    private fun observeConversationDisplayNames() {
        viewModelScope.launch {
            combine(
                state.peerNicknames,
                state.connectedPeers,
                state.peerFingerprints
            ) { peerNicknames, connectedPeers, _ ->
                connectedPeers.mapNotNull { peerID ->
                    peerNicknames[peerID]?.let { peerID to it }
                }.toMap()
            }.collect { connectedNames ->
                conversationListPreferences.canonicalizeAliases()
                com.bitchat.android.services.AppStateStore
                    .updatePrivateConversationDisplayNames(connectedNames)
            }
        }
    }

    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)
        
        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()

        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        viewModelScope.launch {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {
                    // Append only latest debug message as system message to avoid flooding
                    msgs.lastOrNull()?.let { dm ->
                        messageManager.addSystemMessage(dm.content)
                    }
                }
            }
        }

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())

        // Reflect "they favorited us" changes into reactive UI state (drives star celebrations)
        refreshPeerFavoritedUs()
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared.addListener(
                favoriteRelationshipChangeListener
            )
            favoriteRelationshipListenerRegistered = true
        } catch (_: Exception) { }

        // Load verified fingerprints from secure storage
        verificationHandler.loadVerifiedFingerprints()


        // Ensure NostrTransport knows our mesh peer ID for embedded packets. Opens nothing: every
        // relay socket and event still waits for the runtime Internet opt-in.
        try {
            val nostrTransport = com.bitchat.android.nostr.NostrTransport.getInstance(getApplication())
            nostrTransport.senderPeerID = mesh.myPeerID
        } catch (_: Exception) { }

        // Note: Mesh service is now started by MainActivity

        // BLE receives are inserted by MessageHandler path.
    }
    
    override fun onCleared() {
        if (com.bitchat.android.services.WeshInternetReceiver.uiNotifier === weshInternetNotifier) {
            com.bitchat.android.services.WeshInternetReceiver.uiNotifier = null
        }
        if (favoriteRelationshipListenerRegistered) {
            runCatching {
                FavoritesPersistenceService.shared.removeListener(
                    favoriteRelationshipChangeListener
                )
            }
            favoriteRelationshipListenerRegistered = false
        }
        com.bitchat.android.services.AppStateStore.setSelectedPrivateChatPeer(null)
        // Note: Mesh service lifecycle is now managed by MainActivity
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String, expectedEpoch: Long): Boolean =
        weshPreferences.withCurrentEpoch(expectedEpoch) {
            state.setNickname(newNickname)
            dataManager.saveNickname(newNickname)
            mesh.sendBroadcastAnnounce()
        }
    
    // MARK: - Private Chat Management (delegated)
    
    internal suspend fun startPrivateChat(
        peerID: String,
        expectedSeenToken: com.bitchat.android.services.SeenReceiptAdmission.Token?
    ) {
        // Wesh never opens a private conversation with an identity that was not enrolled by QR.
        if (com.bitchat.android.BuildConfig.WESH_POLICY &&
            weshContactStore.contact(ContactDirectory.canonicalConversationId(peerID)) == null) return

        val (conversationID, success) = withContext(Dispatchers.IO) {
            val canonicalID = ContactDirectory.canonicalConversationId(peerID)
            com.bitchat.android.services.AppStateStore
                .loadPrivateConversationHistory(canonicalID)
            state.setPrivateChats(
                ContactDirectory.canonicalizePrivateChats(
                    com.bitchat.android.services.AppStateStore.privateMessages.value
                )
            )
            val unreadAliases = matchingUnreadAliases(
                unreadConversationIDs = state.getUnreadPrivateMessagesValue(),
                canonicalConversationID = canonicalID,
                canonicalize = ContactDirectory::canonicalConversationId
            )
            canonicalID to privateChatManager.startPrivateChat(
                peerID = canonicalID,
                meshService = mesh,
                expectedSeenToken = expectedSeenToken,
                unreadAliases = unreadAliases
            )
        }
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(conversationID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(conversationID)
        }
    }
    
    fun endPrivateChat() {
        val conversationID = state.getSelectedPrivateChatPeerValue()
        privateChatManager.endPrivateChat()
        if (conversationID != null) {
            com.bitchat.android.services.AppStateStore
                .releasePrivateConversationHistory(conversationID)
            state.setPrivateChats(
                ContactDirectory.canonicalizePrivateChats(
                    com.bitchat.android.services.AppStateStore.privateMessages.value
                )
            )
        }
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        clearMeshMentionNotifications()
        // Ensure sheet is hidden
        hidePrivateChatSheet()
    }

    internal suspend fun deletePrivateConversation(
        peerOrConversationID: String
    ): com.bitchat.android.services.DeletedPrivateConversation? {
        val seenToken = com.bitchat.android.services.SeenMessageStore.captureReceiptToken()
        val generation = com.bitchat.android.services.AppStateStore
            .capturePrivateConversationGeneration() ?: return null
        val canonicalID = ContactDirectory.canonicalConversationId(peerOrConversationID)
        val wasPinned = conversationListPreferences.isPinned(canonicalID)
        val wasMuted = conversationListPreferences.isMuted(canonicalID)
        val draft = conversationListPreferences.draftFor(canonicalID)
        val unreadAliases = matchingUnreadAliases(
            unreadConversationIDs = state.getUnreadPrivateMessagesValue(),
            canonicalConversationID = canonicalID,
            canonicalize = ContactDirectory::canonicalConversationId
        )
        // Before the delete: nothing queued or in flight keeps leaving, and the snapshot an undo
        // restores already says « Échec » with Réessayer instead of a false « En attente ».
        com.bitchat.android.services.MessageRouter.tryGetInstance()?.cancelWeshConversation(canonicalID)
        val deletion = withContext(Dispatchers.IO) {
            com.bitchat.android.services.AppStateStore
                .deletePrivateConversationAndWait(canonicalID, expectedGeneration = generation)
        }?.copy(
            wasPinned = wasPinned,
            wasMuted = wasMuted,
            draft = draft
        ) ?: return null
        if (deletion.generation != generation ||
            !com.bitchat.android.services.AppStateStore.isCurrentPrivateConversationGeneration(generation)
        ) return null
        conversationListPreferences.removeConversation(canonicalID)

        state.setPrivateChats(
            ContactDirectory.canonicalizePrivateChats(
                com.bitchat.android.services.AppStateStore.privateMessages.value
            )
        )
        state.setUnreadPrivateMessages(
            state.getUnreadPrivateMessagesValue() - unreadAliases
        )
        seenMessageStore.remove(deletion.messageIDs, seenToken)

        val selected = state.getSelectedPrivateChatPeerValue()
        if (
            selected != null &&
            ContactDirectory.canonicalConversationId(selected)
                .equals(canonicalID, ignoreCase = true)
        ) {
            privateChatManager.endPrivateChat()
            setCurrentPrivateChatPeer(null)
        }
        val sheetPeer = state.getPrivateChatSheetPeerValue()
        if (
            sheetPeer != null &&
            ContactDirectory.canonicalConversationId(sheetPeer)
                .equals(canonicalID, ignoreCase = true)
        ) {
            hidePrivateChatSheet()
        }
        clearNotificationsForSender(canonicalID)
        notificationManager.removeConversationShortcut(canonicalID)
        return deletion
    }

    internal suspend fun restoreDeletedConversation(
        deletion: com.bitchat.android.services.DeletedPrivateConversation
    ): Boolean {
        if (!com.bitchat.android.services.AppStateStore
                .isCurrentPrivateConversationGeneration(deletion.generation)
        ) return false
        val restored = withContext(Dispatchers.IO) {
            com.bitchat.android.services.AppStateStore
                .restoreDeletedConversation(deletion)
        }
        if (!restored || !com.bitchat.android.services.AppStateStore
                .isCurrentPrivateConversationGeneration(deletion.generation)
        ) return false
        if (deletion.wasPinned != conversationListPreferences.isPinned(deletion.conversationID)) {
            conversationListPreferences.togglePinned(deletion.conversationID)
        }
        if (deletion.wasMuted != conversationListPreferences.isMuted(deletion.conversationID)) {
            conversationListPreferences.toggleMuted(deletion.conversationID)
        }
        deletion.draft?.let {
            conversationListPreferences.setDraft(deletion.conversationID, it)
        }
        state.setPrivateChats(
            ContactDirectory.canonicalizePrivateChats(
                com.bitchat.android.services.AppStateStore.privateMessages.value
            )
        )
        if (deletion.unreadMessageCount > 0) {
            state.setUnreadPrivateMessages(
                state.getUnreadPrivateMessagesValue() + deletion.conversationID
            )
        }
        return true
    }

    internal suspend fun setConversationRead(
        conversationID: String,
        isRead: Boolean
    ): Boolean {
        val generation = com.bitchat.android.services.AppStateStore
            .capturePrivateConversationGeneration() ?: return false
        val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
        val updated = withContext(Dispatchers.IO) {
            com.bitchat.android.services.AppStateStore
                .setPrivateConversationRead(canonicalID, isRead, expectedGeneration = generation)
        }
        if (!updated || !com.bitchat.android.services.AppStateStore
                .isCurrentPrivateConversationGeneration(generation)
        ) return false
        state.setUnreadPrivateMessages(
            if (isRead) {
                state.getUnreadPrivateMessagesValue().filterNotTo(mutableSetOf()) {
                    ContactDirectory.canonicalConversationId(it)
                        .equals(canonicalID, ignoreCase = true)
                }
            } else {
                state.getUnreadPrivateMessagesValue() + canonicalID
            }
        )
        return true
    }

    internal fun toggleConversationPinned(conversationID: String) {
        conversationListPreferences.togglePinned(conversationID)
    }

    internal fun toggleConversationMuted(conversationID: String) {
        conversationListPreferences.toggleMuted(conversationID)
    }

    internal fun conversationDraft(conversationID: String?): String =
        conversationID
            ?.let(ContactDirectory::canonicalConversationId)
            ?.lowercase()
            ?.let(conversationListPreferences.drafts.value::get)
            .orEmpty()

    internal fun setConversationDraft(conversationID: String?, text: String) {
        if (conversationID.isNullOrBlank()) return
        conversationListPreferences.setDraft(conversationID, text)
    }

    // MARK: - Open Latest Unread Private Chat

    fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: mesh.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for Nostr DMs
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = targetKey,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.NostrAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            showPrivateChatSheet(openPeer)
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    // END - Open Latest Unread Private Chat

    
    // MARK: - Message Sending

    /** Wesh public text is sent verbatim to the local mesh: no commands, no named channels, no internet relay. */
    internal val weshPublicSending = MutableStateFlow(false)

    fun sendWeshPublic(content: String, onAccepted: (Boolean) -> Unit) {
        val transport = meshService
        val authorized = transport.weshPublicSendGuard {
            !weshPanicInProgress.value && com.bitchat.android.startup.WeshRuntime.transportAllowed &&
                meshService === transport && weshBluetoothReady()
        }
        if (!authorized() || !weshPublicSending.compareAndSet(false, true)) { onAccepted(false); return }
        viewModelScope.launch {
            try {
                val accepted = WeshMessagePolicy.sendPublicText(content, state.getNicknameValue(), transport.myPeerID,
                    store = messageManager::addMessage,
                    bluetoothSend = { transport.sendWeshPublicMessage(it, authorized) },
                    authorized = authorized)
                onAccepted(accepted)
            } finally {
                weshPublicSending.value = false
            }
        }
    }

    private val _weshBluetoothReadyState = MutableStateFlow(false)
    /** Emits only on change, so the Wesh screen recomposes when Bluetooth flips, not every second. */
    val weshBluetoothReadyState: StateFlow<Boolean> = _weshBluetoothReadyState.asStateFlow()

    fun weshBluetoothReady(): Boolean {
        val application = getApplication<Application>()
        val adapterEnabled = runCatching {
            application.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        val modernPermissionsGranted = android.os.Build.VERSION.SDK_INT < 31 || listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        ).all { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(application, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val legacyLocationGranted = android.os.Build.VERSION.SDK_INT >= 31 || listOf(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ).all { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(application, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val legacyLocationEnabled = android.os.Build.VERSION.SDK_INT >= 31 || runCatching {
            val location = application.getSystemService(android.location.LocationManager::class.java)
            location != null && androidx.core.location.LocationManagerCompat.isLocationEnabled(location)
        }.getOrDefault(false)
        return weshBluetoothScanAvailable(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            adapterEnabled = adapterEnabled,
            modernPermissionsGranted = modernPermissionsGranted,
            legacyLocationGranted = legacyLocationGranted,
            legacyLocationEnabled = legacyLocationEnabled,
        )
    }

    /** Any enrolled, unblocked contact: the router sends over Bluetooth or Internet, or queues. */
    fun weshCanSendPrivate(peerID: String): Boolean = weshPrivateCompose.canCompose(ContactDirectory.canonicalConversationId(peerID))

    fun weshIsBlocked(peerID: String): Boolean = privateChatManager.isPeerBlocked(peerID)
    fun weshBlockedFingerprints(): Set<String> = weshContactStore.blockedFingerprints()
    /** The contact's local alias, else the remote nickname known for [fingerprint]; shown only through the remote rule. */
    fun weshContactName(fingerprint: String): String? = weshContactStore.forFingerprint(fingerprint)?.displayName
        ?: identityManager.getCachedFingerprintNickname(fingerprint)
    fun weshBlock(peerID: String): Boolean {
        val resolution = ContactDirectory.resolve(peerID)
        val key = weshContactStore.contact(resolution.conversationID)?.key
            ?: resolution.meshPeerID?.let(meshService::getAuthenticatedRemoteStaticKey) ?: return false
        dataManager.addBlockedUser(ContactIdentityResolver.fingerprintHex(key))
        verificationHandler.cancelWeshVerification(resolution.conversationID)
        com.bitchat.android.services.MessageRouter.tryGetInstance()?.cancelWeshConversation(resolution.conversationID)
        clearNotificationsForSender(resolution.conversationID)
        return true
    }
    fun weshUnblock(fingerprint: String) {
        dataManager.removeBlockedUser(fingerprint)
        // Unblocking an identity also lifts any session-only hide of its current peer IDs.
        com.bitchat.android.services.SessionHiddenPeers.peers.value
            .filter { privateChatManager.getAllPeerFingerprints()[it] == fingerprint }
            .forEach(com.bitchat.android.services.SessionHiddenPeers::unhide)
    }
    fun retryWeshContacts() { weshContactStore.retryLoad() }
    fun weshDismissRequest(request: com.bitchat.android.services.WeshMessageRequests.Request) {
        com.bitchat.android.services.WeshMessageRequests.remove(request.peerID)
    }
    fun weshBlockRequest(request: com.bitchat.android.services.WeshMessageRequests.Request): Boolean {
        val fingerprint = request.fingerprint
            ?: meshService.getAuthenticatedRemoteStaticKey(request.peerID)?.let(ContactIdentityResolver::fingerprintHex)
            ?: return false
        dataManager.addBlockedUser(fingerprint)
        cancelWeshQueueFor(fingerprint)
        com.bitchat.android.services.WeshMessageRequests.removeFingerprint(fingerprint)
        com.bitchat.android.services.WeshMessageRequests.remove(request.peerID)
        return true
    }
    val weshSessionHiddenPeers = com.bitchat.android.services.SessionHiddenPeers.peers
    fun weshUnhidePeer(peerID: String) = com.bitchat.android.services.SessionHiddenPeers.unhide(peerID)
    /**
     * Blocks a public sender locally. Returns true when the block is durable (authenticated
     * identity), false when the sender is only hidden by peer ID until Wesh restarts.
     */
    fun weshBlockPublicSender(peerID: String): Boolean =
        when (val target = WeshPublicBlockPolicy.target(peerID, meshService.getAuthenticatedRemoteStaticKey(peerID))) {
            is WeshPublicBlockPolicy.Target.Identity -> {
                dataManager.addBlockedUser(target.fingerprint)
                cancelWeshQueueFor(target.fingerprint)
                true
            }
            is WeshPublicBlockPolicy.Target.Session -> { com.bitchat.android.services.SessionHiddenPeers.hide(target.peerID); false }
        }
    /** A blocked contact's waiting messages fail now; an unblock never sends them later. */
    private fun cancelWeshQueueFor(fingerprint: String) {
        weshContactStore.forFingerprint(fingerprint)?.let { contact ->
            com.bitchat.android.services.MessageRouter.tryGetInstance()?.cancelWeshConversation(contact.conversationID)
        }
    }
    /** Local alias and memo of a QR contact; false when nothing could be written. */
    suspend fun setWeshContactNote(conversationID: String, alias: String, memo: String): Boolean {
        if (!withContext(Dispatchers.IO) { weshContactStore.setNote(conversationID, alias, memo) }) return false
        notificationManager.refreshPrivateConversationName(
            conversationID,
            weshContactStore.contact(conversationID)?.displayName,
        )
        return true
    }
    fun removeWeshContact(conversationID: String) {
        verificationHandler.cancelWeshVerification(conversationID)
        weshContactStore.remove(conversationID)
        ContactIdentityResolver.fingerprintFromContactConversationId(conversationID)?.let(verificationHandler::unverifyFingerprintValue)
        com.bitchat.android.services.MessageRouter.tryGetInstance()?.cancelWeshConversation(conversationID)
        clearNotificationsForSender(conversationID)
    }
    fun weshVerificationProgress(noiseHex: String) = verificationHandler.weshProgress(noiseHex)
    fun weshScannedMeSince(sinceMs: Long, noiseHex: String?) = verificationHandler.weshScannedMeSince(sinceMs, noiseHex)
    /** Noise static key proven by this peer's session, never the announced one; null when unproven. */
    fun weshAuthenticatedKey(peerID: String): ByteArray? = meshService.getAuthenticatedRemoteStaticKey(peerID)
    fun requestWeshQRReview(raw: String) { weshIncomingQR.value = raw; weshContactSheet.value = true }

    fun sendWeshPrivate(content: String, peerID: String, onAccepted: (Boolean) -> Unit) {
        val destination = ContactDirectory.canonicalConversationId(peerID)
        if (content.isBlank() || content.toByteArray().size > 2000 || !weshCanSendPrivate(destination)) {
            onAccepted(false); return
        }
        viewModelScope.launch {
            var persistedID: String? = null
            val accepted = privateChatManager.sendPrivateMessageDurably(
                content, destination, nicknameForPeer(peerID), state.getNicknameValue(), meshService.myPeerID
            ) { _, _, _, messageID -> persistedID = messageID }
            onAccepted(accepted)
            persistedID?.let { dispatchWeshPrivate(content, destination, it) }
        }
    }
    private fun dispatchWeshPrivate(content: String, destination: String, messageID: String) {
        weshPrivateCompose.route(content, destination, nicknameForPeer(destination).orEmpty(), messageID)
    }
    fun retryWeshPrivate(peerID: String, messageID: String) {
        val destination = ContactDirectory.canonicalConversationId(peerID)
        val message = com.bitchat.android.services.AppStateStore.privateMessages.value[destination]
            ?.firstOrNull { it.id == messageID && it.senderPeerID == meshService.myPeerID } ?: return
        if ((message.deliveryStatus !is com.bitchat.android.model.DeliveryStatus.Failed &&
                message.deliveryStatus !is com.bitchat.android.model.DeliveryStatus.Sending) || !weshCanSendPrivate(destination)) return
        dispatchWeshPrivate(message.content, destination, messageID)
    }

    // MARK: - Utility Functions
    
    fun getPeerIDForNickname(nickname: String): String? {
        return mesh.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService
        try {
            var noiseKey: ByteArray? = null
            var nickname: String = mesh.getPeerNicknames()[peerID] ?: peerID

            val peerInfo = mesh.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else if (ContactIdentityResolver.isNoiseKeyHex(peerID)) {
                noiseKey = ContactIdentityResolver.bytesFromHex(peerID)
                val rel = noiseKey?.let {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(it)
                }
                if (rel != null) nickname = rel.peerNickname
            } else {
                val contact = ContactDirectory.resolve(peerID)
                noiseKey = contact.noisePublicKey
                contact.displayName?.let { nickname = it }
            }

            if (noiseKey != null) {
                val identityManager = com.bitchat.android.identity.SecureIdentityStateManager(getApplication())
                val fingerprint = identityManager.generateFingerprint(noiseKey!!)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey!!,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                try {
                    com.bitchat.android.services.MessageRouter
                        .getInstance(getApplication(), mesh)
                        .sendFavoriteNotification(peerID, isNowFavorite)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Log current state after toggle
        logCurrentFavoriteState()
    }
    
    private fun refreshPeerFavoritedUs() {
        try {
            val fingerprints = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                .getAllRelationships()
                .filter { it.theyFavoritedUs }
                .mapNotNull { relationship ->
                    runCatching {
                        ContactIdentityResolver.fingerprintHex(relationship.peerNoisePublicKey)
                    }.getOrNull()
                }
                .toSet()
            state.setPeerFavoritedUs(fingerprints)
        } catch (_: Exception) { }
    }

    private fun logCurrentFavoriteState() {        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "StateFlow favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }

    private fun isConnectedOnMesh(peerID: String): Boolean {
        return try {
            mesh.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnMesh(peerID: String): Boolean {
        return try {
            mesh.getPeerInfo(peerID)?.isConnected == true &&
                mesh.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnAnyLocalTransport(peerID: String): Boolean {
        return hasEstablishedSessionOnMesh(peerID)
    }

    private fun initiateNoiseHandshakeOnBestLocalTransport(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    private fun nicknameForPeer(peerID: String): String? {
        val contact = ContactDirectory.resolve(peerID)
        val meshPeerID = contact.meshPeerID ?: peerID
        return contact.displayName
            ?: state.peerNicknames.value[meshPeerID]
            ?: try { mesh.getPeerNicknames()[meshPeerID] } catch (_: Exception) { null }
    }

    private fun sessionStateForPeer(peerID: String): NoiseSession.NoiseSessionState {
        return try { mesh.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
                _weshBluetoothReadyState.value = weshBluetoothReady()
            }
        }
    }
    
    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            sessionStateForPeer(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.bitchat.android.services.MessageRouter
                    .getInstance(getApplication(), mesh)
                    .onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
        fingerprints.forEach { (peerID, fingerprint) ->
            identityManager.cachePeerFingerprint(peerID, fingerprint)
            val info = try { mesh.getPeerInfo(peerID) } catch (_: Exception) { null }
            val noiseKeyHex = info?.noisePublicKey?.hexEncodedString()
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fingerprint)
            }
            info?.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                identityManager.cacheFingerprintNickname(fingerprint, nickname)
            }
        }

        state.setPeerNicknames(mesh.getPeerNicknames())

        state.setPeerRSSI(mesh.getPeerRSSI())

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                mesh.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }

        // Flush any pending QR verification once a Noise session is established
        currentPeers.forEach { peerID ->
            if (sessionStateForPeer(peerID) is NoiseSession.NoiseSessionState.Established) {
                verificationHandler.sendPendingVerificationIfNeeded(peerID)
            }
        }
    }

    // MARK: - QR Verification
    
    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && verifiedFingerprints.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray, verifiedFingerprints: Set<String>): Boolean {
        val fingerprint = verificationHandler.fingerprintFromNoiseBytes(noisePublicKey)
        return verifiedFingerprints.contains(fingerprint)
    }

    fun unverifyFingerprint(peerID: String) {
        verificationHandler.unverifyFingerprint(peerID)
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        return verificationHandler.beginQRVerification(qr)
    }

    // MARK: - Debug and Troubleshooting
    
    fun getDebugStatus(): String {
        return mesh.getDebugStatus()
    }
    
    fun setCurrentPrivateChatPeer(peerID: String?) {
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }
    
    fun clearNotificationsForSender(peerID: String) {
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    private var reopenSidebarAfterVerification = false

    fun showVerificationSheet(fromSidebar: Boolean = false) {
        if (fromSidebar) {
            reopenSidebarAfterVerification = true
        }
        state.setShowVerificationSheet(true)
    }

    fun hideVerificationSheet() {
        state.setShowVerificationSheet(false)
        if (reopenSidebarAfterVerification) {
            reopenSidebarAfterVerification = false
            state.setShowMeshPeerList(true)
        }
    }

    fun showSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(true)
    }

    fun hideSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(false)
    }

    fun showMeshPeerList() {
        state.setShowMeshPeerList(true)
    }

    fun hideMeshPeerList() {
        state.setShowMeshPeerList(false)
    }

    fun showPrivateChatSheet(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        state.setPrivateChatSheetPeer(conversationID)
    }

    fun hidePrivateChatSheet() {
        state.setPrivateChatSheetPeer(null)
    }

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        return verificationHandler.getPeerFingerprintForDisplay(peerID)
    }

    fun getMyFingerprint(): String {
        return verificationHandler.getMyFingerprint()
    }

    fun resolvePeerDisplayNameForFingerprint(peerID: String): String {
        return verificationHandler.resolvePeerDisplayNameForFingerprint(peerID)
    }

    fun verifyFingerprintValue(fingerprint: String) {
        verificationHandler.verifyFingerprintValue(fingerprint)
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        verificationHandler.unverifyFingerprintValue(fingerprint)
    }

    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyChallenge(peerID, payload)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyResponse(peerID, payload)
    }

    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }
    
    // MARK: - Emergency Clear

    internal val weshPanicInProgress = MutableStateFlow(false)

    fun panicClearAllData() {
        if (weshPanicInProgress.value) return
        weshPanicInProgress.value = true
        com.bitchat.android.services.SeenMessageStore.fenceReceiptAdmissionForPanic()
        if (com.bitchat.android.BuildConfig.WESH_POLICY) com.bitchat.android.startup.WeshRuntime.requirePanicRecovery()
        weshDraftStore.fenceForPanic()
        weshPreferences.fenceForPanic()
        com.bitchat.android.net.WeshInternetMode.get(getApplication()).fenceForPanic()
        // Router queue and in-flight attempts: nothing captured before the erase may leave.
        com.bitchat.android.services.MessageRouter.tryGetInstance()?.clearAll()
        com.bitchat.android.services.AppStateStore.fencePrivateConversationAdmissionForPanic()
        PanicResetCoordinator.stopTransports({ meshService.setBleTransportEnabled(false) })
        val app = getApplication<Application>()
        val draftEpoch = weshDraftStore.epoch
        PanicResetCoordinator.launchPanic(viewModelScope) {
            try {
                PanicResetCoordinator.eraseAndRestart(
                    establishIntent = {
                        !com.bitchat.android.BuildConfig.WESH_POLICY || weshDraftStore.establishPanicIntent(draftEpoch)
                    },
                    wipe = {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            com.bitchat.android.startup.WeshDataWipe.wipe(app)
                        }
                    },
                    restart = { com.bitchat.android.startup.WeshDataWipe.restart(app) },
                    clearAppData = { com.bitchat.android.startup.WeshRuntime.clearInterruptedPanic(app) }
                )
            } finally {
                weshPanicInProgress.value = false
            }
        }
    }

    // MARK: - Navigation Management
    
    fun showAppInfo() {
        state.setShowAppInfo(true)
    }
    
    // MARK: - Canonical peer identities

    /**
     * Return the stable identity used by every UI surface to color a mesh peer.
     */
    fun peerIdentityForMeshPeer(peerID: String): PeerIdentity = PeerIdentity.mesh(peerID)

    /**
     * Return the stable identity used by every UI surface to color a Nostr peer.
     */
    fun peerIdentityForNostrPubkey(pubkeyHex: String): PeerIdentity =
        PeerIdentity.nostr(pubkeyHex)

}

/** True only when Android currently permits an actual BLE scan, not merely when the radio is on. */
internal fun weshBluetoothScanAvailable(
    sdkInt: Int,
    adapterEnabled: Boolean,
    modernPermissionsGranted: Boolean,
    legacyLocationGranted: Boolean,
    legacyLocationEnabled: Boolean,
): Boolean = adapterEnabled && if (sdkInt >= 31) {
    modernPermissionsGranted
} else {
    legacyLocationGranted && legacyLocationEnabled
}
