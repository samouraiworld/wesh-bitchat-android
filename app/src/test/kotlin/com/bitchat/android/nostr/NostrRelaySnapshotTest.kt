package com.bitchat.android.nostr

import com.bitchat.android.net.WeshNetworkGate
import com.bitchat.android.net.WeshNetworkPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.WebSocket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelaySnapshotTest {
    @Test
    fun `subscription tolerates last relay disconnecting between size and iteration`() {
        val manager = NostrRelayManager::class.java.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() +
            CoroutineExceptionHandler { _, error -> errors.add(error) })
        val connections = object : ConcurrentHashMap<String, WebSocket>() {
            override val size: Int
                get() {
                    val previous = super.size
                    // The real concurrent key view delegates size() to its backing map.
                    // Disconnect exactly after observing the last connection.
                    clear()
                    return previous
                }
        }
        NostrRelayManager::class.java.getDeclaredField("scope").apply {
            isAccessible = true
            (get(manager) as CoroutineScope).cancel()
            set(manager, scope)
        }
        NostrRelayManager::class.java.getDeclaredField("connections").apply {
            isAccessible = true
            set(manager, connections)
        }
        connections["wss://relay.example"] = mock()
        val closedGate = WeshNetworkPolicy.gate
        // The send path only runs with the Internet gate open.
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        try {
            manager.subscribe(NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
                id = "synthetic-subscription", handler = {})
            assertTrue("Subscription coroutine failed: $errors", errors.isEmpty())
        } finally {
            WeshNetworkPolicy.gate = closedGate
            scope.cancel()
            connections.clear()
        }
    }
}
