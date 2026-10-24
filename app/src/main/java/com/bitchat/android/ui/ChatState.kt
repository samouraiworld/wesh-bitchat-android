package com.bitchat.android.ui

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Centralized state definitions and data classes for the chat system
 */

/**
 * Contains all the observable state for the chat system
 */
class ChatState(
    scope: CoroutineScope
) {
    
    // Core messages and peer state
    private val _messages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val messages: StateFlow<List<BitchatMessage>> = _messages.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()
    
    private val _nickname = MutableStateFlow<String>("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()
    
    private val _isConnected = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Private chats
    private val _privateChats = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = _privateChats.asStateFlow()
    
    private val _selectedPrivateChatPeer = MutableStateFlow<String?>(null)
    val selectedPrivateChatPeer: StateFlow<String?> = _selectedPrivateChatPeer.asStateFlow()
    
    private val _unreadPrivateMessages = MutableStateFlow<Set<String>>(emptySet())
    val unreadPrivateMessages: StateFlow<Set<String>> = _unreadPrivateMessages.asStateFlow()
    
    // Favorites
    private val _favoritePeers = MutableStateFlow<Set<String>>(emptySet())
    val favoritePeers: StateFlow<Set<String>> = _favoritePeers.asStateFlow()

    // Fingerprints of peers who favorited us (drives "favorited you" UI celebrations)
    private val _peerFavoritedUs = MutableStateFlow<Set<String>>(emptySet())
    val peerFavoritedUs: StateFlow<Set<String>> = _peerFavoritedUs.asStateFlow()
    
    // Noise session states for peers (for reactive UI updates)
    private val _peerSessionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerSessionStates: StateFlow<Map<String, String>> = _peerSessionStates.asStateFlow()
    
    // Peer fingerprint state for reactive favorites (for reactive UI updates)
    private val _peerFingerprints = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerFingerprints: StateFlow<Map<String, String>> = _peerFingerprints.asStateFlow()

    private val _peerNicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerNicknames: StateFlow<Map<String, String>> = _peerNicknames.asStateFlow()

    private val _peerRSSI = MutableStateFlow<Map<String, Int>>(emptyMap())
    val peerRSSI: StateFlow<Map<String, Int>> = _peerRSSI.asStateFlow()

    // Direct connection status per peer (for live UI updates)
    private val _peerDirect = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val peerDirect: StateFlow<Map<String, Boolean>> = _peerDirect.asStateFlow()
    
    // peerIDToPublicKeyFingerprint REMOVED - fingerprints now handled centrally in PeerManager
    
    // Navigation state
    private val _showAppInfo = MutableStateFlow<Boolean>(false)
    val showAppInfo: StateFlow<Boolean> = _showAppInfo.asStateFlow()

    private val _showMeshPeerList = MutableStateFlow(false)
    val showMeshPeerList: StateFlow<Boolean> = _showMeshPeerList.asStateFlow()

    private val _privateChatSheetPeer = MutableStateFlow<String?>(null)
    val privateChatSheetPeer: StateFlow<String?> = _privateChatSheetPeer.asStateFlow()

    private val _showVerificationSheet = MutableStateFlow(false)
    val showVerificationSheet: StateFlow<Boolean> = _showVerificationSheet.asStateFlow()

    private val _showSecurityVerificationSheet = MutableStateFlow(false)
    val showSecurityVerificationSheet: StateFlow<Boolean> = _showSecurityVerificationSheet.asStateFlow()
    
    val hasUnreadPrivateMessages: StateFlow<Boolean> = _unreadPrivateMessages
        .map { unreadSet -> unreadSet.isNotEmpty() }
        .stateIn(
            scope = scope,
            started = WhileSubscribed(5_000),
            initialValue = false
        )
    
    // Getters for internal state access
    fun getMessagesValue() = _messages.value
    fun getConnectedPeersValue() = _connectedPeers.value
    fun getNicknameValue() = _nickname.value
    fun getPrivateChatsValue() = _privateChats.value
    fun getSelectedPrivateChatPeerValue() = _selectedPrivateChatPeer.value
    fun getUnreadPrivateMessagesValue() = _unreadPrivateMessages.value
    fun getPeerFavoritedUsValue() = _peerFavoritedUs.value
    fun getFavoritePeersValue() = _favoritePeers.value
    fun getPeerSessionStatesValue() = _peerSessionStates.value
    fun getPeerFingerprintsValue() = _peerFingerprints.value

    fun getShowMeshPeerListValue() = _showMeshPeerList.value
    fun getPrivateChatSheetPeerValue() = _privateChatSheetPeer.value
    
    // Setters for state updates
    fun setMessages(messages: List<BitchatMessage>) {
        _messages.value = messages
    }
    
    fun setConnectedPeers(peers: List<String>) {
        _connectedPeers.value = peers
    }
    
    fun setNickname(nickname: String) {
        _nickname.value = nickname
        com.bitchat.android.services.AppStateStore.setNickname(nickname)
    }
    
    fun setIsConnected(connected: Boolean) {
        _isConnected.value = connected
    }
    
    fun setPrivateChats(chats: Map<String, List<BitchatMessage>>) {
        _privateChats.value = chats
    }
    
    fun setSelectedPrivateChatPeer(peerID: String?) {
        _selectedPrivateChatPeer.value = peerID
        com.bitchat.android.services.AppStateStore.setSelectedPrivateChatPeer(peerID)
    }
    
    fun setUnreadPrivateMessages(unread: Set<String>) {
        _unreadPrivateMessages.value = unread
    }
    
    fun setFavoritePeers(favorites: Set<String>) {
        val currentValue = _favoritePeers.value
        Log.d("ChatState", "setFavoritePeers called with ${favorites.size} favorites: $favorites")
        Log.d("ChatState", "Current value: $currentValue")
        Log.d("ChatState", "Values equal: ${currentValue == favorites}")
        Log.d("ChatState", "Setting on thread: ${Thread.currentThread().name}")
        
        // Always set the value - even if equal, this ensures observers are triggered
        _favoritePeers.value = favorites
        
        Log.d("ChatState", "StateFlow value after set: ${_favoritePeers.value}")
    }

    fun setPeerFavoritedUs(fingerprints: Set<String>) {
        _peerFavoritedUs.value = fingerprints
    }
    
    fun setPeerSessionStates(states: Map<String, String>) {
        _peerSessionStates.value = states
    }
    
    fun setPeerFingerprints(fingerprints: Map<String, String>) {
        _peerFingerprints.value = fingerprints
    }

    fun setPeerNicknames(nicknames: Map<String, String>) {
        _peerNicknames.value = nicknames
    }

    fun setPeerRSSI(rssi: Map<String, Int>) {
        _peerRSSI.value = rssi
    }

    fun setPeerDirect(direct: Map<String, Boolean>) {
        _peerDirect.value = direct
    }
    
    fun setShowAppInfo(show: Boolean) {
        _showAppInfo.value = show
    }

    fun setShowVerificationSheet(show: Boolean) {
        _showVerificationSheet.value = show
    }

    fun setShowSecurityVerificationSheet(show: Boolean) {
        _showSecurityVerificationSheet.value = show
    }
    
    fun setShowMeshPeerList(show: Boolean) {
        _showMeshPeerList.value = show
    }

    fun setPrivateChatSheetPeer(peerID: String?) {
        _privateChatSheetPeer.value = peerID
    }
}
