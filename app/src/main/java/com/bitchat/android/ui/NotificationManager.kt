package com.bitchat.android.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.service.ConversationNotificationReceiver
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.WeshContactStore
import com.bitchat.android.ui.pave.RemoteNameRule
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced notification manager for direct messages with production-ready features:
 * - Notification grouping per sender
 * - Click handling to open a specific DM
 * - App background state awareness
 * - Proper notification management and cleanup
 */
class NotificationManager(
  private val context: Context,
  private val notificationManager: NotificationManagerCompat,
  private val isConversationMuted: (String) -> Boolean = { conversationID ->
      ConversationListPreferences.getInstance(context.applicationContext).isMuted(conversationID)
  },
  /** Every nickname a notification shows comes from someone else: one rule, the Pavé one. */
  private val remoteName: RemoteNameRule = RemoteNameRule.of(context)
) {

    companion object {
        private const val TAG = "NotificationManager"
        private const val CHANNEL_ID = "bitchat_dm_notifications"
        // Channel of the removed location chats; deleted at startup in case an older build made it.
        private const val LEGACY_GEOHASH_CHANNEL_ID = "bitchat_geohash_notifications"
        private const val GROUP_KEY_DM = "bitchat_dm_group"
        private const val NOTIFICATION_REQUEST_CODE = 1000
        private const val SUMMARY_NOTIFICATION_ID = 999
        private const val MAX_MESSAGES_IN_NOTIFICATION = 25

        // Intent extras for notification handling
        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_SENDER_NICKNAME = "sender_nickname"
        const val ACTION_REPLY_TO_CONVERSATION =
            "com.bitchat.android.action.REPLY_TO_CONVERSATION"
        const val ACTION_MARK_CONVERSATION_READ =
            "com.bitchat.android.action.MARK_CONVERSATION_READ"
        const val KEY_TEXT_REPLY = "conversation_reply_text"

        private val liveManagers: MutableSet<NotificationManager> =
            Collections.newSetFromMap(WeakHashMap<NotificationManager, Boolean>())

        /**
         * Synchronizes notification action receivers with every manager instance in this process.
         * Without this, an old in-memory MessagingStyle history could reappear on the next DM.
         */
        fun acknowledgeConversation(context: Context, conversationID: String) {
            val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
            val managers = synchronized(liveManagers) { liveManagers.toList() }
            managers.forEach { it.clearNotificationsForSender(canonicalID) }
            if (managers.isEmpty()) {
                NotificationManagerCompat.from(context).cancel(canonicalID.hashCode())
            }
        }
    }

    private val systemNotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
    /**
     * Wesh notifications never carry message text, publish conversation shortcuts or bridge to a
     * watch; the sender name appears only after an explicit opt-in on this phone.
     */
    private val weshPrivacy = com.bitchat.android.BuildConfig.WESH_POLICY
    
    // Track pending notifications per sender to enable grouping
    private val pendingNotifications = ConcurrentHashMap<String, MutableList<PendingNotification>>()

    // Track app background state
    @Volatile
    private var isAppInBackground = false
    
    // Track current view state
    @Volatile
    private var currentPrivateChatPeer: String? = null

    data class PendingNotification(
        val senderPeerID: String,
        val senderNickname: String, 
        val messageContent: String,
        val timestamp: Long
    )

    init {
        synchronized(liveManagers) { liveManagers.add(this) }
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // DM notifications channel
            val dmName = context.getString(R.string.notification_channel_direct_messages)
            val dmDescriptionText =
                context.getString(R.string.notification_channel_direct_messages_description)
            val dmImportance = AndroidNotificationManager.IMPORTANCE_HIGH
            val dmChannel = NotificationChannel(CHANNEL_ID, dmName, dmImportance).apply {
                description = dmDescriptionText
                enableVibration(true)
                setShowBadge(true)
            }
            systemNotificationManager.createNotificationChannel(dmChannel)

            systemNotificationManager.deleteNotificationChannel(LEGACY_GEOHASH_CHANNEL_ID)
        }
    }

    /**
     * Update app background state - notifications should only be shown when app is backgrounded
     */
    fun setAppBackgroundState(inBackground: Boolean) {
        isAppInBackground = inBackground
        Log.d(TAG, "App background state changed: $inBackground")
    }

    /**
     * Update current private chat peer - affects notification logic
     */
    fun setCurrentPrivateChatPeer(peerID: String?) {
        currentPrivateChatPeer = peerID?.let { ContactDirectory.canonicalConversationId(it) }
        Log.d(TAG, "Current private chat peer changed: $currentPrivateChatPeer")
    }

    /**
     * Show a notification for a private message with proper grouping and state awareness
     */
    /**
     * [senderNickname] is the nickname as received, null when unknown: shown through the remote rule.
     * A QR contact's local alias, when set, is shown instead.
     */
    fun showPrivateMessageNotification(senderPeerID: String, senderNickname: String?, messageContent: String) {
        val conversationID = ContactDirectory.canonicalConversationId(senderPeerID)
        if (isConversationMuted(conversationID)) {
            Log.d(TAG, "Skipping muted conversation notification")
            return
        }
        // Only show notifications if app is in background OR user is not viewing this specific chat
        val shouldNotify = isAppInBackground ||
            (!isAppInBackground && currentPrivateChatPeer != conversationID)
        
        if (!shouldNotify) return

        val notification = PendingNotification(
            senderPeerID = conversationID,
            senderNickname = remoteName.show(contactAlias(conversationID) ?: senderNickname),
            messageContent = messageContent,
            timestamp = System.currentTimeMillis()
        )

        // Add to pending notifications for this sender
        pendingNotifications.computeIfAbsent(conversationID) { mutableListOf() }.add(notification)

        // Create or update notification for this sender
        showNotificationForSender(conversationID)
        
        // Update summary notification if we have multiple senders
        if (pendingNotifications.size > 1) {
            showSummaryNotification()
        }
    }

    private fun contactAlias(conversationID: String): String? =
        runCatching { WeshContactStore.get(context).contact(conversationID)?.alias }.getOrNull()

    /** Rebuilds already-visible conversation notifications after a local contact rename. */
    fun refreshPrivateConversationName(senderPeerID: String, senderNickname: String?) {
        val conversationID = ContactDirectory.canonicalConversationId(senderPeerID)
        val notifications = pendingNotifications[conversationID] ?: return
        val displayName = remoteName.show(senderNickname)
        synchronized(notifications) {
            notifications.indices.forEach { index ->
                notifications[index] = notifications[index].copy(senderNickname = displayName)
            }
        }
        showNotificationForSender(conversationID)
        if (pendingNotifications.size > 1) showSummaryNotification()
    }

    private fun showNotificationForSender(senderPeerID: String) {
        val notifications = pendingNotifications[senderPeerID] ?: return
        if (notifications.isEmpty()) return
        if (weshPrivacy) return showRedactedNotificationForSender(senderPeerID, notifications)

        val latestNotification = notifications.last()
        val messageCount = notifications.size

        // Create intent to open the specific private chat
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PRIVATE_CHAT, true)
            putExtra(EXTRA_PEER_ID, senderPeerID)
            putExtra(EXTRA_SENDER_NICKNAME, latestNotification.senderNickname)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create person for better notification styling
        val person = Person.Builder()
            .setName(latestNotification.senderNickname)
            .setKey(senderPeerID)
            .build()
        val shortcutID = conversationShortcutID(senderPeerID)
        publishConversationShortcut(
            shortcutID = shortcutID,
            person = person,
            contentIntent = intent
        )

        // Build notification content
        val contentText = if (messageCount == 1) {
            latestNotification.messageContent
        } else {
            "${latestNotification.messageContent} (+${messageCount - 1} more)"
        }

        val contentTitle = if (messageCount == 1) {
            latestNotification.senderNickname
        } else {
            "${latestNotification.senderNickname} ($messageCount messages)"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addPerson(person)
            .setShortcutId(shortcutID)
            .setLocusId(LocusIdCompat(shortcutID))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        val markReadIntent = Intent(context, ConversationNotificationReceiver::class.java).apply {
            action = ACTION_MARK_CONVERSATION_READ
            putExtra(EXTRA_PEER_ID, senderPeerID)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode() + 1,
            markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyIntent = Intent(context, ConversationNotificationReceiver::class.java).apply {
            action = ACTION_REPLY_TO_CONVERSATION
            putExtra(EXTRA_PEER_ID, senderPeerID)
            putExtra(EXTRA_SENDER_NICKNAME, latestNotification.senderNickname)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode() + 2,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notification_reply))
            .build()
        builder
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_mark_read),
                    markReadPendingIntent
                ).build()
            )
        if (!com.bitchat.android.BuildConfig.WESH_POLICY) builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_reply),
                    replyPendingIntent
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .build()
            )
        builder.setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notification_private_message))
                    .setContentText(context.getString(R.string.notification_content_hidden))
                    .build()
            )

        // Add to notification group if we have multiple senders
        if (pendingNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_DM)
        }

        val self = Person.Builder()
            .setName(context.getString(R.string.you))
            .setKey("bitchat-self")
            .build()
        val messagingStyle = NotificationCompat.MessagingStyle(self)
            .setGroupConversation(false)
        notifications.takeLast(MAX_MESSAGES_IN_NOTIFICATION).forEach { notification ->
            messagingStyle.addMessage(
                notification.messageContent,
                notification.timestamp,
                person
            )
        }
        builder.setStyle(messagingStyle)

        // Use sender peer ID hash as notification ID to group messages from same sender
        val notificationId = senderPeerID.hashCode()
        notifySafely(notificationId, builder.build())

    }

    private fun showRedactedNotificationForSender(senderPeerID: String, notifications: List<PendingNotification>) {
        val latest = notifications.last()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PRIVATE_CHAT, true)
            putExtra(EXTRA_PEER_ID, senderPeerID)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markReadIntent = Intent(context, ConversationNotificationReceiver::class.java).apply {
            action = ACTION_MARK_CONVERSATION_READ
            putExtra(EXTRA_PEER_ID, senderPeerID)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode() + 1,
            markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (WeshPreferences.notificationShowsSender(context)) latest.senderNickname
            else context.getString(R.string.wesh_notification_new_message)
        val builder = redactedBuilder(title, notifications.size, latest.timestamp)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_mark_read),
                    markReadPendingIntent
                ).build()
            )
        if (pendingNotifications.size > 1) builder.setGroup(GROUP_KEY_DM)
        notifySafely(senderPeerID.hashCode(), builder.build())
    }

    private fun redactedBuilder(title: String, count: Int, timestamp: Long): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                if (count == 1) context.getString(R.string.wesh_notification_open_to_read)
                else context.getString(R.string.wesh_notification_message_count, count)
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.wesh_notification_new_message))
                    .setLocalOnly(true)
                    .build()
            )
            .setLocalOnly(true)
            .setShowWhen(true)
            .setWhen(timestamp)

    private fun conversationShortcutID(conversationID: String): String =
        "dm_" + java.util.UUID.nameUUIDFromBytes(
            conversationID.lowercase().toByteArray(Charsets.UTF_8)
        ).toString()

    private fun publishConversationShortcut(
        shortcutID: String,
        person: Person,
        contentIntent: Intent
    ) {
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutID)
            .setShortLabel(person.name?.toString()?.take(40).orEmpty())
            .setLongLived(true)
            .setPerson(person)
            .setLocusId(LocusIdCompat(shortcutID))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
            .setIntent(Intent(contentIntent).apply { action = Intent.ACTION_VIEW })
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /** Wesh shows the verified contact's [nickname], as announced, only after the sender opt-in. */
    fun showVerificationSuccessNotification(nickname: String?, peerID: String?) {
        val body = if (!weshPrivacy || WeshPreferences.notificationShowsSender(context)) {
            context.getString(R.string.verify_success_body, remoteName.show(nickname))
        } else {
            context.getString(R.string.wesh_notification_verified_body)
        }
        showVerificationNotification(context.getString(R.string.verify_success_title), body, peerID)
    }

    fun showVerificationNotification(title: String, body: String, peerID: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (peerID != null) {
                putExtra(EXTRA_OPEN_PRIVATE_CHAT, true)
                putExtra(EXTRA_PEER_ID, peerID)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
        if (weshPrivacy) builder.setLocalOnly(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        notifySafely(
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            builder.build()
        )
    }

    private fun showSummaryNotification() {
        if (pendingNotifications.isEmpty()) return

        val totalMessages = pendingNotifications.values.sumOf { it.size }
        val senderCount = pendingNotifications.size

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_messages_from_people, totalMessages, senderCount))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_DM)
            .setGroupSummary(true)

        if (weshPrivacy) {
            builder.setContentTitle(context.getString(R.string.wesh_notification_new_message))
                .setContentText(context.getString(R.string.wesh_notification_message_count, totalMessages))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setLocalOnly(true)
            notifySafely(SUMMARY_NOTIFICATION_ID, builder.build())
            return
        }

        // Add inbox style showing recent senders
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.notification_new_location_messages))
            
        pendingNotifications.entries.take(5).forEach { (peerID, notifications) ->
            val latestNotif = notifications.last()
            val count = notifications.size
            val line = if (count == 1) {
                "${latestNotif.senderNickname}: ${latestNotif.messageContent}"
            } else {
                "${latestNotif.senderNickname}: $count messages"
            }
            style.addLine(line)
        }
        
        if (pendingNotifications.size > 5) {
            style.setSummaryText(context.getString(R.string.notification_more_conversations, pendingNotifications.size - 5))
        }
        
        builder.setStyle(style)

        notifySafely(SUMMARY_NOTIFICATION_ID, builder.build())

        Log.d(TAG, "Displayed summary notification for $senderCount senders")
    }

    /**
     * Clear notifications for a specific sender (e.g., when user opens their chat)
     */
    fun clearNotificationsForSender(senderPeerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(senderPeerID)
        val matchingKeys = pendingNotifications.keys.filter { key ->
            ContactDirectory.canonicalConversationId(key) == conversationID
        }
        matchingKeys.forEach { key ->
            pendingNotifications.remove(key)
            notificationManager.cancel(key.hashCode())
        }
        notificationManager.cancel(conversationID.hashCode())

        // Update or remove summary notification
        if (pendingNotifications.isEmpty()) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else if (pendingNotifications.size == 1) {
            // Only one sender left, remove group summary
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else {
            // Update summary notification
            showSummaryNotification()
        }
        
        Log.d(TAG, "Cleared notifications for conversation: $conversationID")
    }

    fun removeConversationShortcut(conversationID: String) {
        val shortcutIDs = listOf(conversationShortcutID(conversationID))
        ShortcutManagerCompat.removeDynamicShortcuts(context, shortcutIDs)
        ShortcutManagerCompat.removeLongLivedShortcuts(
            context,
            shortcutIDs
        )
    }

    /**
     * Show a notification for a mesh mention (@username format)
     */
    fun showMeshMentionNotification(
        senderNickname: String?,
        messageContent: String,
        senderPeerID: String? = null
    ) {
        // Only show notifications if app is in background OR user is not viewing mesh chat
        // User is viewing mesh chat when not in a private chat
        val isViewingMeshChat = currentPrivateChatPeer == null
        val shouldNotify = isAppInBackground || (!isAppInBackground && !isViewingMeshChat)

        if (!shouldNotify) return

        // Use a special key for mesh mentions to group them together
        val meshMentionKey = "mesh_mentions"
        val notification = PendingNotification(
            senderPeerID = senderPeerID ?: meshMentionKey,
            senderNickname = remoteName.show(senderNickname),
            messageContent = messageContent,
            timestamp = System.currentTimeMillis()
        )

        // Add to pending notifications for mesh mentions
        pendingNotifications.computeIfAbsent(meshMentionKey) { mutableListOf() }.add(notification)

        // Create or update notification for mesh mentions
        showNotificationForMeshMentions()

        // Update summary notification if we have multiple senders
        if (pendingNotifications.size > 1) {
            showSummaryNotification()
        }
    }

    private fun showNotificationForMeshMentions() {
        val notifications = pendingNotifications["mesh_mentions"] ?: return
        if (notifications.isEmpty()) return

        val latestNotification = notifications.last()
        val messageCount = notifications.size

        // Create intent to open the mesh chat (no specific peer, just main chat)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // No specific chat to open, just bring the app to foreground
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE + "mesh_mentions".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification content
        val contentTitle = if (messageCount == 1) {
            context.getString(R.string.notification_mesh_mention_title_singular)
        } else {
            context.getString(R.string.notification_mesh_mention_title_plural, messageCount)
        }
        if (weshPrivacy) {
            val builder = redactedBuilder(contentTitle, messageCount, latestNotification.timestamp)
                .setContentIntent(pendingIntent)
            if (pendingNotifications.size > 1) builder.setGroup(GROUP_KEY_DM)
            notifySafely(4000, builder.build())
            return
        }

        val contentText = "${latestNotification.senderNickname}: ${latestNotification.messageContent}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        // Add to notification group if we have multiple senders
        if (pendingNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_DM)
        }

        // Add style for multiple messages
        if (messageCount > 1) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(contentTitle)

            // Show last few messages in expanded view
            notifications.takeLast(5).forEach { notif ->
                style.addLine("${notif.senderNickname}: ${notif.messageContent}")
            }

            if (messageCount > 5) {
                val extra = messageCount - 5
                style.setSummaryText(context.resources.getQuantityString(R.plurals.notification_and_more, extra, extra))
            }

            builder.setStyle(style)
        } else {
            // Single message - use BigTextStyle for long messages
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
            )
        }

        // Use a special notification ID for mesh mentions
        val notificationId = 4000 // Different from the other notification IDs
        notifySafely(notificationId, builder.build())

        Log.d(TAG, "Displayed mesh mention notification: $contentTitle")
    }

    /**
     * Clear mesh mention notifications (when user opens mesh chat)
     */
    fun clearMeshMentionNotifications() {
        pendingNotifications.remove("mesh_mentions")

        // Cancel the mesh mention notification
        val notificationId = 4000
        notificationManager.cancel(notificationId)

        // Update or remove summary notification
        if (pendingNotifications.isEmpty()) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else if (pendingNotifications.size == 1) {
            // Only one sender left, remove group summary
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else {
            // Update summary notification
            showSummaryNotification()
        }

        Log.d(TAG, "Cleared mesh mention notifications")
    }

    /**
     * Clear all pending notifications
     */
    fun clearAllNotifications(removeConversationShortcuts: Boolean = false) {
        pendingNotifications.clear()
        notificationManager.cancelAll()
        if (removeConversationShortcuts) {
            val shortcutIDs = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            if (shortcutIDs.isNotEmpty()) {
                ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcutIDs)
            }
        }
        Log.d(TAG, "Cleared all notifications")
    }

    private fun notifySafely(notificationID: Int, notification: android.app.Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            notificationManager.notify(notificationID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was revoked: ${error.message}")
        }
    }

    /**
     * Get pending notification count for UI badging
     */
    fun getPendingNotificationCount(): Int {
        return pendingNotifications.values.sumOf { it.size }
    }

    /**
     * Get app background state for reactive read receipts
     */
    fun getAppBackgroundState(): Boolean {
        return isAppInBackground
    }

    /**
     * Get current private chat peer for reactive read receipts
     */
    fun getCurrentPrivateChatPeer(): String? {
        return currentPrivateChatPeer
    }

    /**
     * Get pending notifications for debugging
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("Notification Manager Debug Info:")
            appendLine("App in background: $isAppInBackground")
            appendLine("Current private chat peer: $currentPrivateChatPeer")
            appendLine("Pending DM notifications: ${pendingNotifications.size} senders")
            pendingNotifications.forEach { (peerID, notifications) ->
                appendLine("  $peerID: ${notifications.size} messages")
            }
        }
    }
}
