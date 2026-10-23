package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.bitchat.android.services.InMemoryConversationStorageCipher
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A preference stored with another type must never crash the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshPreferencesTypeTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun `wrongly typed values read as defaults`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences(WeshPreferences.FILE, Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt("meeting_note", 3)
            .putString("public_muted", "true")
            .putLong("public_disclosure", 1L)
            .putString("notification_sender", "yes")
            .putString("read_receipts", "no")
            .putBoolean("appearance", true)
            .commit()

        val preferences = WeshPreferences(prefs, meetingBackendFactory = {
            EncryptedWeshMeetingNoteBackend(directory.root, InMemoryConversationStorageCipher())
        })
        assertEquals("", preferences.meetingDraft.value)
        assertEquals("", preferences.meetingNote)
        assertFalse(preferences.publicMuted)
        assertFalse(preferences.publicDisclosureAccepted)
        assertFalse(preferences.notificationShowsSender)
        assertFalse(WeshPreferences.notificationShowsSender(app))
        assertTrue(preferences.readReceiptsEnabled)
        assertTrue(WeshPreferences.readReceiptsEnabled(app))
        preferences.readReceiptsEnabled = false
        assertFalse(preferences.readReceiptsEnabled)
        assertFalse(WeshPreferences.readReceiptsEnabled(app))
        assertEquals("Nuit", preferences.appearance)
    }
}
