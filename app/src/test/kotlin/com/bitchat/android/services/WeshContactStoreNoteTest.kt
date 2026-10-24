package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshContactStoreNoteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val identityPrefs get() = app.getSharedPreferences("synthetic-contacts-notes", Context.MODE_PRIVATE)
    private val blocks get() = app.getSharedPreferences("synthetic-blocks-notes", Context.MODE_PRIVATE)
    private val key = ByteArray(32) { 9 }
    private val storageKey = "wesh_qr_contacts_v1"

    @Before
    fun setUp() {
        identityPrefs.edit().clear().commit()
        blocks.edit().clear().commit()
    }

    private fun store() = WeshContactStore(SecureIdentityStateManager(identityPrefs, true), blocks)
    private fun stored(): String = identityPrefs.getString(storageKey, null).orEmpty()

    private fun enrolledWithNote(): Pair<WeshContactStore, String> {
        val store = store()
        assertTrue(store.record(key, "Synthetic Fox"))
        val id = store.contacts.value.single().conversationID
        assertTrue(store.setNote(id, "Lab fox", "Met at the synthetic fair.\nLikes tea."))
        return store to id
    }

    @Test
    fun `alias and memo survive a reload`() {
        enrolledWithNote()
        val reloaded = store().contacts.value.single()
        assertEquals("Synthetic Fox", reloaded.nickname)
        assertEquals("Lab fox", reloaded.alias)
        assertEquals("Met at the synthetic fair.\nLikes tea.", reloaded.memo)
    }

    @Test
    fun `contacts stored before alias and memo existed still load`() {
        val raw = """[{"noise":"${"09".repeat(32)}","nickname":"Synthetic Fox"}]"""
        identityPrefs.edit().putString(storageKey, raw).commit()
        val store = store()
        assertFalse(store.loadFailed.value)
        val contact = store.contacts.value.single()
        assertNull(contact.alias)
        assertNull(contact.memo)
        assertEquals("Synthetic Fox", contact.displayName)
    }

    @Test
    fun `the alias is shown instead of the QR name and a blank alias falls back to it`() {
        val (store, id) = enrolledWithNote()
        assertEquals("Lab fox", store.contacts.value.single().displayName)

        assertTrue(store.setNote(id, " \u200B ", ""))
        val cleared = store.contacts.value.single()
        assertNull(cleared.alias)
        assertNull(cleared.memo)
        assertEquals("Synthetic Fox", cleared.displayName)
        assertFalse(stored().contains("\"alias\""))
    }

    @Test
    fun `alias is bounded like a nickname and stripped of control characters`() {
        val (store, id) = enrolledWithNote()
        assertTrue(store.setNote(id, "Ab\u0007c\u202Ed" + "x".repeat(40), ""))
        val alias = store.contacts.value.single().alias!!
        assertEquals(AppConstants.UI.MAX_NICKNAME_LENGTH, alias.length)
        assertTrue(alias.startsWith("Abcdx"))
    }

    @Test
    fun `memo keeps line breaks, drops other control characters and is bounded`() {
        val (store, id) = enrolledWithNote()
        assertTrue(store.setNote(id, "", "one\ntwo\u0000\u001B" + "y".repeat(600)))
        val memo = store.contacts.value.single().memo!!
        assertEquals(WeshContactStore.MAX_MEMO_LENGTH, memo.length)
        assertTrue(memo.startsWith("one\ntwoy"))
    }

    @Test
    fun `re-enrolling the same key by QR keeps alias and memo`() {
        val (store, _) = enrolledWithNote()
        assertTrue(store.enroll(key, "Synthetic Fox 2", null, null, emptySet()) != null)
        val contact = store().contacts.value.single()
        assertEquals("Synthetic Fox 2", contact.nickname)
        assertEquals("Lab fox", contact.alias)
        assertEquals("Met at the synthetic fair.\nLikes tea.", contact.memo)
    }

    @Test
    fun `removing the contact drops alias and memo`() {
        val (store, id) = enrolledWithNote()
        assertTrue(store.remove(id))
        assertFalse(stored().contains("Lab fox"))
        assertFalse(stored().contains("Likes tea"))
        assertTrue(store.record(key, "Synthetic Fox"))
        assertNull(store.contacts.value.single().alias)
        assertNull(store.contacts.value.single().memo)
    }

    @Test
    fun `wiping drops alias and memo`() {
        val (store, _) = enrolledWithNote()
        store.clear()
        assertFalse(stored().contains("Lab fox"))
        assertFalse(stored().contains("Likes tea"))
        assertTrue(store().contacts.value.isEmpty())
    }

    @Test
    fun `a note is refused, writing nothing, once the contacts could not be read`() {
        val (store, id) = enrolledWithNote()
        identityPrefs.edit().putString(storageKey, "{not json").commit()
        assertFalse(store.retryLoad())
        // The last readable list is still shown, but nothing may overwrite what could not be read.
        assertEquals(id, store.contacts.value.single().conversationID)
        assertFalse(store.setNote(id, "Synthetic", "Synthetic memo"))
        assertEquals("{not json", stored())
    }

    @Test
    fun `a failed durable write neither reports success nor publishes the note`() {
        val raw = """[{"noise":"${"09".repeat(32)}","nickname":"Synthetic Fox"}]"""
        val preferences = mock<android.content.SharedPreferences>()
        val editor = mock<android.content.SharedPreferences.Editor>()
        whenever(preferences.getString(storageKey, null)).thenReturn(raw)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
        whenever(editor.commit()).thenReturn(false)
        val store = WeshContactStore(SecureIdentityStateManager(preferences, true), blocks)
        val original = store.contacts.value.single()

        assertFalse(store.setNote(original.conversationID, "Lab fox", "Synthetic memo"))

        assertEquals(original, store.contacts.value.single())
        assertNull(store.contacts.value.single().alias)
        assertNull(store.contacts.value.single().memo)
        verify(editor).commit()
    }
}
