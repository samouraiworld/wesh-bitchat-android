package com.bitchat.android

import android.app.Application
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.WESH_POLICY) {
            com.bitchat.android.startup.WeshRuntime.start(this)
        } else {
            initializeRuntime()
        }
    }

    internal fun initializeRuntime() {
        // Start the single process-wide power policy before transport components are constructed.
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()

        // Installs the Internet opt-in gate (default off) and keeps Tor's preference OFF while it is off.
        try { com.bitchat.android.net.WeshInternetMode.get(this) } catch (_: Exception) { }
        // Receives Internet messages from QR contacts while the mode is On; opens no socket itself.
        if (BuildConfig.WESH_POLICY) try { com.bitchat.android.services.WeshInternetReceiver.start(this) } catch (_: Exception) { }

        // Tor follows the runtime opt-in only (WeshInternetMode starts it when the user turns it on).
        if (com.bitchat.android.net.WeshNetworkPolicy.allowsNetwork()) {
            try { ArtiTorManager.getInstance().init(this) } catch (_: Exception) { }
        }
        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Upstream warms the Nostr key for favorite notifications. Not in Wesh: only the Internet
        // opt-in creates that key, and nothing reads the key store on the Main thread at startup.
        if (!BuildConfig.WESH_POLICY) try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = !BuildConfig.WESH_POLICY && com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Initialize the Nostr alias registry for persistence
        try {
            com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
        } catch (_: Exception) { }

        // Own relay connectivity and account-DM subscriptions at the process level so closing the
        // Activity does not disconnect Nostr. Self-gated: its relay sockets follow the toggle.
        try { com.bitchat.android.nostr.NostrBackgroundRuntime.initialize(this) } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        if (!BuildConfig.WESH_POLICY) try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
