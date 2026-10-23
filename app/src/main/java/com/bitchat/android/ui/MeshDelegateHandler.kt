package com.bitchat.android.ui

import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.ui.NotificationTextUtils
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.SeenReceiptAdmission
import com.bitchat.android.services.ContactDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Handles all BluetoothMeshDelegate callbacks and routes them to appropriate managers
 */
class MeshDelegateHandler internal constructor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val coroutineScope: CoroutineScope,
    private val onHapticFeedback: () -> Unit,
    private val getMeshService: () -> MeshService,
    private val publicNotificationsMuted: () -> Boolean = { false },
    private val markMessageReadLocally: (messageID: String, expectedToken: SeenReceiptAdmission.Token?) -> Unit = { _, _ -> },
    private val captureSeenToken: () -> SeenReceiptAdmission.Token? = { SeenMessageStore.captureReceiptToken() },
    /** READ over Internet for a message that came over Internet (MessageRouter.sendWeshInternetReadReceipt). */
    private val sendInternetReadReceipt: (messageID: String, conversationID: String, expectedToken: SeenReceiptAdmission.Token?) -> Unit =
        { _, _, _ -> },
    private val readReceiptsEnabled: () -> Boolean = { true }
) : BluetoothMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        // Wesh has one public room and no named channels: a message tagged for a BitChat channel
        // never reaches the UI (admission already drops it), and neither buzzes nor notifies.
        if (!message.isPrivate && message.channel != null) return
        val seenToken = captureSeenToken()
        coroutineScope.launch {
            val messageKey = messageManager.generateMessageKey(message)
            if (messageManager.isMessageProcessed(messageKey)) {
                return@launch // Duplicate message, ignore
            }
            messageManager.markMessageProcessed(messageKey)
            
            // Check if sender is blocked
            message.senderPeerID?.let { senderPeerID ->
                if (privateChatManager.isPeerBlocked(senderPeerID)) {
                    return@launch
                }
            }
            
            // Trigger haptic feedback
            onHapticFeedback()

            if (message.isPrivate) {
                if (message.sender == "system") {
                    // System notices (e.g. "x favorited you"): no unread badge, read receipt or push
                    privateChatManager.handleIncomingPrivateMessage(message, suppressUnread = true)
                } else {
                    // Private message
                    privateChatManager.handleIncomingPrivateMessage(message)

                    // Reactive read receipts: if chat is focused, send immediately for this message
                    message.senderPeerID?.let { senderPeerID ->
                        sendReadReceiptIfFocused(message, seenToken)
                    }

                    // Show notification with enhanced information - now includes senderPeerID
                    message.senderPeerID?.let { senderPeerID ->
                        // No nickname: null, so the notification shows the unnamed label, never the peer ID.
                        val senderNickname = message.sender.takeIf { it.isNotBlank() && it != senderPeerID }
                        val preview = NotificationTextUtils.buildPrivateMessagePreview(message)
                        notificationManager.showPrivateMessageNotification(
                            senderPeerID = senderPeerID,
                            senderNickname = senderNickname,
                            messageContent = preview
                        )
                    }
                }
            } else {
                // Public mesh message: AppStateStore is the source of truth; avoid double-adding to UI state
                // Still run mention detection/notifications
                checkAndTriggerMeshMentionNotification(message)
            }
            
            // Periodic cleanup
            if (messageManager.isMessageProcessed("cleanup_check_${System.currentTimeMillis()/30000}")) {
                messageManager.cleanupDeduplicationCaches()
            }
        }
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScope.launch {
            processPeerUpdate(peers.distinct())
        }
    }

    private suspend fun processPeerUpdate(mergedPeers: List<String>) {
        state.setConnectedPeers(mergedPeers)
        state.setIsConnected(mergedPeers.isNotEmpty())
        
        // Flush router outbox for any peers that just connected (and their noiseHex aliases)
        runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance()?.onPeersUpdated(mergedPeers) }

        runCatching { com.bitchat.android.services.AppStateStore.canonicalizePrivateChats() }

        state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
            val canonical = ContactDirectory.canonicalConversationId(currentPeer)
            if (canonical != currentPeer) {
                com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(
                    state = state,
                    targetPeerID = canonical,
                    keysToMerge = ContactDirectory.aliasesForConversation(currentPeer).toList()
                )
                state.setSelectedPrivateChatPeer(canonical)
            }
        }

        state.getPrivateChatSheetPeerValue()?.let { sheetPeer ->
            val canonical = ContactDirectory.canonicalConversationId(sheetPeer)
            if (canonical != sheetPeer) {
                state.setPrivateChatSheetPeer(canonical)
            }
        }

        mergedPeers.forEach { pid ->
            try {
                val canonical = ContactDirectory.canonicalConversationId(pid)
                unifyChatsIntoPeer(canonical, ContactDirectory.aliasesForConversation(pid).toList())
            } catch (_: Exception) { }
        }
    }

    /**
     * Merge any chats stored under the given keys into the connected peer's chat entry.
     */
    private fun unifyChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, targetPeerID, keysToMerge)
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Delivered(recipientPeerID, Date()))
        }
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Read(recipientPeerID, Date()))
        }
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        // Handled by ChatViewModel for verification flow
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        // Handled by ChatViewModel for verification flow
    }
    
    override fun getNickname(): String? = state.getNicknameValue()
    
    override fun isFavorite(peerID: String): Boolean {
        return privateChatManager.isFavorite(peerID)
    }
    
    /**
     * Check for mentions in mesh messages and trigger notifications
     */
    private fun checkAndTriggerMeshMentionNotification(message: BitchatMessage) {
        if (publicNotificationsMuted()) return
        try {
            // Get user's current nickname
            val currentNickname = state.getNicknameValue()
            if (currentNickname.isNullOrEmpty()) {
                return
            }

            // Check if this message mentions the current user using @username format
            val isMention = checkForMeshMention(message.content, currentNickname)

            if (isMention) {
                notificationManager.showMeshMentionNotification(
                    senderNickname = message.sender,
                    messageContent = message.content,
                    senderPeerID = message.senderPeerID
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshDelegateHandler", "Error checking mesh mentions: ${e.message}")
        }
    }

    /**
     * Check if the content mentions the current user with @username format (simple, no hash suffix)
     */
    private fun checkForMeshMention(content: String, currentNickname: String): Boolean {
        // Simple mention pattern for mesh: @username (no hash suffix)
        val mentionPattern = "@([\\p{L}0-9_]+)".toRegex()

        return mentionPattern.findAll(content).any { match ->
            val mentionedUsername = match.groupValues[1]
            // Direct comparison for mesh mentions (no hash suffix to remove)
            mentionedUsername.equals(currentNickname, ignoreCase = true)
        }
    }

    /**
     * An Internet message just stored while this UI is attached (called on Main): read at once if its
     * chat is open in the foreground, like a Bluetooth one, and its READ goes back over Internet.
     */
    fun readInternetMessageIfFocused(message: BitchatMessage) {
        val conversationID = message.senderPeerID?.let { ContactDirectory.canonicalConversationId(it) } ?: return
        val focused = notificationManager.getCurrentPrivateChatPeer()?.let { ContactDirectory.canonicalConversationId(it) }
        if (notificationManager.getAppBackgroundState() || focused != conversationID) return
        val token = captureSeenToken()
        try { markMessageReadLocally(message.id, token) } catch (_: Exception) { }
        if (message.type == BitchatMessageType.Message && runCatching { readReceiptsEnabled() }.getOrDefault(false)) {
            try { sendInternetReadReceipt(message.id, conversationID, token) } catch (_: Exception) { }
        }
        try {
            val current = state.getUnreadPrivateMessagesValue()
            if (conversationID in current) state.setUnreadPrivateMessages(current - conversationID)
        } catch (_: Exception) { }
    }

    /**
     * Send read receipts reactively based on UI focus state.
     * Uses same logic as notification system - send read receipt if user is currently
     * viewing the private chat with this sender AND app is in foreground.
     */
    private fun sendReadReceiptIfFocused(message: BitchatMessage, expectedToken: SeenReceiptAdmission.Token?) {
        // Get notification manager's focus state (mirror the notification logic)
        val isAppInBackground = notificationManager.getAppBackgroundState()
        val currentPrivateChatPeer = notificationManager.getCurrentPrivateChatPeer()
        
        // Send read receipt if user is currently focused on this specific chat
        val senderPeerID = message.senderPeerID
        val senderConversationID = senderPeerID?.let { ContactDirectory.canonicalConversationId(it) }
        val focusedConversationID = currentPrivateChatPeer?.let { ContactDirectory.canonicalConversationId(it) }
        val shouldSendReadReceipt = !isAppInBackground &&
            senderConversationID != null &&
            focusedConversationID == senderConversationID

        if (shouldSendReadReceipt) {
            android.util.Log.d(
                "MeshDelegateHandler",
                "Sending reactive read receipt for focused chat with $senderConversationID (message=${message.id})"
            )
            // UI focus is the source of truth for local read state. Transport acceptance is a
            // separate fact and may remain retryable when the peer disconnects.
            try { markMessageReadLocally(message.id, expectedToken) } catch (_: Exception) { }

            if (runCatching { readReceiptsEnabled() }.getOrDefault(false)) {
                val nickname = state.getNicknameValue().ifBlank { "unknown" }
                val mesh = getMeshService()
                try {
                    val meshPeerID = ContactDirectory.resolve(senderConversationID).meshPeerID
                        ?: senderPeerID.takeIf {
                            com.bitchat.android.services.ContactIdentityResolver.isMeshPeerId(it)
                        }
                    // A media placeholder stands for something never received: never reported read.
                    if (meshPeerID != null && message.type == BitchatMessageType.Message &&
                        mesh.getPeerInfo(meshPeerID)?.isConnected == true &&
                        mesh.hasEstablishedSession(meshPeerID)
                    ) {
                        mesh.sendReadReceipt(message.id, meshPeerID, nickname)
                    }
                } catch (_: Exception) { }
            }

            // Ensure unread badge is cleared for this peer immediately.
            try {
                val current = state.getUnreadPrivateMessagesValue().toMutableSet()
                val changed = current.remove(senderPeerID) or current.remove(senderConversationID)
                if (changed) {
                    state.setUnreadPrivateMessages(current)
                }
            } catch (_: Exception) { }
        } else {
            android.util.Log.d("MeshDelegateHandler", "Skipping read receipt - chat not focused (background: $isAppInBackground, current peer: $currentPrivateChatPeer, sender: $senderPeerID)")
        }
    }

    /**
     * Expose mesh peer info for components that need to resolve identities (e.g., Nostr mapping)
     */
    fun getPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? {
        return getMeshService().getPeerInfo(peerID)
    }

}
