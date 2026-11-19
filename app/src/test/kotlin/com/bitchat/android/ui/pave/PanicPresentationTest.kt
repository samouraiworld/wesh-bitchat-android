package com.bitchat.android.ui.pave

import android.app.Application
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.startup.InterruptedPanicRecovery
import com.bitchat.android.startup.WeshRecoveryGate
import com.bitchat.android.ui.EncryptedWeshDraftBackend
import com.bitchat.android.ui.WeshDraftStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owned marker and RAM recovery objects only; no runtime, platform service or application graph. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PanicPresentationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `the real presentation selector preserves all eight priorities`() {
        data class Case(val progress: Boolean, val recovery: Boolean, val erased: Boolean, val expected: PavePanicPresentation)
        val cases = listOf(
            Case(false, false, false, PavePanicPresentation.Content),
            Case(false, false, true, PavePanicPresentation.Erased),
            Case(false, true, false, PavePanicPresentation.RecoveryRequired),
            Case(false, true, true, PavePanicPresentation.RecoveryRequired),
            Case(true, false, false, PavePanicPresentation.Erasing),
            Case(true, false, true, PavePanicPresentation.Erasing),
            Case(true, true, false, PavePanicPresentation.Erasing),
            Case(true, true, true, PavePanicPresentation.Erasing)
        )
        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, pavePanicPresentation(case.progress, case.recovery, case.erased))
        }
    }

    @Test fun `pending checked intent keeps recovery above a latent erased flag`() = runTest {
        val f = markerFixture(this)
        assertEquals(PavePanicPresentation.Erasing, pavePanicPresentation(true, f.gate.recoveryRequired.value, true))
        f.assertLocked()

        val coldGate = WeshRecoveryGate()
        assertTrue(EncryptedWeshDraftBackend.hasPendingWipe(f.root))
        coldGate.requireRecovery()
        assertFalse(coldGate.allowsTransport(prepared = true))
        assertEquals(PavePanicPresentation.RecoveryRequired, pavePanicPresentation(false, coldGate.recoveryRequired.value, true))
    }

    @Test fun `Android acceptance remains requested and repeated requests cannot expose success`() = runTest {
        val f = markerFixture(this)
        val recovery = InterruptedPanicRecovery(backgroundScope)
        val response = CompletableDeferred<Boolean>()
        var requests = 0
        recovery.request { requests++; response.await() }
        try {
            runCurrent()
            assertEquals(InterruptedPanicRecovery.State.Running, recovery.state.value)
            recovery.request { requests++; true }
            runCurrent()
            assertEquals(1, requests)
            f.assertLocked()
            response.complete(true)
            runCurrent()
            assertEquals(InterruptedPanicRecovery.State.Requested, recovery.state.value)
            recovery.request { requests++; true }
            runCurrent()
            assertEquals(1, requests)
            f.assertLocked()
        } finally {
            response.complete(false)
            runCurrent()
        }
    }

    @Test fun `Android refusal and exception remain locked through explicit accepted retry`() = runTest {
        val f = markerFixture(this)
        val recovery = InterruptedPanicRecovery(backgroundScope)
        var requests = 0
        recovery.request { requests++; false }
        runCurrent()
        assertEquals(InterruptedPanicRecovery.State.Failed, recovery.state.value)
        f.assertLocked()

        recovery.request { requests++; error("Synthetic reset refusal") }
        runCurrent()
        assertEquals(InterruptedPanicRecovery.State.Failed, recovery.state.value)
        f.assertLocked()

        recovery.request { requests++; true }
        runCurrent()
        assertEquals(3, requests)
        assertEquals(InterruptedPanicRecovery.State.Requested, recovery.state.value)
        f.assertLocked()
    }

    @Test fun `fresh RAM gate permits ordinary content without a panic intent`() {
        val gate = WeshRecoveryGate()
        val root = directory.newFolder()
        assertFalse(EncryptedWeshDraftBackend.hasPendingWipe(root))
        assertTrue(gate.allowsTransport(prepared = true))
        assertEquals(PavePanicPresentation.Content, pavePanicPresentation(false, gate.recoveryRequired.value, false))
    }

    private suspend fun markerFixture(scope: TestScope): MarkerFixture {
        val fixture = MarkerFixture(directory.newFolder(), scope)
        fixture.gate.requireRecovery()
        fixture.store.fenceForPanic()
        assertTrue("the real store must verify its owned intent marker", fixture.store.establishPanicIntent(fixture.store.epoch))
        fixture.assertLocked()
        return fixture
    }

    private class MarkerFixture(val root: File, scope: TestScope) {
        val gate = WeshRecoveryGate()
        private val opaque = byteArrayOf(17, 31, 47, 61)
        private val ciphertext = File(root, "wesh-drafts-v1.bin").apply { writeBytes(opaque) }
        private var cipherCalls = 0
        private val cipher = object : ConversationStorageCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                cipherCalls++
                error("Unexpected synthetic encrypt")
            }
            override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
                cipherCalls++
                error("Unexpected synthetic decrypt")
            }
            override fun destroyKey() { cipherCalls++ }
        }
        private val backend = EncryptedWeshDraftBackend(root, cipher)
        val store = WeshDraftStore(backend, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))

        fun assertLocked() {
            assertTrue(gate.recoveryRequired.value)
            assertFalse(gate.allowsTransport(prepared = true))
            assertTrue(EncryptedWeshDraftBackend.hasPendingWipe(root))
            assertEquals(WeshDraftStore.Persistence.WipeRequired, store.persistence.value)
            assertFalse(store.save(store.epoch, "public", "Synthetic refused edit"))
            assertArrayEquals(opaque, ciphertext.readBytes())
            assertEquals(0, cipherCalls)
            assertEquals(PavePanicPresentation.RecoveryRequired, pavePanicPresentation(false, gate.recoveryRequired.value, true))
        }
    }
}
