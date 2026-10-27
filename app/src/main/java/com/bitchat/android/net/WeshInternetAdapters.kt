package com.bitchat.android.net

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import info.guardianproject.arti.ArtiWipeLatch
import java.io.File

/** Arti behind [WeshInternetMode]. TorPreferenceManager only ever mirrors the Wesh toggle. */
internal class ArtiTorControl(
    private val app: Application,
    private val status: StateFlow<ArtiTorManager.TorStatus> = ArtiTorManager.getInstance().statusFlow,
    private val applyOff: suspend () -> Unit = { ArtiTorManager.getInstance().applyMode(app, TorMode.OFF) },
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
    private val deleteArti: (File) -> Boolean = ::deleteDirectory,
    private val createMarker: (File) -> Boolean = { it.exists() || it.createNewFile() },
    private val startInFlight: () -> Boolean = { ArtiTorManager.getInstance().isStartInFlight() },
    private val requestOff: () -> Unit = { ArtiTorManager.getInstance().requestOff() },
    private val stopFailed: () -> Boolean = { ArtiTorManager.getInstance().hasNativeStopFailed() }
) : WeshTorControl {
    override val progress: Flow<Pair<Int, Boolean>> = status.map { it.bootstrapPercent to it.isReady() }

    override fun start(): Boolean {
        // A wipe left over from a failed panic runs before Arti may touch its directory again.
        // On failure the committed OFF also makes an initialized manager stop any surviving Arti.
        if (!completePendingWipe(app, deleteArti)) {
            stop()
            return false
        }
        TorPreferenceManager.set(app, TorMode.ON)
        ArtiTorManager.getInstance().init(app)
        return true
    }

    override fun stop() {
        // Immediate: a start still bootstrapping is cancelled now, not after it finishes.
        requestOff()
        TorPreferenceManager.set(app, TorMode.OFF, commit = true)
    }

    /**
     * Always stops Arti, waits for it (hard timeout), and always deletes its directory. True only
     * when both are confirmed; otherwise a marker makes the next process start delete it again.
     */
    override suspend fun stopAndWipe(): Boolean {
        // Up for the whole wipe: no start may begin until the directory is gone and verified.
        ArtiWipeLatch.begin()
        try {
            return stopAndWipeLatched()
        } finally {
            ArtiWipeLatch.end()
        }
    }

    private suspend fun stopAndWipeLatched(): Boolean {
        stop()
        // The native stop blocks; panic runs on the main thread.
        val stopped = withContext(Dispatchers.IO) {
            withTimeoutOrNull(stopTimeoutMs) {
                applyOff()
                status.first { it.state == ArtiTorManager.TorState.OFF && !it.running }
            } != null && !runCatching(stopFailed).getOrDefault(true)
        }
        OkHttpProvider.reset()
        return withContext(Dispatchers.IO) {
            val marked = runCatching { createMarker(pendingMarker(app)) }.getOrDefault(false)
            var deleted = runCatching { deleteArti(artiDirectory(app)) }.getOrDefault(false)
            // A start still in flight could recreate the directory after this delete: wait it
            // out, then stop and delete once more if it left anything behind.
            val settled = withTimeoutOrNull(stopTimeoutMs) {
                while (runCatching(startInFlight).getOrDefault(true)) delay(SETTLE_POLL_MS)
            } != null
            if (artiDirectory(app).exists()) {
                withTimeoutOrNull(stopTimeoutMs) { applyOff() }
                deleted = runCatching { deleteArti(artiDirectory(app)) }.getOrDefault(false)
            }
            val done = stopped && deleted && settled
            when {
                done -> clearPendingSignalsIfGone(app)
                // No marker: a committed preference is the second durable signal.
                !marked -> runCatching { pendingPreferences(app).edit().putBoolean(KEY_PENDING, true).commit() }
            }
            done && !isWipePending(app)
        }
    }

    private fun ArtiTorManager.TorStatus.isReady(): Boolean =
        mode == TorMode.ON && running && bootstrapPercent >= 100 && state == ArtiTorManager.TorState.RUNNING

    companion object {
        /** Above ArtiTorManager's own 7 s stop wait. */
        const val STOP_TIMEOUT_MS = 15_000L
        private const val SETTLE_POLL_MS = 50L

        /** ArtiProxy keeps its `cache` and `state` directories here. */
        fun artiDirectory(context: Context): File = File(context.filesDir, "arti")

        /** Outside the Arti directory and outside the preference files panic clears. */
        fun pendingMarker(context: Context): File = File(context.filesDir, "arti-wipe-pending")

        private fun deleteDirectory(dir: File): Boolean = dir.deleteRecursively() && !dir.exists()

        /**
         * Runs at process start and before every Arti start: finishes a wipe a failed panic left
         * behind. False while the directory still cannot be deleted, so Arti must not start.
         */
        fun completePendingWipe(context: Context, delete: (File) -> Boolean = ::deleteDirectory): Boolean {
            if (!isWipePending(context)) return true
            val deleted = runCatching { delete(artiDirectory(context)) }.getOrDefault(false)
            if (deleted) clearPendingSignalsIfGone(context)
            return deleted && !isWipePending(context)
        }

        private const val KEY_PENDING = "arti_wipe_pending"
        private fun pendingPreferences(context: Context) =
            context.getSharedPreferences("wesh_arti_wipe", Context.MODE_PRIVATE)

        /** Marker file or committed preference; an unreadable signal counts as pending. */
        fun isWipePending(context: Context): Boolean = runCatching {
            pendingMarker(context).exists() || pendingPreferences(context).getBoolean(KEY_PENDING, false)
        }.getOrDefault(true)

        private fun clearPendingSignals(context: Context) {
            runCatching { pendingMarker(context).delete() }
            runCatching { pendingPreferences(context).edit().remove(KEY_PENDING).commit() }
        }

        /** Clears the pending wipe only if the directory is really gone; otherwise re-arms it. */
        private fun clearPendingSignalsIfGone(context: Context) {
            fun present() = runCatching { artiDirectory(context).exists() }.getOrDefault(true)
            if (present()) return rearmPendingSignals(context)
            clearPendingSignals(context)
            if (present()) rearmPendingSignals(context)
        }

        private fun rearmPendingSignals(context: Context) {
            runCatching { pendingMarker(context).createNewFile() }
            runCatching { pendingPreferences(context).edit().putBoolean(KEY_PENDING, true).commit() }
        }
    }
}

