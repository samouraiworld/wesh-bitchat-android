package com.bitchat.android.ui

import com.bitchat.android.mesh.BoundSendOutcome
import com.bitchat.android.mesh.admitWeshPublicMessageOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WeshPublicSendOutcomeTest {
    @Test fun `typed transport refusal preserves draft with no success record or repeated handoff`() = runTest {
        val drafts = WeshDraftStore()
        drafts.save(drafts.epoch, "public", "synthetic public")
        val captured = requireNotNull(drafts.capture("public"))
        var handoffs = 0
        var syncRecords = 0
        var echoes = 0
        val outcome = WeshMessagePolicy.sendPublicTextOutcome(captured.text, "Synthetic", "peer-local",
            store = { echoes++ }, bluetoothSend = { text ->
                admitWeshPublicMessageOutcome(text, { true }, { 1L }, { "packet" },
                    handoff = { _, _ -> handoffs++; BoundSendOutcome.NOT_SENT },
                    recordAccepted = { syncRecords++ })
            })
        if (outcome == BoundSendOutcome.SENT) drafts.clearIfUnchanged(captured)
        assertEquals(BoundSendOutcome.NOT_SENT, outcome)
        assertEquals(1, handoffs)
        assertEquals(0, syncRecords)
        assertEquals(0, echoes)
        assertEquals(captured.text, drafts.drafts.value["public"])
    }

    @Test fun `uncertain or unknown transport outcome never becomes a rejection or an echo`() = runTest {
        for (transportOutcome in listOf(BoundSendOutcome.UNCONFIRMED, BoundSendOutcome.AUTH_FAILED)) {
            var handoffs = 0
            var syncRecords = 0
            var echoes = 0
            val result = WeshMessagePolicy.sendPublicTextOutcome("synthetic public", "Synthetic", "peer-local",
                store = { echoes++ }, bluetoothSend = { text ->
                    admitWeshPublicMessageOutcome(text, { true }, { 1L }, { "packet" },
                        handoff = { _, _ -> handoffs++; transportOutcome },
                        recordAccepted = { syncRecords++ })
                })
            assertEquals(BoundSendOutcome.UNCONFIRMED, result)
            assertEquals(1, handoffs)
            assertEquals(0, syncRecords)
            assertEquals(0, echoes)
        }
    }

    @Test fun `lifecycle lost after concrete acceptance stays uncertain without recording or resending`() = runTest {
        var generation = 1L
        var radioEffects = 0
        var syncRecords = 0
        var echoes = 0
        val result = WeshMessagePolicy.sendPublicTextOutcome("synthetic public", "Synthetic", "peer-local",
            store = { echoes++ }, bluetoothSend = { text ->
                admitWeshPublicMessageOutcome(text, { true }, { generation }, { "packet" },
                    handoff = { _, authorized ->
                        assertTrue(authorized())
                        radioEffects++
                        generation++
                        BoundSendOutcome.SENT
                    }, recordAccepted = { syncRecords++ })
            })
        assertEquals(BoundSendOutcome.UNCONFIRMED, result)
        assertEquals(1, radioEffects)
        assertEquals(0, syncRecords)
        assertEquals(0, echoes)
    }

    @Test fun `policy fence lost after service record does not create a local echo`() = runTest {
        var allowed = true
        var radioEffects = 0
        var syncRecords = 0
        var echoes = 0
        val result = WeshMessagePolicy.sendPublicTextOutcome("synthetic public", "Synthetic", "peer-local",
            store = { echoes++ }, bluetoothSend = { text ->
                val handoff = admitWeshPublicMessageOutcome(text, { allowed }, { 1L }, { "packet" },
                    handoff = { _, _ -> radioEffects++; BoundSendOutcome.SENT },
                    recordAccepted = { syncRecords++ })
                allowed = false
                handoff
            }, authorized = { allowed })
        assertEquals(BoundSendOutcome.UNCONFIRMED, result)
        assertEquals(1, radioEffects)
        assertEquals(1, syncRecords)
        assertEquals(0, echoes)
    }

    @Test fun `authorized confirmed handoff records once and clears only its captured draft`() = runTest {
        val drafts = WeshDraftStore()
        drafts.save(drafts.epoch, "public", "synthetic public")
        val captured = requireNotNull(drafts.capture("public"))
        val effects = mutableListOf<String>()
        val packet = Any()
        val result = WeshMessagePolicy.sendPublicTextOutcome(captured.text, "Synthetic", "peer-local",
            store = { effects += "echo" }, bluetoothSend = { text ->
                admitWeshPublicMessageOutcome(text, { true }, { 1L }, { packet },
                    handoff = { candidate, authorized ->
                        assertSame(packet, candidate)
                        assertTrue(authorized())
                        effects += "radio"
                        BoundSendOutcome.SENT
                    }, recordAccepted = { candidate -> assertSame(packet, candidate); effects += "sync" })
            })
        if (result == BoundSendOutcome.SENT) drafts.clearIfUnchanged(captured)
        assertEquals(BoundSendOutcome.SENT, result)
        assertEquals(listOf("radio", "sync", "echo"), effects)
        assertEquals("", drafts.drafts.value["public"].orEmpty())
    }

    @Test fun `failure before handoff is safely not sent but an exception after handoff is uncertain`() = runTest {
        var handoffs = 0
        val before = admitWeshPublicMessageOutcome<String>("synthetic public", { true }, { 1L },
            prepare = { error("synthetic preparation failure") },
            handoff = { _, _ -> handoffs++; BoundSendOutcome.SENT }, recordAccepted = {})
        assertEquals(BoundSendOutcome.NOT_SENT, before)
        assertEquals(0, handoffs)
        val after = admitWeshPublicMessageOutcome("synthetic public", { true }, { 1L }, { "packet" },
            handoff = { _, _ -> handoffs++; error("synthetic callback failure") }, recordAccepted = {})
        assertEquals(BoundSendOutcome.UNCONFIRMED, after)
        assertEquals(1, handoffs)
    }

    @Test fun `failure during recording after radio completion remains uncertain`() = runTest {
        var handoffs = 0
        val result = admitWeshPublicMessageOutcome("synthetic public", { true }, { 1L }, { "packet" },
            handoff = { _, _ -> handoffs++; BoundSendOutcome.SENT },
            recordAccepted = { error("synthetic record failure") })
        assertEquals(BoundSendOutcome.UNCONFIRMED, result)
        assertEquals(1, handoffs)
    }

    @Test fun `revocation before preparation performs no handoff and cancellation remains cancellation`() = runTest {
        var effects = 0
        val result = admitWeshPublicMessageOutcome("synthetic public", { false }, { 1L },
            prepare = { effects++; "packet" }, handoff = { _, _ -> effects++; BoundSendOutcome.SENT },
            recordAccepted = { effects++ })
        assertEquals(BoundSendOutcome.NOT_SENT, result)
        assertEquals(0, effects)
        try {
            admitWeshPublicMessageOutcome("synthetic public", { true }, { 1L }, { "packet" },
                handoff = { _, _ -> throw CancellationException("synthetic cancellation") }, recordAccepted = { effects++ })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, effects)
        }
    }
}
