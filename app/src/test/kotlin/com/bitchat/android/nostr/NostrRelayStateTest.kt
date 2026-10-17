package com.bitchat.android.nostr

import com.bitchat.android.net.NostrRelayControl
import com.bitchat.android.net.WeshNetworkGate
import com.bitchat.android.net.WeshNetworkPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrRelayStateTest {
    private val manager = NostrRelayManager.shared
    private val originalOpen = manager.openWebSocket
    private val originalGate = WeshNetworkPolicy.gate

    @After fun restore() {
        manager.openWebSocket = originalOpen
        manager.disconnect()
        WeshNetworkPolicy.gate = originalGate
    }

    @Test
    fun `a relay that connects is published to both connected counts`() = runTest {
        manager.disconnect()
        val fromRelays = mutableListOf<Int>()
        val fromControl = mutableListOf<Int>()
        val control = NostrRelayControl(RuntimeEnvironment.getApplication())
        // The Pave screen counts from the relays flow; the Internet state machine from connectedCount.
        val a = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.connectedCount.collect { fromRelays += it }
        }
        val b = launch(UnconfinedTestDispatcher(testScheduler)) {
            control.connectedCount.collect { fromControl += it }
        }

        manager.updateRelayStatus("wss://relay.primal.net", isConnected = true)
        // A status change that leaves the count unchanged is not re-emitted.
        manager.updateRelayStatus("wss://relay.primal.net", isConnected = true)
        manager.updateRelayStatus("wss://relay.primal.net", isConnected = false)
        a.cancel()
        b.cancel()

        assertEquals(listOf(0, 1, 0), fromRelays)
        assertEquals(listOf(0, 1, 0), fromControl)
    }

    @Test
    fun `two connects at once open a single socket per relay`() {
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        manager.disconnect()
        val opened = ConcurrentHashMap<String, AtomicInteger>()
        val release = CountDownLatch(1)
        manager.openWebSocket = { request, _ ->
            opened.computeIfAbsent(request.url.host) { AtomicInteger() }.incrementAndGet()
            // Hold the first attempt open, as a socket still being created would be.
            release.await(5, TimeUnit.SECONDS)
            IdleWebSocket(request)
        }

        // Tor ready and the Internet toggle both ask for relays at the same moment.
        manager.connect()
        val deadline = System.currentTimeMillis() + 5_000
        while (opened.size < NostrRelayManager.WESH_PINNED_RELAYS.size && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        manager.connect()
        Thread.sleep(500)
        release.countDown()

        assertTrue(opened.isNotEmpty())
        assertEquals(
            NostrRelayManager.WESH_PINNED_RELAYS.associate { it.removePrefix("wss://") to 1 },
            opened.mapValues { it.value.get() }
        )
    }

    @Test
    fun `a connect that arrives while an abandoned attempt finishes is not lost`() {
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        manager.disconnect()
        val relays = NostrRelayManager.WESH_PINNED_RELAYS.size
        val opened = ConcurrentHashMap<String, AtomicInteger>()
        val release = CountDownLatch(1)
        val inClose = CountDownLatch(relays)
        val firstGeneration = ConcurrentHashMap.newKeySet<WebSocket>()
        manager.openWebSocket = { request, _ ->
            val count = opened.computeIfAbsent(request.url.host) { AtomicInteger() }.incrementAndGet()
            if (count == 1) {
                release.await(5, TimeUnit.SECONDS)
                object : WebSocket by IdleWebSocket(request) {
                    override fun close(code: Int, reason: String?): Boolean {
                        if (firstGeneration.remove(this)) {
                            // Every first attempt now drops its socket while still in flight;
                            // the last one triggers the reconnect that resetAllConnections makes.
                            inClose.countDown()
                            if (inClose.count == 0L) manager.connect()
                            inClose.await(5, TimeUnit.SECONDS)
                            Thread.sleep(300)
                        }
                        return true
                    }
                }.also { firstGeneration.add(it) }
            } else {
                IdleWebSocket(request)
            }
        }

        manager.connect()
        waitFor { opened.size == relays }
        // Tor ready: resetAllConnections disconnects while the first attempts are in flight.
        manager.disconnect()
        release.countDown()
        waitFor { opened.values.all { it.get() >= 2 } }

        assertEquals(
            NostrRelayManager.WESH_PINNED_RELAYS.associate { it.removePrefix("wss://") to 2 },
            opened.mapValues { it.value.get() }
        )
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private class IdleWebSocket(private val request: Request) : WebSocket {
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }
}
