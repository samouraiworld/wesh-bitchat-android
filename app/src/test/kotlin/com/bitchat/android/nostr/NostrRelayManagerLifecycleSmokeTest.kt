package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerLifecycleSmokeTest {
    @Test
    fun `Internet mode off cannot activate relay connections`() {
        com.bitchat.android.net.WeshNetworkPolicy.gate = com.bitchat.android.net.WeshNetworkGate { false }
        val manager = NostrRelayManager.shared
        manager.disconnect()
        manager.connect()
        assertFalse(manager.isConnected.value)
        assertTrue(manager.getRelayStatuses().none { it.isConnected })
        manager.disconnect()
    }

    @Test
    fun `only the pinned relays are configured and panic leaves them off`() {
        val manager = NostrRelayManager.shared
        assertEquals(
            listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://relay.snort.social"),
            manager.getRelayStatuses().map { it.url }
        )
        manager.clearAllOnPanic()
        assertFalse(manager.isConnectionDesired())
        manager.resetAllConnections()
        assertFalse(manager.isConnectionDesired())
    }

    @Test
    fun `owner teardown is synchronous and preserves other subscription owners`() {
        val manager = NostrRelayManager.shared
        manager.disconnect()
        manager.clearAllSubscriptions()
        val filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE))

        manager.subscribe(
            filter = filter,
            id = "background-contract",
            handler = {},
            targetRelayUrls = emptyList(),
            owner = NostrRelayManager.OWNER_BACKGROUND
        )
        manager.subscribe(
            filter = filter,
            id = "ui-contract",
            handler = {},
            targetRelayUrls = emptyList(),
            owner = "test-ui"
        )

        manager.unsubscribeOwner("test-ui")

        assertEquals(setOf("background-contract"), manager.getActiveSubscriptions().keys)
        manager.clearAllSubscriptions()
    }

    @Test
    fun `disconnected manager maintains subscription and empty publish invariants locally`() {
        val manager = NostrRelayManager.shared
        manager.disconnect()
        manager.clearAllSubscriptions()

        val id = manager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = "local-contract",
            handler = {},
            targetRelayUrls = emptyList()
        )

        assertEquals("local-contract", id)
        assertEquals(1, manager.getActiveSubscriptionCount())
        assertTrue(manager.getActiveSubscriptions().containsKey(id))
        assertTrue(manager.validateSubscriptionConsistency().isConsistent)
        manager.sendEvent(signedEvent(), relayUrls = emptyList())
        manager.retryConnection("wss://not-configured.example")

        manager.unsubscribe(id)
        assertEquals(0, manager.getActiveSubscriptionCount())
        assertFalse(manager.isConnected.value)
        assertTrue(manager.getRelayStatuses().none { it.isConnected })

        manager.disconnect()
        assertFalse(manager.isConnected.value)
    }

    @Test
    fun `OK rejection logs the rejecting relay URL and reason`() {
        org.robolectric.shadows.ShadowLog.clear()
        val manager = NostrRelayManager.shared
        val handleMessage = NostrRelayManager::class.java.getDeclaredMethod(
            "handleMessage",
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
        val eventId = "a".repeat(64)
        NostrRelayManager.registerPendingGiftWrap(eventId)

        handleMessage.invoke(
            manager,
            """["OK","$eventId",false,"rate-limited: you are noting too much"]""",
            "wss://relay.damus.io"
        )

        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("NostrRelayManager")
        assertTrue(
            logs.any {
                it.type == android.util.Log.WARN &&
                    "wss://relay.damus.io" in it.msg &&
                    "rate-limited: you are noting too much" in it.msg
            }
        )
    }

    private fun signedEvent(): NostrEvent {
        val privateKey = "0".repeat(63) + "1"
        return NostrEvent(
            pubkey = NostrCrypto.derivePublicKey(privateKey),
            createdAt = 1,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = "local"
        ).sign(privateKey)
    }
}
