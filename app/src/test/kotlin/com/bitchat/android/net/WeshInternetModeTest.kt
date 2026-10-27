package com.bitchat.android.net

import android.app.Application
import android.content.Context
import com.bitchat.android.net.WeshInternetMachine.Event
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshInternetModeTest {
    private class FakeTor : WeshTorControl {
        val status = MutableStateFlow(0 to false)
        var running = false
        var starts = 0
        var wiped = false
        var wipeResult = true
        var startAllowed = true
        override val progress = status
        override fun start(): Boolean { starts++; if (!startAllowed) { running = false; return false }; running = true; return true }
        override fun stop() { running = false }
        override suspend fun stopAndWipe(): Boolean { running = false; wiped = true; return wipeResult }
    }
    private class FakeRelays : WeshRelayControl {
        val count = MutableStateFlow(0)
        var connects = 0
        var desired = false
        var panicCleared = false
        override val connectedCount = count
        override fun connect() { connects++; desired = true }
        override fun disconnect() { desired = false; count.value = 0 }
        override fun clearOnPanic() { panicCleared = true; desired = false }
    }

    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-internet", Context.MODE_PRIVATE)
    private lateinit var scope: TestScope
    private lateinit var tor: FakeTor
    private lateinit var relays: FakeRelays
    private lateinit var network: MutableSharedFlow<Boolean>
    private var keysWiped = false
    private var keysCreated = 0
    private var available = true

    @Before fun setup() {
        prefs.edit().clear().commit()
        scope = TestScope()
        tor = FakeTor(); relays = FakeRelays(); network = MutableSharedFlow(replay = 1)
        keysWiped = false; keysCreated = 0; available = true
    }

    private fun mode(startFenced: Boolean = false) = WeshInternetMode(prefs, tor, relays, network, { keysWiped = true; true }, { available }, scope.backgroundScope,
        ensureNostrIdentity = { keysCreated++ }, startFenced = startFenced)

    @Test fun `wrongly typed stored values read as absent and the erase still clears them`() = scope.runTest {
        prefs.edit().putString(WeshInternetMode.KEY_ENABLED, "yes").putInt(WeshInternetMode.KEY_DISCLOSURE, 1).commit()
        val mode = mode(); runCurrent()
        assertFalse(mode.enabled.value)
        assertFalse(mode.disclosureAccepted)
        assertFalse(mode.allowsNetwork())
        assertTrue(mode.panicReset())
        assertFalse(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, true))
        assertFalse(prefs.contains(WeshInternetMode.KEY_DISCLOSURE))
    }

    private fun connected(mode: WeshInternetMode) {
        prefs.edit().putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        assertTrue(mode.setEnabled(true))
        tor.status.value = 100 to true
        scope.runCurrent()
        relays.count.value = 1
        scope.runCurrent()
    }

    @Test fun `opt-in is persisted, defaults off, and needs disclosure and product policy`() = scope.runTest {
        val mode = mode(); runCurrent()
        assertEquals(WeshInternetState.Off, mode.state.value)
        assertFalse(mode.allowsNetwork())
        assertFalse(tor.running)
        assertFalse(mode.setEnabled(true))
        mode.disclosureAccepted = true
        available = false
        assertFalse(mode.setEnabled(true))
        assertFalse(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, false))
        available = true
        assertEquals(0, keysCreated)
        assertTrue(mode.setEnabled(true))
        assertEquals("The Nostr key is created on opt-in only", 1, keysCreated)
        assertTrue(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, false))
        assertTrue(mode.allowsNetwork())
    }

    @Test fun `an opt-in persisted while the product was unavailable stays off and never starts Tor`() = scope.runTest {
        prefs.edit().putBoolean(WeshInternetMode.KEY_ENABLED, true).putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        available = false
        val mode = mode(); runCurrent()
        assertFalse(mode.enabled.value)
        assertFalse(mode.allowsNetwork())
        assertFalse(tor.running)
        assertEquals(WeshInternetState.Off, mode.state.value)
    }

    @Test fun `startup installs a closed process gate and keeps Tor preference off`() {
        val app = RuntimeEnvironment.getApplication()
        WeshInternetMode.resetForTesting()
        TorPreferenceManager.set(app, TorMode.ON)
        WeshNetworkPolicy.gate = WeshNetworkGate { true }
        WeshInternetMode.get(app)
        assertFalse(WeshNetworkPolicy.allowsNetwork())
        assertEquals(TorMode.OFF, TorPreferenceManager.get(app))
    }

    @Test fun `relays are dialed only once Tor is ready and On needs a connected relay`() = scope.runTest {
        prefs.edit().putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        val mode = mode(); runCurrent()
        mode.setEnabled(true)
        assertTrue(tor.running)
        assertEquals(WeshInternetState.Connecting(0), mode.state.value)
        tor.status.value = 75 to false; runCurrent()
        assertEquals(WeshInternetState.Connecting(75), mode.state.value)
        assertEquals(0, relays.connects)
        tor.status.value = 100 to true; runCurrent()
        assertEquals(1, relays.connects)
        assertEquals(WeshInternetState.Connecting(100), mode.state.value)
        relays.count.value = 2; runCurrent()
        assertEquals(WeshInternetState.On, mode.state.value)
    }

    @Test fun `a 75 second stall becomes an error, progress rearms it, retry reconnects`() = scope.runTest {
        prefs.edit().putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        val mode = mode(); runCurrent()
        mode.setEnabled(true)
        advanceTimeBy(70_000); tor.status.value = 50 to false; runCurrent()
        advanceTimeBy(70_000); runCurrent()
        assertEquals(WeshInternetState.Connecting(50), mode.state.value)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(WeshInternetState.Error(WeshInternetError.TOR_BLOCKED), mode.state.value)
        mode.retry()
        assertEquals(WeshInternetState.Connecting(50), mode.state.value)
        assertEquals(2, tor.starts)
        tor.status.value = 100 to true; runCurrent()
        advanceTimeBy(75_001); runCurrent()
        assertEquals(WeshInternetState.Error(WeshInternetError.RELAYS_UNREACHABLE), mode.state.value)
    }

    @Test fun `losing the network or Tor drops On and a network change leaves the error`() = scope.runTest {
        val mode = mode(); runCurrent()
        connected(mode)
        assertEquals(WeshInternetState.On, mode.state.value)
        tor.status.value = 0 to false; runCurrent()
        assertEquals(WeshInternetState.Connecting(0), mode.state.value)
        network.emit(false); runCurrent()
        assertEquals(WeshInternetState.Error(WeshInternetError.NO_NETWORK), mode.state.value)
        network.emit(true); runCurrent()
        assertEquals(WeshInternetState.Connecting(0), mode.state.value)
        tor.status.value = 100 to true; runCurrent()
        assertEquals(WeshInternetState.On, mode.state.value)
        assertEquals(2, relays.connects)
    }

    @Test fun `toggle off stops Tor and relays and is persisted, a persisted opt-in resumes`() = scope.runTest {
        val mode = mode(); runCurrent()
        connected(mode)
        mode.setEnabled(false)
        assertEquals(WeshInternetState.Off, mode.state.value)
        assertFalse(tor.running)
        assertFalse(relays.desired)
        assertFalse(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, true))

        prefs.edit().putBoolean(WeshInternetMode.KEY_ENABLED, true).commit()
        val resumed = mode(); runCurrent()
        assertTrue(tor.running)
        assertEquals(WeshInternetState.Connecting(100), resumed.state.value)
    }

    @Test fun `panic turns Internet off for good and wipes Tor state and Nostr keys`() = scope.runTest {
        val mode = mode(); runCurrent()
        connected(mode)
        mode.fenceForPanic()
        assertFalse(mode.allowsNetwork())
        assertFalse(mode.setEnabled(true))
        assertTrue(mode.panicReset())
        assertEquals(WeshInternetState.Off, mode.state.value)
        assertFalse(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, true))
        assertTrue(relays.panicCleared)
        assertFalse(relays.desired)
        assertTrue(tor.wiped)
        assertTrue(keysWiped)
        assertFalse("Disclosure must be shown again after panic", prefs.contains(WeshInternetMode.KEY_DISCLOSURE))
        assertFalse(mode.setEnabled(true))
        // Late Tor and relay callbacks after the wipe cannot bring Internet back.
        tor.status.value = 100 to true; relays.count.value = 1; runCurrent()
        assertEquals(WeshInternetState.Off, mode.state.value)
        assertEquals(1, relays.connects)
        assertFalse(mode.allowsNetwork())
    }

    @Test fun `an unconfirmed Tor wipe fails the panic and keeps the fence up`() = scope.runTest {
        val mode = mode(); runCurrent()
        connected(mode)
        tor.wipeResult = false
        assertFalse(mode.panicReset())
        assertEquals(WeshInternetState.Off, mode.state.value)
        prefs.edit().putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        assertFalse(mode.setEnabled(true))
        assertFalse(mode.allowsNetwork())
        tor.wipeResult = true
        assertTrue(mode.panicReset())
        prefs.edit().putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        assertTrue(mode.setEnabled(true))
    }

    @Test fun `an incomplete Arti wipe refuses the opt-in, persists nothing and keeps the fence`() = scope.runTest {
        prefs.edit().putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        val mode = mode(); runCurrent()
        tor.startAllowed = false
        assertFalse(mode.setEnabled(true))
        assertFalse(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, true))
        assertEquals(0, keysCreated)
        assertFalse(mode.allowsNetwork())
        tor.startAllowed = true
        assertFalse("Fence stays up until a panic reset completes", mode.setEnabled(true))
        assertTrue(mode.panicReset())
        prefs.edit().putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        assertTrue(mode.setEnabled(true))
    }

    @Test fun `startup with a persisted opt-in but an unfinished wipe stays off and fenced`() = scope.runTest {
        prefs.edit().putBoolean(WeshInternetMode.KEY_ENABLED, true).putBoolean(WeshInternetMode.KEY_DISCLOSURE, true).commit()
        tor.startAllowed = false
        val failed = mode(); runCurrent()
        assertEquals(WeshInternetState.Off, failed.state.value)
        assertFalse(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, true))
        assertFalse(failed.allowsNetwork())

        prefs.edit().putBoolean(WeshInternetMode.KEY_ENABLED, true).commit()
        tor.startAllowed = true
        val starts = tor.starts
        val fencedAtStartup = mode(startFenced = true); runCurrent()
        assertEquals(starts, tor.starts)
        assertFalse(tor.running)
        assertFalse(prefs.getBoolean(WeshInternetMode.KEY_ENABLED, true))
        assertFalse(fencedAtStartup.setEnabled(true))
    }

    @Test fun `transition table`() {
        val ready = WeshInternetInputs(torPercent = 100, torReady = true, relays = 1)
        val booting = WeshInternetInputs(torPercent = 30)
        val offline = booting.copy(network = false)
        val r = WeshInternetMachine::reduce
        assertEquals(WeshInternetState.Connecting(30), r(WeshInternetState.Off, Event.Enable, booting))
        assertEquals(WeshInternetState.Off, r(WeshInternetState.Off, Event.Inputs(ready), ready))
        assertEquals(WeshInternetState.Off, r(WeshInternetState.Off, Event.Retry, booting))
        assertEquals(WeshInternetState.Error(WeshInternetError.NO_NETWORK), r(WeshInternetState.Connecting(30), Event.NetworkChanged(false), offline))
        assertEquals(WeshInternetState.Error(WeshInternetError.TOR_BLOCKED), r(WeshInternetState.Connecting(30), Event.Stalled, booting))
        assertEquals(WeshInternetState.On, r(WeshInternetState.Connecting(30), Event.Inputs(ready), ready))
        assertEquals(WeshInternetState.Connecting(30), r(WeshInternetState.Error(WeshInternetError.TOR_BLOCKED), Event.Retry, booting))
        assertEquals(WeshInternetState.Connecting(30), r(WeshInternetState.Error(WeshInternetError.NO_NETWORK), Event.NetworkChanged(true), booting))
        assertEquals(WeshInternetState.Connecting(100), r(WeshInternetState.On, Event.Inputs(ready.copy(relays = 0)), ready.copy(relays = 0)))
        assertEquals(
            WeshInternetState.Error(WeshInternetError.NO_NETWORK),
            r(WeshInternetState.On, Event.NetworkChanged(false), ready.copy(network = false)),
        )
        assertEquals(
            WeshInternetState.On,
            r(WeshInternetState.Error(WeshInternetError.NO_NETWORK), Event.NetworkChanged(true), ready),
        )
        assertEquals(WeshInternetState.On, r(WeshInternetState.On, Event.Stalled, ready))
        listOf(WeshInternetState.On, WeshInternetState.Connecting(5), WeshInternetState.Error(WeshInternetError.RELAYS_UNREACHABLE))
            .forEach { assertEquals(WeshInternetState.Off, r(it, Event.Disable, ready)) }
    }
}
