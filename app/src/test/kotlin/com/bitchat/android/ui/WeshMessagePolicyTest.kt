package com.bitchat.android.ui

import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.ContactIdentityResolver
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async

class WeshMessagePolicyTest {
    private val key = ByteArray(32) { it.toByte() }
    private val replacement = ByteArray(32) { (it + 1).toByte() }
    private val verified = setOf(ContactIdentityResolver.fingerprintHex(key))

    @Test fun `public consent refuses private context changed draft or Bluetooth lost during alert`() {
        val request = WeshMessagePolicy.PublicSendRequest("synthetic public")
        assertTrue(request.canConfirm("public", "synthetic public", true))
        assertFalse(request.canConfirm("contact_synthetic", "synthetic private", true))
        assertFalse(request.canConfirm("public", "new synthetic draft", true))
        assertFalse(request.canConfirm("public", "synthetic public", false))
        assertEquals("synthetic public", request.content)
    }

    @Test fun `public text remains literal and cannot select a private or internet channel`() = runTest {
        val effects = mutableListOf<String>()
        assertTrue(WeshMessagePolicy.sendPublicText("/join synthetic", "Synthetic", "peer-local",
            store = { message ->
                assertFalse(message.isPrivate); assertNull(message.channel)
                assertEquals(DeliveryStatus.Sent, message.deliveryStatus); effects += "stored"
            },
            bluetoothSend = { content ->
                assertEquals("/join synthetic", content); effects += "bluetooth"; true
            }))
        assertEquals(listOf("bluetooth", "stored"), effects)
        effects.clear()
        assertFalse(WeshMessagePolicy.sendPublicText(" ", "Synthetic", "peer-local", { effects += "stored" }, { effects += "sent"; true }))
        assertTrue(effects.isEmpty())
    }

    @Test fun `refused public handoff preserves captured draft and creates no success echo`() = runTest {
        val drafts = WeshDraftStore()
        drafts.save(drafts.epoch, "public", "synthetic public")
        val capture = requireNotNull(drafts.capture("public"))
        var echoes = 0
        val accepted = WeshMessagePolicy.sendPublicText(capture.text, "Synthetic", "peer-local",
            store = { echoes++ }, bluetoothSend = { false })
        if (accepted) drafts.clearIfUnchanged(capture)
        assertFalse(accepted)
        assertEquals(0, echoes)
        assertEquals(capture.text, drafts.drafts.value["public"])
    }

    @Test fun `pending public handoff creates no echo and late success preserves a newer draft`() = runTest {
        val drafts = WeshDraftStore()
        drafts.save(drafts.epoch, "public", "synthetic original")
        val capture = requireNotNull(drafts.capture("public"))
        val awaitingHandoff = CompletableDeferred<Unit>()
        val acceptedHandoff = CompletableDeferred<Boolean>()
        var echoes = 0
        val send = async {
            WeshMessagePolicy.sendPublicText(capture.text, "Synthetic", "peer-local",
                store = { echoes++ }, bluetoothSend = {
                    awaitingHandoff.complete(Unit)
                    acceptedHandoff.await()
                })
        }
        awaitingHandoff.await()
        assertEquals(0, echoes)
        assertEquals(capture.text, drafts.drafts.value["public"])
        drafts.save(drafts.epoch, "public", "synthetic newer")
        acceptedHandoff.complete(true)
        assertTrue(send.await())
        drafts.clearIfUnchanged(capture)
        assertEquals(1, echoes)
        assertEquals("synthetic newer", drafts.drafts.value["public"])
    }

    @Test fun `revoked admission before final echo preserves draft even after a local handoff`() = runTest {
        val drafts = WeshDraftStore()
        drafts.save(drafts.epoch, "public", "synthetic public")
        val capture = requireNotNull(drafts.capture("public"))
        var allowed = true
        var echoes = 0
        var handoffs = 0
        val accepted = WeshMessagePolicy.sendPublicText(capture.text, "Synthetic", "peer-local",
            store = { echoes++ }, bluetoothSend = {
                handoffs++
                allowed = false
                true
            }, authorized = { allowed })
        if (accepted) drafts.clearIfUnchanged(capture)
        assertFalse(accepted)
        assertEquals(1, handoffs)
        assertEquals(0, echoes)
        assertEquals(capture.text, drafts.drafts.value["public"])
    }

    @Test fun `a parsed QR or unavailable session does not authorize private sending`() {
        assertFalse(WeshMessagePolicy.canSendPrivate(null, key, verified))
        assertFalse(WeshMessagePolicy.canSendPrivate(key, key, emptySet()))
        assertTrue(WeshMessagePolicy.canSendPrivate(key, key, verified))
    }
    @Test fun `changed authenticated identity removes outgoing eligibility`() {
        assertFalse(WeshMessagePolicy.canSendPrivate(replacement, key, verified))
        assertFalse(WeshMessagePolicy.canSendPrivate(replacement, replacement, verified))
        assertFalse(WeshMessagePolicy.sessionMatches(replacement, key.joinToString("") { "%02x".format(it) }))
    }
    @Test fun `transport handoff and partial receipt never claim full recipient receipt`() {
        assertEquals("En attente d’envoi", WeshMessagePolicy.deliveryLabel(null))
        assertEquals("Transmis · réception non confirmée", WeshMessagePolicy.deliveryLabel(DeliveryStatus.Sent))
        assertEquals("Réception partielle", WeshMessagePolicy.deliveryLabel(DeliveryStatus.PartiallyDelivered(1, 2)))
        assertEquals("Échec de l’envoi", WeshMessagePolicy.deliveryLabel(DeliveryStatus.Failed("synthetic")))
    }

    @Test fun `the public room shows no system notice unless it is our own message`() {
        fun public(sender: String, peer: String?) = com.bitchat.android.model.BitchatMessage(
            sender = sender, content = "synthetic", timestamp = java.util.Date(1), isRelay = false, senderPeerID = peer)
        val shows = { message: com.bitchat.android.model.BitchatMessage ->
            WeshMessagePolicy.showsInPublicRoom(message, myPeerID = "peer-local", isBlocked = { it == "peer-blocked" })
        }
        assertTrue(shows(public("Nora", "peer-remote")))
        assertTrue(shows(public("system", "peer-local")))
        assertFalse(shows(public("system", null)))
        assertFalse(shows(public("system", "peer-remote")))
        assertFalse(shows(public("Nora", "peer-blocked")))
        assertFalse(shows(public("Nora", "peer-remote").copy(isPrivate = true)))
        assertFalse(shows(public("Nora", "peer-remote").copy(channel = "#synthetic")))
    }
}
