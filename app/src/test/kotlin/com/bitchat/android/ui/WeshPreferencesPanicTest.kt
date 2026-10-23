package com.bitchat.android.ui

import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WeshPreferencesPanicTest {
    private class Storage {
        val memory = mutableMapOf<String, Any>("meeting_note" to "synthetic saved note",
            "public_muted" to true, "public_disclosure" to true)
        val disk = memory.toMutableMap()
        val prefs: SharedPreferences = mock()
        private val editor: SharedPreferences.Editor = mock()
        private val puts = mutableMapOf<String, Any>()
        private val removes = mutableSetOf<String>()
        var commitSucceeds = true
        var throwOnCommit = false
        var commits = 0
        var readbackOverride: String? = null
        val committedNotes = mutableListOf<String?>()
        var noteDisk: String? = null
        var noteMemory: String? = null
        var beforeNoteWrite: suspend () -> Unit = {}
        var afterNoteWrite: suspend () -> Unit = {}

        fun noteBackend(): WeshMeetingNoteBackend = object : WeshMeetingNoteBackend {
            override suspend fun load(): String? = readbackOverride ?: noteDisk
            override suspend fun write(text: String) {
                beforeNoteWrite()
                commits++
                noteMemory = text
                committedNotes += text
                if (throwOnCommit) error("synthetic note commit failure")
                check(commitSucceeds) { "synthetic uncertain note commit" }
                noteDisk = text
                afterNoteWrite()
            }
        }

        init {
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer { memory[it.getArgument<String>(0)] as String? ?: it.getArgument<String?>(1) }
            whenever(prefs.contains(any())).thenAnswer { memory.containsKey(it.getArgument<String>(0)) }
            whenever(prefs.getBoolean(any(), any())).thenAnswer { memory[it.getArgument<String>(0)] as Boolean? ?: false }
            whenever(prefs.all).thenAnswer { memory.toMap() }
            whenever(prefs.edit()).thenReturn(editor)
            whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                puts[it.getArgument(0)] = it.getArgument<String>(1); editor
            }
            whenever(editor.remove(any())).thenAnswer { removes += it.getArgument<String>(0); editor }
            whenever(editor.putBoolean(any(), any())).thenAnswer {
                puts[it.getArgument(0)] = it.getArgument<Boolean>(1); editor
            }
            whenever(editor.commit()).thenAnswer {
                commits++
                publishMemory()
                committedNotes += memory["meeting_note"] as String?
                if (throwOnCommit) error("synthetic commit failure")
                if (commitSucceeds) persistMemory()
                commitSucceeds
            }
            doAnswer { publishMemory(); persistMemory(); null }.whenever(editor).apply()
        }

        private fun publishMemory() {
            removes.forEach(memory::remove)
            removes.clear()
            memory.putAll(puts)
            puts.clear()
        }
        private fun persistMemory() { disk.clear(); disk.putAll(memory) }
        fun simulateRestart() { memory.clear(); memory.putAll(disk) }
    }

    private fun TestScope.newPreferences(storage: Storage) =
        WeshPreferences(storage.prefs, StandardTestDispatcher(testScheduler), meetingBackendFactory = storage::noteBackend)

    // Save/fencing controls start from a synthetic already-encrypted note.
    // Legacy plaintext migration is exercised independently with the real backend.
    private suspend fun TestScope.newLoadedPreferences(storage: Storage): WeshPreferences {
        storage.memory.remove("meeting_note")
        storage.disk.remove("meeting_note")
        if (storage.noteDisk == null) storage.noteDisk = "synthetic saved note"
        return newPreferences(storage).also { assertTrue(it.loadMeetingNote()) }
    }

    @Test fun `failed note commit changed in RAM still requires a new successful commit on retry`() = runTest {
        val storage = Storage().apply { commitSucceeds = false }
        val preferences = newLoadedPreferences(storage)
        val epoch = preferences.resetEpoch.value
        assertTrue(preferences.editMeetingDraft("synthetic edited note", epoch))
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals("synthetic edited note", preferences.meetingDraft.value)
        assertEquals("synthetic edited note", storage.noteMemory)
        assertEquals("synthetic saved note", storage.noteDisk)
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals(2, storage.commits)
        storage.commitSucceeds = true
        assertTrue(preferences.saveMeetingDraft(epoch))
        assertEquals(3, storage.commits)
        storage.simulateRestart()
        assertEquals("synthetic edited note", newLoadedPreferences(storage).meetingNote)
    }

    @Test fun `note commit exception retains the edited draft and permits an explicit retry`() = runTest {
        val storage = Storage().apply { throwOnCommit = true }
        val preferences = newLoadedPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic edited note", epoch)
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals("synthetic edited note", preferences.meetingDraft.value)
        assertEquals("synthetic saved note", storage.noteDisk)
        storage.throwOnCommit = false
        assertTrue(preferences.saveMeetingDraft(epoch))
        assertEquals(2, storage.commits)
        assertEquals("synthetic edited note", storage.noteDisk)
    }

    @Test fun `note commit success without exact readback remains unconfirmed until retry`() = runTest {
        val storage = Storage()
        val preferences = newLoadedPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic edited note", epoch)
        storage.readbackOverride = "synthetic divergent note"
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals("synthetic edited note", preferences.meetingDraft.value)
        storage.readbackOverride = null
        assertTrue(preferences.saveMeetingDraft(epoch))
        assertEquals(2, storage.commits)
    }

    @Test fun `serialized saves preserve newer edits and do not confirm an older draft`() = runTest {
        val storage = Storage()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peakActive = 0
        var attempts = 0
        storage.beforeNoteWrite = {
            active++
            peakActive = maxOf(active, peakActive)
            if (attempts++ == 0) { entered.complete(Unit); release.await() }
        }
        storage.afterNoteWrite = { active-- }
        val preferences = newLoadedPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic first note", epoch)
        val first = async { preferences.saveMeetingDraft(epoch) }
        entered.await()
        preferences.editMeetingDraft("synthetic newer note", epoch)
        val second = async(start = CoroutineStart.UNDISPATCHED) { preferences.saveMeetingDraft(epoch) }
        runCurrent()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertFalse(first.await())
        assertTrue(second.await())
        assertEquals(1, peakActive)
        assertEquals(listOf("synthetic first note", "synthetic newer note"), storage.committedNotes)
        assertEquals("synthetic newer note", preferences.meetingDraft.value)
        assertEquals("synthetic newer note", storage.noteDisk)
    }

    @Test fun `panic fences immediately while commit is suspended and refuses queued old writes`() = runTest {
        val storage = Storage()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        storage.beforeNoteWrite = {
            if (attempts++ == 0) { entered.complete(Unit); release.await() }
        }
        val preferences = newLoadedPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic in flight", epoch)
        val first = async { preferences.saveMeetingDraft(epoch) }
        entered.await()
        preferences.editMeetingDraft("synthetic queued", epoch)
        val queued = async(start = CoroutineStart.UNDISPATCHED) { preferences.saveMeetingDraft(epoch) }
        runCurrent()
        preferences.fenceForPanic()
        assertEquals("", preferences.meetingDraft.value)
        assertFalse(preferences.editMeetingDraft("synthetic stale callback", epoch))
        release.complete(Unit)
        assertFalse(first.await())
        assertFalse(queued.await())
        assertEquals(listOf("synthetic in flight"), storage.committedNotes)
        assertFalse(preferences.saveMeetingDraft(epoch))
        assertEquals(1, storage.commits)
    }

    @Test fun `panic before IO dispatch refuses a captured save without writing its text`() = runTest {
        val storage = Storage()
        val preferences = newLoadedPreferences(storage)
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic queued", epoch)
        val save = async(start = CoroutineStart.UNDISPATCHED) { preferences.saveMeetingDraft(epoch) }
        preferences.fenceForPanic()
        assertFalse(save.await())
        assertEquals(0, storage.commits)
        assertTrue(storage.committedNotes.isEmpty())
        assertEquals("synthetic saved note", storage.noteDisk)
    }

    @Test fun `invalidation after IO commit but before caller resumes cannot report note saved`() = runTest {
        val storage = Storage()
        val caller = StandardTestDispatcher(testScheduler, name = "synthetic-caller")
        val io = StandardTestDispatcher(testScheduler, name = "synthetic-io")
        lateinit var preferences: WeshPreferences
        storage.memory.remove("meeting_note")
        storage.disk.remove("meeting_note")
        storage.noteDisk = "synthetic saved note"
        storage.afterNoteWrite = { launch(caller) { preferences.fenceForPanic() }; Unit }
        preferences = WeshPreferences(storage.prefs, io, meetingBackendFactory = storage::noteBackend)
        assertTrue(preferences.loadMeetingNote())
        val epoch = preferences.resetEpoch.value
        preferences.editMeetingDraft("synthetic edited note", epoch)
        val save = async(caller) { preferences.saveMeetingDraft(epoch) }
        advanceUntilIdle()
        assertFalse(save.await())
        assertEquals(1, storage.commits)
        assertEquals("synthetic edited note", storage.noteDisk)
        assertEquals("", preferences.meetingDraft.value)
    }

    @Test fun `stale UI callbacks cannot rewrite note disclosure or mute while panic remains fenced`() = runTest {
        val storage = Storage()
        val preferences = newLoadedPreferences(storage)
        preferences.editMeetingDraft("synthetic unsaved note", preferences.resetEpoch.value)
        preferences.fenceForPanic()
        assertEquals("", preferences.meetingDraft.value)
        val before = storage.disk.toMap()
        assertFalse(preferences.editMeetingDraft("synthetic stale note", preferences.resetEpoch.value))
        assertFalse(preferences.saveMeetingDraft(preferences.resetEpoch.value))
        preferences.publicMuted = false
        preferences.publicDisclosureAccepted = false
        preferences.readReceiptsEnabled = false
        assertEquals("", preferences.meetingDraft.value)
        assertEquals(before, storage.memory)
        assertEquals(before, storage.disk)
        assertEquals("synthetic saved note", storage.noteDisk)
        assertEquals(0, storage.commits)
    }
}
