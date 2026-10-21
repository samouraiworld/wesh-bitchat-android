package com.bitchat.android.nostr

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
class NostrRelayManager internal constructor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    
    companion object {
        /**
         * Wesh pins three public relays that accept connections from Tor exits; the first two are
         * also BitChat defaults. No custom or directory relay is ever dialed.
         * Declared before [shared]: the instance initializer reads it.
         */
        val WESH_PINNED_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://relay.snort.social"
        )

        @JvmStatic
        val shared = NostrRelayManager()
        
        private const val TAG = "NostrRelayManager"
        private const val MAX_QUEUED_EVENTS = 500
        const val OWNER_LEGACY = "legacy"
        const val OWNER_BACKGROUND = "background"
        
        /**
         * Get instance for Android compatibility (context-aware calls)
         */
        fun getInstance(context: android.content.Context): NostrRelayManager {
            shared.appContext = context.applicationContext
            return shared
        }

        private val DEFAULT_RELAYS = WESH_PINNED_RELAYS
        
        // Reconnect backoff lives in RelayReconnectPolicy.

        // Track gift-wraps we initiated for logging
        private val pendingGiftWrapIDs = ConcurrentHashMap.newKeySet<String>()
        
        fun registerPendingGiftWrap(id: String) {
            pendingGiftWrapIDs.add(id)
        }

        fun defaultRelays(): List<String> = DEFAULT_RELAYS
    }
    
    /**
     * Relay status information
     */
    data class Relay(
        val url: String,
        var isConnected: Boolean = false,
        var lastError: Throwable? = null,
        var lastConnectedAt: Long? = null,
        var messagesSent: Int = 0,
        var messagesReceived: Int = 0,
        var reconnectAttempts: Int = 0,
        var lastDisconnectedAt: Long? = null,
        var nextReconnectTime: Long? = null
    )
    
    // Published state
    private val _relays = MutableStateFlow<List<Relay>>(emptyList())
    val relays: StateFlow<List<Relay>> = _relays.asStateFlow()
    /** Relays with an open socket; the Internet state machine and the Pave screen both read it. */
    val connectedCount: Flow<Int> = relays.map { list -> list.count { it.isConnected } }.distinctUntilChanged()
    
    private val _isConnected = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Socket identity, mutable relay state and publication share the same teardown boundary.
    private val lifecycleLock = Any()
    private class ConnectionAttempt
    private val connectionAttempts = mutableMapOf<String, ConnectionAttempt>()

    // Internal state
    private val relaysList = mutableListOf<Relay>()
    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val desiredConnected = AtomicBoolean(false)
    private val subscriptions = ConcurrentHashMap<String, Set<String>>() // relay URL -> subscription IDs
    private val messageHandlers = ConcurrentHashMap<String, (NostrEvent) -> Unit>()
    
    // Persistent subscription tracking for robust reconnection
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionInfo>() // subscription ID -> info
    
    /**
     * Information about an active subscription that needs to be maintained across reconnections
     */
    data class SubscriptionInfo(
        val id: String,
        val filter: NostrFilter,
        val handler: (NostrEvent) -> Unit,
        val targetRelayUrls: Set<String>? = null, // null means all relays
        val createdAt: Long = System.currentTimeMillis(),
        val owner: String = OWNER_LEGACY
    )
    
    // Event deduplication system
    private val eventDeduplicator = NostrEventDeduplicator.getInstance()
    
    // Bounded per-relay delivery queue for reconnect reliability.
    private val messageQueue = NostrPendingEventQueue(MAX_QUEUED_EVENTS)
    
    // Subscription validation timer
    private var subscriptionValidationJob: Job? = null
    private val powerManager: com.bitchat.android.mesh.PowerManager?
        get() = appContext?.let { com.bitchat.android.mesh.PowerManager.getInstance(it) }
    @Volatile private var appContext: android.content.Context? = null
    
    // OkHttp client for WebSocket connections (via provider to honor Tor)
    private val httpClient: OkHttpClient
        get() = com.bitchat.android.net.OkHttpProvider.webSocketClient()
    
    /** Opens a relay socket; tests replace it to observe connection attempts. */
    internal var openWebSocket: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        httpClient.newWebSocket(request, listener)
    }

    private val gson by lazy { NostrRequest.createGson() }
    
    // --- Internal helpers ---

    /** Every socket, REQ and EVENT path checks the Wesh Internet gate first. */
    private fun isNetworkActionAllowed(): Boolean =
        com.bitchat.android.net.WeshNetworkPolicy.allowsNetwork()

    /**
     * Privacy teardown is allowed to bypass a closed network gate solely to stop
     * server-side delivery. If a CLOSE cannot be queued, fail closed by dropping that socket.
     */
    private fun closeSubscriptionsOnConnectedRelays(subscriptionIds: Set<String>) {
        if (subscriptionIds.isEmpty()) return

        val closeTargets = NostrLiveSubscriptionPrivacy.closeTargets(
            liveSubscriptionIds = subscriptionIds,
            subscriptionsByRelay = subscriptions,
        )
        closeTargets.forEach { (relayUrl, relaySubscriptionIds) ->
            val webSocket = connections[relayUrl] ?: return@forEach
            for (subscriptionId in relaySubscriptionIds) {
                val request = NostrRequest.Close(subscriptionId)
                val message = gson.toJson(request, NostrRequest::class.java)
                val closeQueued = runCatching { webSocket.send(message) }
                    .getOrDefault(false)
                if (!closeQueued) {
                    synchronized(lifecycleLock) {
                        if (connections.remove(relayUrl, webSocket)) {
                            connectionAttempts.remove(relayUrl)
                            subscriptions.remove(relayUrl)
                            updateRelayStatus(
                                relayUrl,
                                isConnected = false,
                                error = IllegalStateException("Failed to close revoked subscription")
                            )
                            if (desiredConnected.get()) scope.launch { connectToRelay(relayUrl) }
                        }
                    }
                    webSocket.cancel()
                    break
                }
            }
        }
    }

    init {
        // Initialize with default relays - avoid static initialization order issues
        try {
            val defaultRelayUrls = WESH_PINNED_RELAYS
            relaysList.addAll(defaultRelayUrls.map { Relay(it) })
            updateRelaysList()
            updateConnectionStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NostrRelayManager: ${e.message}", e)
            // Initialize with empty list as fallback
            _relays.value = emptyList()
            _isConnected.value = false
        }
    }
    
    /**
     * Connect to all configured relays
     */
    fun connect(): Unit = synchronized(lifecycleLock) {
        if (!isNetworkActionAllowed()) return
        desiredConnected.set(true)
        Log.i(TAG, "Connecting to ${relaysList.size} Nostr relays")
        scope.launch {
            relaysList.forEach { relay ->
                launch { connectToRelay(relay.url) }
            }
        }
        startSubscriptionValidation()
    }

    /** Disconnect invalidates attempts before closing sockets, so late callbacks are harmless. */
    fun disconnect() {
        val sockets = synchronized(lifecycleLock) { disconnectLocked() }
        sockets.forEach { it.close(1000, "Manual disconnect") }
    }

    /** Caller holds [lifecycleLock]; socket close is performed after releasing it. */
    private fun disconnectLocked(): List<WebSocket> {
        desiredConnected.set(false)
        connectionAttempts.clear()
        stopSubscriptionValidation()
        reconnectJobs.values.forEach(Job::cancel)
        reconnectJobs.clear()
        val sockets = connections.values.toList()
        connections.clear()
        subscriptions.clear()
        relaysList.forEach {
            it.isConnected = false
            it.nextReconnectTime = null
        }
        updateRelaysList()
        updateConnectionStatus()
        return sockets
    }

    /**
     * Send an event to specified relays (or all if none specified)
     */
    fun sendEvent(
        event: NostrEvent,
        relayUrls: List<String>? = null
    ) {
        if (!com.bitchat.android.net.WeshNetworkPolicy.allowsNetwork()) return
        val targetRelays = (relayUrls ?: relaysList.map { it.url })
            .filter { it.isNotBlank() }
            .distinct()
        if (targetRelays.isEmpty()) return

        val queueId = messageQueue.enqueue(
            event = event,
            relayUrls = targetRelays
        ) ?: return
        scope.launch {
            if (!isNetworkActionAllowed()) return@launch
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    if (sendToRelay(event, webSocket, relayUrl)) {
                        messageQueue.markDelivered(queueId, relayUrl)
                    }
                }
            }
        }
    }
    
    /**
     * Subscribe to events matching a filter
     * The subscription will be automatically re-established on reconnection
     */
    fun subscribe(
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>? = null,
        owner: String = OWNER_LEGACY
    ): String {
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            owner = owner
        )
        
        activeSubscriptions[id] = subscriptionInfo
        messageHandlers[id] = handler
        sendSubscriptionToRelays(subscriptionInfo)
        
        return id
    }
    
    /**
     * Send a subscription to the appropriate relays
     */
    private fun sendSubscriptionToRelays(subscriptionInfo: SubscriptionInfo) {
        if (!isNetworkActionAllowed()) return
        val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
        val message = gson.toJson(request, NostrRequest::class.java)

        scope.launch {
            if (!isNetworkActionAllowed()) return@launch
            val targetRelays = subscriptionInfo.targetRelayUrls?.toList() ?: connections.keys.toList()
            
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    try {
                        val success = webSocket.send(message)
                        if (success) {
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            subscriptions[relayUrl] =
                                currentSubs + subscriptionInfo.id
                        }
                        if (!success) {
                            Log.w(TAG, "Failed to send subscription: WebSocket send failed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send subscription")
                    }
                }
            }

            if (connections.isEmpty()) {
                Log.w(TAG, "⚠️ No relay connections available for subscription, will retry on reconnection")
            }
        }
    }
    
    /**
     * Unsubscribe from a subscription
     */
    fun unsubscribe(id: String) {
        // Remove from persistent tracking
        val subscriptionInfo = activeSubscriptions.remove(id)
        messageHandlers.remove(id)
        
        if (subscriptionInfo == null) {
            return
        }

        val request = NostrRequest.Close(id)
        val message = gson.toJson(request, NostrRequest::class.java)
        
        scope.launch {
            if (!isNetworkActionAllowed()) {
                closeSubscriptionsOnConnectedRelays(setOf(id))
                subscriptions.replaceAll { _, ids -> ids - id }
                return@launch
            }
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        webSocket.send(message)
                        subscriptions[relayUrl] = currentSubs - id
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unsubscribe from relay")
                    }
                }
            }
        }
    }

    fun unsubscribeOwner(owner: String) {
        activeSubscriptions.values
            .filter { it.owner == owner }
            .map { it.id }
            .forEach(::unsubscribe)
    }
    
    /**
     * Manually retry connection to a specific relay
     */
    fun retryConnection(relayUrl: String) {
        val socket = synchronized(lifecycleLock) {
            val relay = relaysList.find { it.url == relayUrl } ?: return
            if (!isNetworkActionAllowed()) return
            desiredConnected.set(true)
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
            reconnectJobs.remove(relayUrl)?.cancel()
            connectionAttempts.remove(relayUrl)
            val previous = connections.remove(relayUrl)
            updateRelayStatus(relayUrl, false)
            scope.launch { connectToRelay(relayUrl) }
            previous
        }
        socket?.close(1000, "Manual retry")
    }

    /** Reset atomically preserves the user's intent, while invalidating the old generation. */
    fun resetAllConnections() {
        val sockets = synchronized(lifecycleLock) {
            val shouldReconnect = desiredConnected.get()
            val previous = disconnectLocked()
            relaysList.forEach { relay ->
                relay.reconnectAttempts = 0
                relay.nextReconnectTime = null
                relay.lastError = null
            }
            updateRelaysList()
            if (shouldReconnect) connect()
            previous
        }
        sockets.forEach { it.close(1000, "Reset connection") }
    }

    /**
     * Force re-establishment of all subscriptions on currently connected relays
     * Useful for ensuring subscription consistency after network issues
     */
    fun reestablishAllSubscriptions() {
        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                restoreSubscriptionsForRelay(relayUrl, webSocket)
            }
        }
    }
    
    /**
     * Clear all subscription tracking, message handlers, routing caches, and queued messages.
     * Intended for panic/reset flows prior to reconnecting and re-subscribing from scratch.
     */
    fun clearAllSubscriptions() {
        try {
            // Clear persistent subscription tracking
            activeSubscriptions.clear()
            messageHandlers.clear()
            subscriptions.clear()

            // Clear any queued messages waiting to be sent
            messageQueue.clear()

            Log.i(TAG, "Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }

    /**
     * Clear all subscription tracking, deduplication cache, message queue, and connections for panic mode.
     */
    fun clearAllOnPanic() {
        try {
            clearAllSubscriptions()
            clearDeduplicationCache()
            // Panic turns Internet off: relays stay disconnected until the user opts in again.
            disconnect()
        } catch (_: Exception) {
        }
    }
    
    internal fun isConnectionDesired(): Boolean = desiredConnected.get()

    /**
     * Get detailed status for all relays
     */
    fun getRelayStatuses(): List<Relay> = synchronized(lifecycleLock) {
        relaysList.map { it.copy() }
    }
    
    /**
     * Get event deduplication statistics
     */
    fun getDeduplicationStats(): DeduplicationStats {
        return eventDeduplicator.getStats()
    }
    
    /**
     * Clear the event deduplication cache (useful for testing or debugging)
     */
    fun clearDeduplicationCache() {
        eventDeduplicator.clear()
    }
    
    /**
     * Get the count of active subscriptions
     */
    fun getActiveSubscriptionCount(): Int {
        return activeSubscriptions.size
    }
    
    /**
     * Get information about all active subscriptions (for debugging)
     */
    fun getActiveSubscriptions(): Map<String, SubscriptionInfo> {
        return activeSubscriptions.toMap()
    }
    
    /**
     * Validate subscription consistency across all relays
     * Returns a report of any inconsistencies found
     */
    fun validateSubscriptionConsistency(): SubscriptionConsistencyReport {
        val expectedSubs = activeSubscriptions.keys
        val actualSubsByRelay = subscriptions.toMap()
        val inconsistencies = mutableListOf<String>()
        
        connections.keys.forEach { relayUrl ->
            val actualSubs = actualSubsByRelay[relayUrl] ?: emptySet()
            val expectedForRelay = expectedSubs.filter { subId ->
                val subInfo = activeSubscriptions[subId]
                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
            }.toSet()
            
            val missing = expectedForRelay - actualSubs
            val extra = actualSubs - expectedForRelay
            
            if (missing.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl missing subscriptions: $missing")
            }
            if (extra.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl has extra subscriptions: $extra")
            }
        }
        
        return SubscriptionConsistencyReport(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            totalActiveSubscriptions = activeSubscriptions.size,
            connectedRelayCount = connections.size
        )
    }
    
    data class SubscriptionConsistencyReport(
        val isConsistent: Boolean,
        val inconsistencies: List<String>,
        val totalActiveSubscriptions: Int,
        val connectedRelayCount: Int
    )
    
    /**
     * Start periodic subscription validation to ensure robustness
     */
    private fun startSubscriptionValidation() {
        stopSubscriptionValidation() // Stop any existing validation
        
        subscriptionValidationJob = scope.launch {
            val manager = powerManager
            if (manager == null) {
                runSubscriptionValidationLoop(
                    com.bitchat.android.util.AppConstants.Nostr
                        .SUBSCRIPTION_VALIDATION_INTERVAL_MS
                )
                return@launch
            }

            manager.profile
                .map { it.nostr.subscriptionValidationMs }
                .distinctUntilChanged()
                .collectLatest(::runSubscriptionValidationLoop)
        }
    }

    private suspend fun runSubscriptionValidationLoop(intervalMs: Long) {
        while (currentCoroutineContext().isActive && desiredConnected.get()) {
            delay(intervalMs)
            if (!desiredConnected.get()) break
            validateAndRepairSubscriptions()
        }
    }

    private fun validateAndRepairSubscriptions() {
        try {
            val report = validateSubscriptionConsistency()
            if (report.isConsistent || report.connectedRelayCount == 0) return

            Log.w(TAG, "Nostr subscription inconsistencies detected")
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl] ?: emptySet()
                val expectedSubs = activeSubscriptions.keys.filter { subId ->
                    val subInfo = activeSubscriptions[subId]
                    subInfo?.targetRelayUrls == null ||
                        subInfo.targetRelayUrls.contains(relayUrl)
                }.toSet()

                if ((expectedSubs - currentSubs).isNotEmpty()) {
                    Log.i(TAG, "Auto-repairing missing subscriptions")
                    restoreSubscriptionsForRelay(relayUrl, webSocket)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during subscription validation: ${e.message}")
        }
    }
    
    /**
     * Stop periodic subscription validation
     */
    private fun stopSubscriptionValidation() {
        subscriptionValidationJob?.cancel()
        subscriptionValidationJob = null
    }
    
    // MARK: - Private Methods
    
    private suspend fun connectToRelay(urlString: String) {
        val attempt = synchronized(lifecycleLock) {
            if (urlString !in WESH_PINNED_RELAYS || !isNetworkActionAllowed() ||
                !desiredConnected.get() || connections.containsKey(urlString) ||
                connectionAttempts.containsKey(urlString)
            ) return
            ConnectionAttempt().also { connectionAttempts[urlString] = it }
        }
        try {
            val request = Request.Builder().url(urlString).build()
            // Reserve the attempt before calling OkHttp: its listener may run before this returns.
            val webSocket = openWebSocket(request, RelayWebSocketListener(urlString, attempt))
            val accepted = synchronized(lifecycleLock) {
                if (!isNetworkActionAllowed() || !desiredConnected.get() ||
                    connectionAttempts[urlString] !== attempt
                ) {
                    if (connectionAttempts[urlString] === attempt) {
                        connections.remove(urlString, webSocket)
                        subscriptions.remove(urlString)
                        connectionAttempts.remove(urlString)
                        updateRelayStatus(urlString, false)
                    }
                    false
                } else {
                    connections[urlString] = webSocket
                    true
                }
            }
            if (!accepted) webSocket.close(1000, "Stale connection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection")
            synchronized(lifecycleLock) {
                if (connectionAttempts[urlString] === attempt) {
                    connectionAttempts.remove(urlString)
                    handleCurrentDisconnection(urlString, e)
                }
            }
        }
    }

    private fun sendToRelay(
        event: NostrEvent,
        webSocket: WebSocket,
        relayUrl: String
    ): Boolean = synchronized(lifecycleLock) {
        if (!isNetworkActionAllowed() || connections[relayUrl] !== webSocket) return false
        try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)

            val success = webSocket.send(message)
            if (success) {
                // Update relay stats
                relaysList.find { it.url == relayUrl }?.let { relay ->
                    relay.messagesSent += 1
                }
                updateRelaysList()
                true
            } else {
                Log.e(TAG, "Failed to send event: WebSocket send failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event")
            false
        }
    }

    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val jsonElement = JsonParser.parseString(message)
            if (!jsonElement.isJsonArray) {
                Log.w(TAG, "Received non-array message from relay")
                return
            }
            
            val response = NostrResponse.fromJsonArray(jsonElement.asJsonArray)
            
            when (response) {
                is NostrResponse.Event -> {
                    // Update relay stats
                    relaysList.find { it.url == relayUrl }?.let { relay ->
                        relay.messagesReceived += 1
                    }
                    updateRelaysList()
                    
                    // CLIENT-SIDE FILTER ENFORCEMENT: Ensure this event matches the subscription's filter
                    val subscriptionInfo = activeSubscriptions[response.subscriptionId]
                        ?: return
                    if (!isNetworkActionAllowed()) return
                    subscriptionInfo.let { subInfo ->
                        val matches = try { subInfo.filter.matches(response.event) } catch (e: Exception) { true }
                        if (!matches) {
                            // Do NOT call deduplicator here to allow the correct subscription to process it later
                            return
                        }
                    }

                    // DEDUPLICATION: Check if we've already processed this event
                    eventDeduplicator.processEvent(response.event) { event ->
                        // Call handler for new events only
                        val handler = messageHandlers[response.subscriptionId]
                        if (handler != null) {
                            scope.launch(Dispatchers.Main) {
                                // A queued delivery belongs to this registration, even when a
                                // replacement reuses the same subscription ID and callback.
                                if (isNetworkActionAllowed() &&
                                    activeSubscriptions[response.subscriptionId] === subscriptionInfo &&
                                    messageHandlers[response.subscriptionId] === handler
                                ) {
                                    handler(event)
                                }
                            }
                        } else {
                            Log.w(TAG, "⚠️ No handler for Nostr subscription")
                        }
                    }

                }

                is NostrResponse.EndOfStoredEvents -> {
                    // No action needed
                }

                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (!response.accepted) {
                        val level = if (wasGiftWrap) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "Event rejected by relay: ${response.message ?: "no reason"}")
                    }
                }

                is NostrResponse.Notice -> {
                    // No action needed
                }

                is NostrResponse.Unknown -> {
                    // No action needed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse relay message")
        }
    }
    
    private fun handleDisconnection(
        relayUrl: String,
        attempt: ConnectionAttempt,
        webSocket: WebSocket,
        error: Throwable
    ): Unit = synchronized(lifecycleLock) {
        // The attempt is registered before socket creation, including an early failure callback.
        if (connectionAttempts[relayUrl] !== attempt) return
        connectionAttempts.remove(relayUrl)
        connections.remove(relayUrl, webSocket)
        subscriptions.remove(relayUrl)
        handleCurrentDisconnection(relayUrl, error)
    }

    private fun handleCurrentDisconnection(
        relayUrl: String,
        error: Throwable
    ) {
        updateRelayStatus(relayUrl, false, error)
        if (!desiredConnected.get() ||
            !isNetworkActionAllowed()
        ) return

        // Every failure backs off and retries, including a name-resolution
        // failure: "unable to resolve host" is what this device reports when it
        // simply has no network, so treating it as permanent turns a walk
        // through a tunnel into a dead relay layer for the rest of the process.
        val relay = relaysList.find { it.url == relayUrl } ?: return
        relay.reconnectAttempts = RelayReconnectPolicy.nextAttempt(relay.reconnectAttempts)
        val backoffInterval = RelayReconnectPolicy.backoffMs(relay.reconnectAttempts)

        relay.nextReconnectTime = System.currentTimeMillis() + backoffInterval
        
        Log.d(TAG, "Scheduling Nostr relay reconnection")
        
        reconnectJobs.remove(relayUrl)?.cancel()
        val reconnectJob = scope.launch {
            delay(backoffInterval)
            if (desiredConnected.get() &&
                isNetworkActionAllowed()
            ) {
                connectToRelay(relayUrl)
            }
        }
        reconnectJobs[relayUrl] = reconnectJob
        reconnectJob.invokeOnCompletion {
            reconnectJobs.remove(relayUrl, reconnectJob)
        }
    }
    
    internal fun updateRelayStatus(url: String, isConnected: Boolean, error: Throwable? = null): Unit = synchronized(lifecycleLock) {
        val relay = relaysList.find { it.url == url } ?: return
        
        relay.isConnected = isConnected
        relay.lastError = error
        
        if (isConnected) {
            relay.lastConnectedAt = System.currentTimeMillis()
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
        } else {
            relay.lastDisconnectedAt = System.currentTimeMillis()
        }
        
        updateRelaysList()
        updateConnectionStatus()
    }
    
    private fun updateRelaysList(): Unit = synchronized(lifecycleLock) {
        // Relay is mutated in place: a list of the same instances equals the previous value and
        // StateFlow would drop the update, so collectors never saw a relay connect.
        _relays.value = relaysList.map { it.copy() }
    }
    
    private fun updateConnectionStatus(): Unit = synchronized(lifecycleLock) {
        val connected = relaysList.any { it.isConnected }
        _isConnected.value = connected
    }
    
    private fun generateSubscriptionId(): String {
        return "sub-${UUID.randomUUID()}"
    }
    
    /**
     * Restore all active subscriptions for a specific relay that just reconnected
     */
    private fun restoreSubscriptionsForRelay(relayUrl: String, webSocket: WebSocket): Unit = synchronized(lifecycleLock) {
        if (!isNetworkActionAllowed() || connections[relayUrl] !== webSocket) return
        val subscriptionsToRestore = activeSubscriptions.values.filter { subscriptionInfo ->
            // Include subscription if it targets all relays or specifically targets this relay
            isNetworkActionAllowed() &&
                (subscriptionInfo.targetRelayUrls == null ||
                    subscriptionInfo.targetRelayUrls.contains(relayUrl))
        }
        
        if (subscriptionsToRestore.isEmpty()) {
            return
        }

        subscriptionsToRestore.forEach { subscriptionInfo ->
            try {
                val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
                val message = gson.toJson(request, NostrRequest::class.java)

                val success = webSocket.send(message)
                if (success) {
                    val currentSubs = subscriptions[relayUrl] ?: emptySet()
                    subscriptions[relayUrl] =
                        currentSubs + subscriptionInfo.id
                }
                if (!success) {
                    Log.w(TAG, "Failed to restore subscription: WebSocket send failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore subscription")
            }
        }
    }
    
    /**
     * WebSocket listener for relay connections
     */
    private inner class RelayWebSocketListener(
        private val relayUrl: String,
        private val attempt: ConnectionAttempt
    ) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val accepted = synchronized(lifecycleLock) {
                if (!isNetworkActionAllowed() || !desiredConnected.get() ||
                    connectionAttempts[relayUrl] !== attempt
                ) {
                    if (connectionAttempts[relayUrl] === attempt) {
                        connectionAttempts.remove(relayUrl)
                        connections.remove(relayUrl, webSocket)
                        updateRelayStatus(relayUrl, false)
                    }
                    false
                } else {
                    connections[relayUrl] = webSocket
                    reconnectJobs.remove(relayUrl)?.cancel()
                    updateRelayStatus(relayUrl, true)
                    restoreSubscriptionsForRelay(relayUrl, webSocket)
                    messageQueue.pendingForRelay(relayUrl).forEach { delivery ->
                        if (sendToRelay(delivery.event, webSocket, relayUrl)) {
                            messageQueue.markDelivered(delivery.queueId, relayUrl)
                        }
                    }
                    true
                }
            }
            if (!accepted) webSocket.close(1000, "Stale connection")
        }

        override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(lifecycleLock) {
            if (connectionAttempts[relayUrl] !== attempt || connections[relayUrl] !== webSocket) return
            handleMessage(text, relayUrl)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server-initiated close; onClosed will follow
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, attempt, webSocket, error)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Nostr WebSocket failure")
            handleDisconnection(relayUrl, attempt, webSocket, t)
        }
    }
}