internal class NostrRelayControl(private val context: Context) : WeshRelayControl {
    private val manager: NostrRelayManager get() = NostrRelayManager.getInstance(context)
    override val connectedCount: Flow<Int> = NostrRelayManager.shared.connectedCount
    override fun connect() = manager.connect()
    override fun disconnect() = manager.disconnect()
    override fun clearOnPanic() = manager.clearAllOnPanic()
}

/** A candidate Internet route. Tor readiness remains the end-to-end connectivity signal. */
internal fun hasInternetRoute(capabilities: NetworkCapabilities?): Boolean =
    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

/** Keeps late callbacks for the previous default network from overriding the new one. */
internal class DefaultNetworkAvailability<N>(initialDefault: N?, val initiallyAvailable: Boolean) {
    private var currentDefault: N? = initialDefault

    fun onAvailable(network: N) {
        currentDefault = network
    }

    fun onCapabilitiesChanged(network: N, available: Boolean): Boolean? =
        available.takeIf { network == currentDefault }

    fun onLost(network: N): Boolean? {
        if (network != currentDefault) return null
        currentDefault = null
        return false
    }
}

/** Emits whether the current default network advertises a candidate Internet route. */
internal fun defaultNetworkFlow(context: Context): Flow<Boolean> = callbackFlow {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    if (connectivity == null) {
        trySend(false)
        awaitClose { }
        return@callbackFlow
    }
    val initialDefault = runCatching { connectivity.activeNetwork }.getOrNull()
    val initiallyAvailable = runCatching {
        hasInternetRoute(connectivity.getNetworkCapabilities(initialDefault))
    }.getOrDefault(false)
    val availability = DefaultNetworkAvailability(initialDefault, initiallyAvailable)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Android publishes ordered capabilities next. They describe whether this new default
            // is a candidate route; Tor readiness remains the end-to-end signal.
            availability.onAvailable(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            availability.onCapabilitiesChanged(network, hasInternetRoute(capabilities))?.let(::trySend)
        }

        override fun onLost(network: Network) {
            availability.onLost(network)?.let(::trySend)
        }
    }
    trySend(availability.initiallyAvailable)
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
}.distinctUntilChanged()

internal fun wipeNostrKeys(context: Context): Boolean = NostrIdentityBridge.panicClearKeys(context)
