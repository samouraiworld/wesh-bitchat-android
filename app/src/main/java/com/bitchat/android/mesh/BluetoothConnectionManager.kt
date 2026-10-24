package com.bitchat.android.mesh

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Power-optimized Bluetooth connection manager with comprehensive memory management
 * Integrates with PowerManager for adaptive power consumption
 * Coordinates smaller, focused components for better maintainability
 */
class BluetoothConnectionManager(
    private val context: Context, 
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) {
    
    companion object {
        private const val TAG = "BluetoothConnectionManager"
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    // Power management
    private val powerManager = PowerManager.getInstance(context.applicationContext)
    
    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Component managers
    private val permissionManager = BluetoothPermissionManager(context)
    private val connectionTracker = BluetoothConnectionTracker(connectionScope, powerManager, myPeerID)
    private val packetBroadcaster = BluetoothPacketBroadcaster(connectionScope, connectionTracker, fragmentManager, myPeerID)
    
    // Delegate for component managers to call back to main manager
    @androidx.annotation.VisibleForTesting
    internal val componentDelegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(
            packet: BitchatPacket,
            peerID: String,
            device: BluetoothDevice?,
            ingressLinkID: String
        ) {
            device?.let { bluetoothDevice ->
                // Get current RSSI for this device and update if available
                val currentRSSI = connectionTracker.getBestRSSI(bluetoothDevice.address)
                if (currentRSSI != null) {
                    delegate?.onRSSIUpdated(bluetoothDevice.address, currentRSSI)
                }
            }

            // A validated ANNOUNCE claiming our identity must reach the tracker so the link is
            // closed fail-closed. Other reflected self packets remain ignored.
            if (peerID == myPeerID && packet.type != MessageType.ANNOUNCE.value) return

            delegate?.onPacketReceived(packet, peerID, device, ingressLinkID)
        }
        
        override fun onServerConnectionRegistered(device: BluetoothDevice) {
            // Enforce budgets even when a central never enables notifications.
            // This is resource admission, not a ready transport notification.
            enforceStrictLimits()
        }

        override fun onDeviceConnected(device: BluetoothDevice) {
            // Trigger limit enforcement immediately upon any new connection
            enforceStrictLimits()
            delegate?.onDeviceConnected(device)
        }

        override fun onDeviceDisconnected(device: BluetoothDevice, linkID: String?, peerID: String?) {
            onLinkClosed(device.address, linkID)
            delegate?.onDeviceDisconnected(device, linkID, peerID)
        }

        override fun onLinkClosed(deviceAddress: String, linkID: String?) {
            packetBroadcaster.onLinkDisconnected(deviceAddress, linkID)
            // A reconnection gets a new link ID: free the fragment slots this link opened.
            linkID?.let { fragmentManager?.onIngressLinkClosed(PacketProcessor.linkQuotaKey(it)) }
        }

        override fun onGattClientWriteComplete(deviceAddress: String, linkID: String, status: Int) {
            packetBroadcaster.onGattClientWriteComplete(deviceAddress, linkID, status)
        }

        override fun onGattServerNotificationComplete(deviceAddress: String, linkID: String?, status: Int) {
            packetBroadcaster.onGattServerNotificationComplete(deviceAddress, linkID, status)
        }
        
        override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
            delegate?.onRSSIUpdated(deviceAddress, rssi)
        }
    }
    
    // One limiter for both roles, so its shared ceiling covers every inbound link.
    private val inboundLimiter = InboundLinkRateLimiter()
    private val serverManager = BluetoothGattServerManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, inboundLimiter
    )
    private val clientManager = BluetoothGattClientManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, inboundLimiter
    )
    
    // Service state
    @Volatile private var isActive = false
    @Volatile private var transportReady = false
    /** Set once by [stopServices]: this instance never starts again, whatever Bluetooth does. */
    @Volatile private var stopped = false
    private val lifecycleEpoch = BluetoothLifecycleEpoch()
    private val componentLifecycle = BluetoothComponentLifecycleQueue(
        connectionScope = connectionScope,
        releaseComponents = ::releaseComponents
    )
    private var startupJob: Job? = null

    // Bluetooth turning off, or its system service dying, leaves every GATT object bound to the
    // dead stack: release them, and start again once the adapter is back on. Events run in order.
    @Volatile private var resumeWhenBluetoothOn = false
    private val adapterStates = Channel<Int>(Channel.UNLIMITED)
    private var adapterReceiver: BroadcastReceiver? = null

    // Delegate for callbacks
    var delegate: BluetoothConnectionManagerDelegate? = null
    
    // Public property for address-peer mapping
    val addressPeerMap get() = connectionTracker.addressPeerMap

    fun observePeerIfCurrent(
        deviceAddress: String,
        linkID: String,
        peerID: String,
        supportsDeterministicElection: Boolean = false
    ): Boolean {
        val observation = connectionTracker.observePeerIfCurrent(
            deviceAddress,
            linkID,
            peerID,
            supportsDeterministicElection
        )
        observation.linksToClose.forEach { link ->
            val loser = link.connection
            // Tie-break removes the loser from the tracker before Android reports disconnect, so
            // release per-link queues and fragment quota here without announcing peer loss: the
            // authenticated winner remains connected.
            packetBroadcaster.onLinkDisconnected(link.deviceAddress, loser.linkID)
            fragmentManager?.onIngressLinkClosed(PacketProcessor.linkQuotaKey(loser.linkID))
            if (loser.isClient) clientManager.disconnectLink(link.deviceAddress, loser.linkID)
            else serverManager.disconnectLink(loser.device, loser.linkID)
        }
        return observation.accepted
    }

    fun getCurrentLinkID(deviceAddress: String): String? =
        connectionTracker.getCurrentLinkID(deviceAddress)

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun isGattServerEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true })
    }

    private fun isGattClientEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true })
    }

    /**
     * A missing GATT completion is a dead exact-link generation, not a reason to try another link.
     * Route closure through the role owner so tracker, peer/Noise state and delegates stay aligned.
     */
    private fun onGattOperationTimedOut(
        deviceAddress: String,
        linkID: String,
        isClient: Boolean,
        device: BluetoothDevice
    ) {
        val peerID = connectionTracker.getPeerForLink(linkID)
        if (isClient) clientManager.disconnectLink(deviceAddress, linkID)
        else serverManager.disconnectLink(device, linkID)

        // Client finish normally performed this synchronously. The fallback is required for an
        // inbound link whose Bluetooth service is too broken to emit its disconnect callback.
        if (connectionTracker.cleanupDeviceConnectionIfCurrent(deviceAddress, linkID)) {
            fragmentManager?.onIngressLinkClosed(PacketProcessor.linkQuotaKey(linkID))
            if (peerID != null && !connectionTracker.isPeerConnected(peerID)) {
                delegate?.onDeviceDisconnected(device, linkID, peerID)
            } else {
                componentDelegate.onLinkClosed(deviceAddress, linkID)
            }
        }
    }

    init {
        packetBroadcaster.setLinkTimeoutHandler(::onGattOperationTimedOut)
        connectionScope.launch {
            for (state in adapterStates) {
                try { onAdapterState(state) } catch (e: Exception) { Log.w(TAG, "Bluetooth state $state not applied: ${e.message}") }
            }
        }
        connectionScope.launch {
            var previousMode: PowerManager.PowerMode? = null
            powerManager.profile.collect { profile ->
                val modeChanged = previousMode != null && previousMode != profile.mode
                previousMode = profile.mode
                if (!transportReady || !isBleTransportEnabled()) return@collect

                if (modeChanged && isGattServerEnabled()) {
                    serverManager.restartAdvertising()
                }
                clientManager.applyPowerProfile(profile)
            }
        }
        // Observe debug settings to enforce role state while active
        try {
            val dbg = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
            // Master transport enable/disable
            connectionScope.launch {
                dbg.bleEnabled.collect { enabled ->
                    if (enabled) return@collect
                    if (isActive) {
                        disableTransport()
                    }
                }
            }
            // Role enable/disable
            connectionScope.launch {
                dbg.gattServerEnabled.collect { enabled ->
                    if (!transportReady) return@collect
                    if (enabled && isBleTransportEnabled()) startServer() else stopServer()
                }
            }
            connectionScope.launch {
                dbg.gattClientEnabled.collect { enabled ->
                    if (!transportReady) return@collect
                    if (enabled && isBleTransportEnabled()) startClient() else stopClient()
                }
            }
            
            // Centralized limit enforcement on any setting change
            connectionScope.launch {
                combine(
                    dbg.maxConnectionsOverall,
                    dbg.maxServerConnections,
                    dbg.maxClientConnections
                ) { _, _, _ -> 
                    // We don't need the values here, we just need to trigger enforcement
                    Unit 
                }.collect {
                    if (transportReady) {
                        enforceStrictLimits()
                    }
                }
            }
        } catch (_: Exception) { }
    }
    
    /**
     * Centralized connection limit enforcement
     */
    private fun enforceStrictLimits() {
        if (!isActive) return
        
        try {
            val dbg = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
            val maxOverall = dbg.maxConnectionsOverall.value
            val maxServer = dbg.maxServerConnections.value
            val maxClient = dbg.maxClientConnections.value
            
            // Get list of connections to evict to satisfy all constraints
            val toEvict = connectionTracker.getConnectionsToEvict(maxOverall, maxServer, maxClient)
            
            if (toEvict.isNotEmpty()) {
                Log.i(TAG, "Enforcing limits (max: $maxOverall, s: $maxServer, c: $maxClient) - evicting ${toEvict.size} connections")
                
                toEvict.forEach { conn ->
                    if (conn.isClient) {
                        try {
                            conn.gatt?.disconnect()
                        } catch (_: SecurityException) {
                            Log.w(TAG, "Bluetooth permission revoked while enforcing connection limits")
                        } catch (_: Exception) { }
                    } else {
                        serverManager.disconnectDevice(conn.device)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing limits: ${e.message}")
        }
    }
    
    /**
     * Start all Bluetooth services with power optimization
     */
    @Synchronized fun startServices(): Boolean {
        if (stopped) return false
        if (transportReady) {
            delegate?.onTransportStateChanged(true)
            return true
        }
        if (isActive) return true
        if (!isBleTransportEnabled()) {
            Log.i(TAG, "BLE transport disabled by debug settings; not starting Bluetooth services")
            disableTransport()
            return false
        }
        
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }

        // Register even when Bluetooth is currently off. Otherwise no STATE_ON event can ever
        // reach this manager and a cold start with Bluetooth off remains wedged until process exit.
        registerAdapterReceiver()
        // Arm recovery before reading adapter state, so a concurrent STATE_ON broadcast cannot
        // slip through the check-then-set window and leave the manager waiting forever.
        resumeWhenBluetoothOn = initialResumeWhenBluetoothOn(bluetoothEnabled = false, stopped = stopped)
        
        val bluetoothEnabled = try {
            bluetoothAdapter?.isEnabled == true
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked before startup")
            false
        }
        if (!bluetoothEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or permission is unavailable")
            return false
        }
        
        try {
            isActive = true
            resumeWhenBluetoothOn = false
            val generation = lifecycleEpoch.begin()
            startupJob?.cancel()

        // set the adapter's name to our 8-character peerID for iOS privacy, TODO: Make this configurable
        // try {
        //     if (bluetoothAdapter?.name != myPeerID) {
        //         bluetoothAdapter?.name = myPeerID
        //         Log.d(TAG, "Set Bluetooth adapter name to peerID: $myPeerID for iOS compatibility.")
        //     }
        // } catch (se: SecurityException) {
        //     Log.e(TAG, "Missing BLUETOOTH_CONNECT permission to set adapter name.", se)
        // }

            // Start all component managers
            val startJob = componentLifecycle.newStart {
                    if (!isCurrentLifecycleGeneration(generation)) return@newStart
                    // Lifecycle commands are serialized, so a release registered by OFF/stop
                    // always finishes before a later start may mutate platform components.
                    connectionTracker.start()
                    if (!isCurrentLifecycleGeneration(generation)) return@newStart

                    powerManager.start()
                    if (!isCurrentLifecycleGeneration(generation)) return@newStart

                    val startServer = isGattServerEnabled()
                    val startClient = isGattClientEnabled()

                    if (startServer) {
                        if (!serverManager.start()) {
                            Log.e(TAG, "Failed to start server manager")
                            failLifecycleGeneration(generation)
                            return@newStart
                        }
                        if (!isCurrentLifecycleGeneration(generation)) return@newStart
                    } else {
                        Log.i(TAG, "GATT Server disabled by debug settings; not starting")
                    }

                    if (startClient) {
                        if (!clientManager.start()) {
                            Log.e(TAG, "Failed to start client manager")
                            failLifecycleGeneration(generation)
                            return@newStart
                        }
                        if (!isCurrentLifecycleGeneration(generation)) return@newStart
                    } else {
                        Log.i(TAG, "GATT Client disabled by debug settings; not starting")
                    }

                    synchronized(this@BluetoothConnectionManager) {
                        if (!isCurrentLifecycleGenerationLocked(generation)) return@synchronized
                        startupJob = null
                        transportReady = true
                        delegate?.onTransportStateChanged(true)
                    }
                    if (isCurrentLifecycleGeneration(generation)) {
                        Log.i(TAG, "Bluetooth services started successfully")
                    }
            }
            startupJob = startJob
            startJob.start()
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth services: ${e.message}")
            isActive = false
            transportReady = false
            return false
        }
    }

    /**
     * Disable BLE without cancelling this manager's coroutine scope, so it can be re-enabled.
     */
    fun disableTransport() {
        Log.i(TAG, "Disabling BLE transport")
        synchronized(this) {
            val active = isActive
            isActive = false
            transportReady = false
            resumeWhenBluetoothOn = false
            lifecycleEpoch.invalidate()
            val old = startupJob
            old?.cancel()
            startupJob = null
            if (active) delegate?.onTransportStateChanged(false)
            // Register the release before dropping the same lock startServices uses. A concurrent
            // restart must therefore observe this release as its predecessor.
            componentLifecycle.newRelease(old).start()
        }
    }

    private fun releaseComponents() {
        clientManager.stop()
        serverManager.stop()
        connectionTracker.stop()
    }
    
    /**
     * Stop all Bluetooth services with proper cleanup
     */
    fun stopServices() {
        synchronized(this) {
            val active = isActive
            stopped = true
            isActive = false
            transportReady = false
            resumeWhenBluetoothOn = false
            lifecycleEpoch.invalidate()
            val old = startupJob
            old?.cancel()
            startupJob = null
            unregisterAdapterReceiver()
            if (active) delegate?.onTransportStateChanged(false)
            componentLifecycle.newRelease(old) {
                connectionScope.cancel()
                Log.i(TAG, "All Bluetooth services stopped")
            }.start()
        }
    }

    private fun onAdapterState(state: Int) {
        val reaction = synchronized(this) {
            if (stopped) return
            adapterReaction(state, isActive, resumeWhenBluetoothOn)
        }
        when (reaction) {
            AdapterReaction.RELEASE -> {
                Log.i(TAG, "Bluetooth turning off: releasing GATT server and client")
                synchronized(this) {
                    if (stopped) return
                    resumeWhenBluetoothOn = true
                    isActive = false
                    transportReady = false
                    lifecycleEpoch.invalidate()
                    val old = startupJob
                    old?.cancel()
                    startupJob = null
                    delegate?.onTransportStateChanged(false)
                    componentLifecycle.newRelease(old).start()
                }
            }
            AdapterReaction.RESTART -> {
                Log.i(TAG, "Bluetooth back on: restarting GATT server and client")
                synchronized(this) {
                    if (stopped) return
                    resumeWhenBluetoothOn = false
                }
                startServices()
            }
            AdapterReaction.NONE -> Unit
        }
    }

    @Synchronized private fun registerAdapterReceiver() {
        if (stopped || adapterReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                adapterStates.trySend(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
            }
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        adapterReceiver = receiver
    }

    @Synchronized private fun unregisterAdapterReceiver() {
        val receiver = adapterReceiver ?: return
        adapterReceiver = null
        try { context.applicationContext.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
    }

    /**
     * Indicates whether this instance can be safely reused for a future start.
     * Returns false if its coroutine scope has been cancelled.
     */
    fun isReusable(): Boolean {
        return connectionScope.isActive
    }

    private fun isCurrentLifecycleGeneration(generation: Long): Boolean = synchronized(this) {
        isCurrentLifecycleGenerationLocked(generation)
    }

    private fun isCurrentLifecycleGenerationLocked(generation: Long): Boolean =
        !stopped && isActive && lifecycleEpoch.isCurrent(generation)

    private fun failLifecycleGeneration(generation: Long) {
        synchronized(this) {
            if (!isCurrentLifecycleGenerationLocked(generation)) return
            isActive = false
            transportReady = false
            lifecycleEpoch.invalidate()
            startupJob = null
            releaseComponents()
            delegate?.onTransportStateChanged(false)
        }
    }

    /**
     * Broadcast packet to connected devices with connection limit enforcement
     * Automatically fragments large packets to fit within BLE MTU limits
     */
    fun broadcastPacket(routed: RoutedPacket): Boolean {
        if (!transportReady || !isBleTransportEnabled()) return false

        return packetBroadcaster.broadcastPacket(
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    suspend fun broadcastPacketAndAwaitAcceptance(routed: RoutedPacket, authorized: () -> Boolean): Boolean {
        if (!transportReady || !isBleTransportEnabled()) return false
        return packetBroadcaster.broadcastPacketAndAwaitAcceptance(routed, serverManager.getGattServer(),
            serverManager.getCharacteristic(), authorized)
    }

    suspend fun broadcastPacketAndAwaitOutcome(routed: RoutedPacket, authorized: () -> Boolean): BoundSendOutcome {
        if (!transportReady || !isBleTransportEnabled()) return BoundSendOutcome.NOT_SENT
        return packetBroadcaster.broadcastPacketAndAwaitOutcome(routed, serverManager.getGattServer(),
            serverManager.getCharacteristic(), authorized)
    }

    suspend fun broadcastControlPacketAndAwaitAcceptance(routed: RoutedPacket): Boolean {
        if (!transportReady || !isBleTransportEnabled()) return false

        return packetBroadcaster.broadcastControlPacketAndAwaitAcceptance(
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (!transportReady || !isBleTransportEnabled()) return false
        return packetBroadcaster.sendToPeer(
            peerID,
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    /**
     * Send a packet directly to a specific peer, without broadcasting to others.
     */
    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        if (!transportReady || !isBleTransportEnabled()) return false
        return packetBroadcaster.sendPacketToPeer(
            RoutedPacket(packet),
            peerID,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun sendPacketToLink(deviceAddress: String, linkID: String, packet: BitchatPacket): Boolean {
        if (!transportReady || !isBleTransportEnabled()) return false
        return packetBroadcaster.sendPacketToLink(
            RoutedPacket(packet),
            deviceAddress,
            linkID,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }
    

    // Expose role controls for debug UI
    fun startServer() {
        if (!transportReady || !isBleTransportEnabled()) return
        connectionScope.launch { if (isGattServerEnabled()) serverManager.start() }
    }
    fun stopServer() { connectionScope.launch { serverManager.stop() } }
    fun startClient() {
        if (!transportReady || !isBleTransportEnabled()) return
        connectionScope.launch { if (isGattClientEnabled()) clientManager.start() }
    }
    fun stopClient() { connectionScope.launch { clientManager.stop() } }

    // Inject nickname resolver for broadcaster logs
    fun setNicknameResolver(resolver: (String) -> String?) { packetBroadcaster.setNicknameResolver(resolver) }

    // Debug snapshots for connected devices
    fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> {
        return try {
            connectionTracker.getConnectedDevices().values.map { dc ->
                val rssi = if (dc.rssi != Int.MIN_VALUE) dc.rssi else null
                Triple(dc.device.address, dc.isClient, rssi)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isClientConnection(address: String): Boolean? {
        return try { connectionTracker.getConnectedDevices()[address]?.isClient } catch (e: Exception) { null }
    }

    /**
     * Public: connect/disconnect helpers for debug UI
     */
    fun connectToAddress(address: String): Boolean {
        if (!transportReady || !isBleTransportEnabled()) return false
        return clientManager.connectToAddress(address)
    }
    fun disconnectAddress(address: String) { connectionTracker.disconnectDevice(address) }


    // Optionally disconnect all connections (server and client)
    fun disconnectAll() {
        connectionScope.launch {
            // Stop and restart to force disconnects
            clientManager.stop()
            serverManager.stop()
            delay(200)
            if (transportReady && isBleTransportEnabled()) {
                // Restart managers if service is active
                if (isGattServerEnabled()) serverManager.start()
                if (isGattClientEnabled()) clientManager.start()
            }
        }
    }


    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectionTracker.getConnectedDeviceCount()
    
    /**
     * Get debug information including power management
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Bluetooth Connection Manager ===")
            appendLine("Active: $isActive")
            val bluetoothEnabled = try {
                bluetoothAdapter?.isEnabled?.toString() ?: "Unavailable"
            } catch (_: SecurityException) {
                "[Permission required]"
            }
            appendLine("Bluetooth Enabled: $bluetoothEnabled")
            appendLine("Has Permissions: ${permissionManager.hasBluetoothPermissions()}")
            appendLine("GATT Server Active: ${serverManager.getGattServer() != null}")
            appendLine()
            appendLine(powerManager.getPowerInfo())
            appendLine()
            appendLine(connectionTracker.getDebugInfo())
        }
    }
    
}

/**
 * Delegate interface for Bluetooth connection manager callbacks
 */
interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(
        packet: BitchatPacket,
        peerID: String,
        device: BluetoothDevice?,
        ingressLinkID: String
    )
    /** A server link occupies a connection budget before CCCD readiness. */
    fun onServerConnectionRegistered(device: BluetoothDevice) = Unit
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice, linkID: String?, peerID: String?)
    /** Release per-physical-link queues/quotas without declaring the logical peer offline. */
    fun onLinkClosed(deviceAddress: String, linkID: String?) = Unit
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
    fun onGattClientWriteComplete(deviceAddress: String, linkID: String, status: Int) = Unit
    fun onGattServerNotificationComplete(deviceAddress: String, linkID: String?, status: Int) = Unit
    fun onTransportStateChanged(active: Boolean) = Unit
}

internal enum class AdapterReaction { RELEASE, RESTART, NONE }

/** What a Bluetooth adapter [state] asks of a manager that is [active], or waiting to resume. */
internal fun adapterReaction(state: Int, active: Boolean, resumePending: Boolean): AdapterReaction = when (state) {
    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
        if (active) AdapterReaction.RELEASE else AdapterReaction.NONE
    BluetoothAdapter.STATE_ON -> if (resumePending && !active) AdapterReaction.RESTART else AdapterReaction.NONE
    else -> AdapterReaction.NONE
}

/** A reusable manager started while Bluetooth is off must keep listening for STATE_ON. */
internal fun initialResumeWhenBluetoothOn(bluetoothEnabled: Boolean, stopped: Boolean): Boolean =
    !bluetoothEnabled && !stopped

/** Monotonic ownership token for asynchronous manager starts. */
internal class BluetoothLifecycleEpoch {
    private var value = 0L

    @Synchronized fun begin(): Long = ++value
    @Synchronized fun invalidate(): Long = ++value
    @Synchronized fun isCurrent(token: Long): Boolean = token == value
}

/**
 * Serializes platform component mutations across starts and releases.
 *
 * Jobs are returned lazy so the owner can publish its lifecycle state and job reference under the
 * same lock before execution begins. A release becomes the predecessor of every later start at
 * construction time, closing the disable -> concurrent start registration gap.
 */
internal class BluetoothComponentLifecycleQueue(
    private val connectionScope: CoroutineScope,
    private val releaseComponents: suspend () -> Unit
) {
    private val queueLock = Any()
    private val componentMutex = Mutex()
    private var releaseTail: Job? = null

    fun newStart(startComponents: suspend () -> Unit): Job = synchronized(queueLock) {
        val predecessor = releaseTail
        connectionScope.launch(start = CoroutineStart.LAZY) {
            predecessor?.join()
            componentMutex.withLock { startComponents() }
        }
    }

    fun newRelease(
        startupToCancel: Job?,
        afterRelease: () -> Unit = {}
    ): Job = synchronized(queueLock) {
        val predecessor = releaseTail
        connectionScope.launch(start = CoroutineStart.LAZY) {
            predecessor?.join()
            startupToCancel?.cancelAndJoin()
            componentMutex.withLock { releaseComponents() }
            afterRelease()
        }.also { releaseTail = it }
    }
}
