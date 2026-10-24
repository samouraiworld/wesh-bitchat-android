package com.bitchat.android.ui

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import android.os.Parcelable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.bitchat.android.R
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.WeshContactStore
import com.bitchat.android.ui.pave.PaveStringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.app.NotificationManager as AndroidNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class WeshNotificationPrivacyTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val context = LabelContext(app)
    private val nickname = "Synthetic Sender"
    private val secret = "Synthetic private text"

    @Before
    fun setUp() {
        app.getSharedPreferences(WeshPreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `private message notification hides sender and content by default`() {
        val manager = backgroundManager()
        manager.showPrivateMessageNotification("aaaabbbbccccdddd", nickname, secret)
        manager.showPrivateMessageNotification("1111222233334444", nickname, secret)

        val posted = postedNotifications()
        assertTrue(posted.isNotEmpty())
        posted.forEach { notification ->
            val text = flatten(notification.extras)
            assertFalse("sender leaked: $text", text.contains(nickname))
            assertFalse("content leaked: $text", text.contains(secret))
            assertNull(notification.shortcutId)
            assertNull(notification.locusId)
            assertTrue("notification may be bridged to a watch", notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
            assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
        }
        assertEquals(label(R.string.wesh_notification_new_message), posted.first().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }

    @Test
    fun `sender name appears only after opt in and content stays hidden`() {
        WeshPreferences(app).notificationShowsSender = true
        backgroundManager().showPrivateMessageNotification("aaaabbbbccccdddd", nickname, secret)

        val notification = postedNotifications().single()
        assertEquals(nickname, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(flatten(notification.extras).contains(secret))
        assertNull(notification.shortcutId)
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }

    @Test
    fun `opted-in sender name goes through the remote nickname rule`() {
        WeshPreferences(app).notificationShowsSender = true
        backgroundManager().showPrivateMessageNotification("aaaabbbbccccdddd", "\u202Eevil\u200Bname", secret)
        // An invisible character between letters makes the whole name unnamed, as in Pavé rows.
        assertEquals("Sans surnom", postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `opted-in sender name is a QR contact's local alias when one is set`() {
        val prefs = app.getSharedPreferences("synthetic-contacts-alias-notification", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = WeshContactStore(SecureIdentityStateManager(prefs, true),
            app.getSharedPreferences("synthetic-blocks-alias-notification", Context.MODE_PRIVATE))
        assertTrue(store.record(ByteArray(32) { 5 }, nickname))
        val id = store.contacts.value.single().conversationID
        assertTrue(store.setNote(id, "Lab fox", ""))
        WeshContactStore.installForTesting(store)
        try {
            WeshPreferences(app).notificationShowsSender = true
            backgroundManager().showPrivateMessageNotification(id, "Broadcast name", secret)
            assertEquals("Lab fox", postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        } finally {
            WeshContactStore.resetForTesting()
        }
    }

    @Test
    fun `a sender without a nickname is shown unnamed, not as a masked peer ID`() {
        WeshPreferences(app).notificationShowsSender = true
        backgroundManager().showPrivateMessageNotification("aaaabbbbcccc1234", null, secret)
        assertEquals("Sans surnom", postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `a sender named like an app label or a fingerprint is masked in the title`() {
        WeshPreferences(app).notificationShowsSender = true
        impostors.forEach { name ->
            systemNotifications().cancelAll()
            backgroundManager().showPrivateMessageNotification("aaaabbbbccccdddd", name, secret)
            assertEquals(name, "Sans surnom (réservé)", postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        }
    }

    @Test
    fun `a verified contact named like an app label or a fingerprint is masked in the notification`() {
        WeshPreferences(app).notificationShowsSender = true
        impostors.forEach { name ->
            systemNotifications().cancelAll()
            backgroundManager().showVerificationSuccessNotification(name, "aaaabbbbccccdddd")
            assertEquals(name, "${label(R.string.verify_success_body)} Sans surnom (réservé)",
                postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        }
    }

    @Test
    fun `public mention notification hides sender and content`() {
        backgroundManager().showMeshMentionNotification(nickname, secret, "aaaabbbbccccdddd")

        val notification = postedNotifications().single()
        val text = flatten(notification.extras)
        assertFalse(text.contains(nickname))
        assertFalse(text.contains(secret))
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
    }

    @Test
    fun `verification notification hides the contact nickname unless opted in`() {
        backgroundManager().showVerificationSuccessNotification(nickname, "aaaabbbbccccdddd")

        val notification = postedNotifications().single()
        assertFalse(flatten(notification.extras).contains(nickname))
        assertEquals(label(R.string.wesh_notification_verified_body), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertFalse(intent.hasExtra(NotificationManager.EXTRA_SENDER_NICKNAME))
        assertEquals("aaaabbbbccccdddd", intent.getStringExtra(NotificationManager.EXTRA_PEER_ID))
    }

    @Test
    fun `verification notification names the contact after opt in`() {
        WeshPreferences(app).notificationShowsSender = true
        backgroundManager().showVerificationSuccessNotification(nickname, "aaaabbbbccccdddd")
        assertEquals("${label(R.string.verify_success_body)} $nickname", postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    private fun backgroundManager() = NotificationManager(context, NotificationManagerCompat.from(context), isConversationMuted = { false },
        remoteName = PaveStringResources.remoteNameRule())
        .apply { setAppBackgroundState(true) }

    /** "You" in French, the unnamed label in capitals, a name ending like a short key fingerprint. */
    private val impostors = listOf("Vous", "NO NICKNAME", "Nora A1B2 C3D4")

    private fun systemNotifications() = app.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

    private fun postedNotifications(): List<Notification> = shadowOf(systemNotifications()).allNotifications

    private fun label(id: Int) = "label-$id"

    private fun flatten(value: Any?): String = when (value) {
        is Bundle -> value.keySet().joinToString(" ") { flatten(value.get(it)) }
        is Array<*> -> value.joinToString(" ") { flatten(it) }
        is Parcelable -> value.toString()
        else -> value?.toString().orEmpty()
    }

    /** App resources are not packaged for JVM tests; each string resolves to a stable label. */
    private class LabelContext(base: Context) : ContextWrapper(base) {
        @Suppress("DEPRECATION")
        private val labels = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "label-$id"
            override fun getString(id: Int): String = "label-$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String = (listOf("label-$id") + formatArgs).joinToString(" ")
            override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = "label-$id"
        }
        override fun getResources(): Resources = labels
        override fun getApplicationContext(): Context = this
    }
}
