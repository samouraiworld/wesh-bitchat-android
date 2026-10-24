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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

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
    private val connectionTracker = BluetoothConnectionTracker(connectionScope, powerManager)
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

            if (peerID == myPeerID) return // Ignore messages from self

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
            packetBroadcaster.onLinkDisconnected(device.address, linkID)
            // A reconnection gets a new link ID: free the fragment slots this link opened.
            linkID?.let { fragmentManager?.onIngressLinkClosed(PacketProcessor.linkQuotaKey(it)) }
            delegate?.onDeviceDisconnected(device, linkID, peerID)
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
    /** Set once by [stopServices]: this instance never starts again, whatever Bluetooth does. */
    @Volatile private var stopped = false

    // Bluetooth turning off, or its system service dying, leaves every GATT object bound to the
    // dead stack: release them, and start again once the adapter is back on. Events run in order.
    @Volatile private var resumeWhenBluetoothOn = false
    private val adapterStates = Channel<Int>(Channel.UNLIMITED)
    private var adapterReceiver: BroadcastReceiver? = null

    // Delegate for callbacks
    var delegate: BluetoothConnectionManagerDelegate? = null
    
    // Public property for address-peer mapping
    val addressPeerMap get() = connectionTracker.addressPeerMap

    fun observePeerIfCurrent(deviceAddress: String, linkID: String, peerID: String): Boolean =
        connectionTracker.observePeerIfCurrent(deviceAddress, linkID, peerID)

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

    init {
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
                if (!isActive || !isBleTransportEnabled()) return@collect

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
                    if (!isActive) return@collect
                    if (enabled && isBleTransportEnabled()) startServer() else stopServer()
                }
            }
            connectionScope.launch {
                dbg.gattClientEnabled.collect { enabled ->
                    if (!isActive) return@collect
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
                    if (isActive) {
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
    fun startServices(): Boolean {
        if (!isBleTransportEnabled()) {
            Log.i(TAG, "BLE transport disabled by debug settings; not starting Bluetooth services")
            disableTransport()
            return false
        }
        
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
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
            registerAdapterReceiver()

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
            connectionScope.launch {
                // Start connection tracker first
                connectionTracker.start()
                
                // Start power manager
                powerManager.start()
                
                // Start server/client based on debug settings
                val startServer = isGattServerEnabled()
                val startClient = isGattClientEnabled()

                if (startServer) {
                    if (!serverManager.start()) {
                        Log.e(TAG, "Failed to start server manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                } else {
                    Log.i(TAG, "GATT Server disabled by debug settings; not starting")
                }

                if (startClient) {
                    if (!clientManager.start()) {
                        Log.e(TAG, "Failed to start client manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                } else {
                    Log.i(TAG, "GATT Client disabled by debug settings; not starting")
                }
                
                Log.i(TAG, "Bluetooth services started successfully")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth services: ${e.message}")
            isActive = false
            return false
        }
    }

    /**
     * Disable BLE without cancelling this manager's coroutine scope, so it can be re-enabled.
     */
    fun disableTransport() {
        Log.i(TAG, "Disabling BLE transport")
        isActive = false
        resumeWhenBluetoothOn = false
        connectionScope.launch { releaseComponents() }
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
            stopped = true
            isActive = false
            resumeWhenBluetoothOn = false
            unregisterAdapterReceiver()
        }

        connectionScope.launch {
            releaseComponents()

            // Cancel the coroutine scope
            connectionScope.cancel()
            
            Log.i(TAG, "All Bluetooth services stopped")
        }
    }

    @Synchronized private fun onAdapterState(state: Int) {
        if (stopped) return
        when (adapterReaction(state, isActive, resumeWhenBluetoothOn)) {
            AdapterReaction.RELEASE -> {
                Log.i(TAG, "Bluetooth turning off: releasing GATT server and client")
                resumeWhenBluetoothOn = true
                isActive = false
                releaseComponents()
            }
            AdapterReaction.RESTART -> {
                Log.i(TAG, "Bluetooth back on: restarting GATT server and client")
                resumeWhenBluetoothOn = false
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
    
    /**
     * Broadcast packet to connected devices with connection limit enforcement
     * Automatically fragments large packets to fit within BLE MTU limits
     */
    fun broadcastPacket(routed: RoutedPacket): Boolean {
        if (!isActive || !isBleTransportEnabled()) return false

        return packetBroadcaster.broadcastPacket(
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    suspend fun broadcastPacketAndAwaitAcceptance(routed: RoutedPacket, authorized: () -> Boolean): Boolean {
        if (!isActive || !isBleTransportEnabled()) return false
        return packetBroadcaster.broadcastPacketAndAwaitAcceptance(routed, serverManager.getGattServer(),
            serverManager.getCharacteristic(), authorized)
    }

    suspend fun broadcastPacketAndAwaitOutcome(routed: RoutedPacket, authorized: () -> Boolean): BoundSendOutcome {
        if (!isActive || !isBleTransportEnabled()) return BoundSendOutcome.NOT_SENT
        return packetBroadcaster.broadcastPacketAndAwaitOutcome(routed, serverManager.getGattServer(),
            serverManager.getCharacteristic(), authorized)
    }

    suspend fun broadcastControlPacketAndAwaitAcceptance(routed: RoutedPacket): Boolean {
        if (!isActive || !isBleTransportEnabled()) return false

        return packetBroadcaster.broadcastControlPacketAndAwaitAcceptance(
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (!isActive || !isBleTransportEnabled()) return false
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
        if (!isActive || !isBleTransportEnabled()) return false
        return packetBroadcaster.sendPacketToPeer(
            RoutedPacket(packet),
            peerID,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun sendPacketToLink(deviceAddress: String, linkID: String, packet: BitchatPacket): Boolean {
        if (!isActive || !isBleTransportEnabled()) return false
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
        if (!isActive || !isBleTransportEnabled()) return
        connectionScope.launch { if (isGattServerEnabled()) serverManager.start() }
    }
    fun stopServer() { connectionScope.launch { serverManager.stop() } }
    fun startClient() {
        if (!isActive || !isBleTransportEnabled()) return
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
        if (!isActive || !isBleTransportEnabled()) return false
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
            if (isActive && isBleTransportEnabled()) {
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
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
    fun onGattClientWriteComplete(deviceAddress: String, linkID: String, status: Int) = Unit
    fun onGattServerNotificationComplete(deviceAddress: String, linkID: String?, status: Int) = Unit
}

internal enum class AdapterReaction { RELEASE, RESTART, NONE }

/** What a Bluetooth adapter [state] asks of a manager that is [active], or waiting to resume. */
internal fun adapterReaction(state: Int, active: Boolean, resumePending: Boolean): AdapterReaction = when (state) {
    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
        if (active) AdapterReaction.RELEASE else AdapterReaction.NONE
    BluetoothAdapter.STATE_ON -> if (resumePending && !active) AdapterReaction.RESTART else AdapterReaction.NONE
    else -> AdapterReaction.NONE
}
