package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val inboundLimiter: InboundLinkRateLimiter = InboundLinkRateLimiter()
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
        // Self-healing advertising recovery tuning
        private const val ADVERTISE_RETRY_BASE_MS = 3_000L      // base backoff for transient advertise failures
        private const val ADVERTISE_MAX_RETRY_DELAY_MS = 30_000L // cap on backoff delay

        internal fun advertiseData(): AdvertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        // A stable identifier in broadcast data lets any passive scanner follow a phone
        // across MAC rotations and days. Peers deduplicate through the authenticated announce.
        internal fun scanResponseData(): AdvertiseData = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    // Android returns no advertiser while Bluetooth is off: read it at each use, not at construction.
    private val bleAdvertiser: BluetoothLeAdvertiser? get() = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private val serverLinkIDs = ConcurrentHashMap<String, String>()
    private val closingServerLinks = ConcurrentHashMap<String, String>()
    private var characteristic: BluetoothGattCharacteristic? = null
    private data class ActiveAdvertising(
        val advertiser: BluetoothLeAdvertiser,
        val callback: AdvertiseCallback
    )
    @Volatile private var activeAdvertising: ActiveAdvertising? = null
    private val advertiseLock = Any()
    private var advertiseRetryCount = 0
    
    // State management
    @Volatile private var isActive = false
    private val serverStateLock = Any()
    private var serverGeneration = 0L
    @Volatile private var serviceReady = false
    private data class DetachedServer(
        val server: BluetoothGattServer?,
        val advertising: ActiveAdvertising?,
        val connections: List<BluetoothConnectionTracker.LinkToClose>
    )

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun isServerRoleEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true })
    }

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while disconnecting server device")
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting server device: ${e.message}")
        }
    }

    /** Close only the exact inbound GATT generation rejected by authenticated tie-break. */
    fun disconnectLink(device: BluetoothDevice, linkID: String) {
        val shouldClose = synchronized(serverStateLock) {
            if (serverLinkIDs[device.address] != linkID) return@synchronized false
            closingServerLinks[device.address] = linkID
            connectionTracker.removeSubscribedDevice(device)
            true
        }
        if (!shouldClose) return
        disconnectDevice(device)
    }
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        // Respect debug setting
        if (!isServerRoleEnabled()) {
            Log.i(TAG, "Server start skipped: BLE/GATT Server disabled in debug settings")
            return false
        }

        if (isActive) {
            return true
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
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        val generation = synchronized(serverStateLock) {
            if (isActive) return true
            isActive = true
            serviceReady = false
            ++serverGeneration
        }
        connectionScope.launch { setupGattServer(generation) }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() = stopGeneration(null)

    private fun stopGeneration(expectedGeneration: Long?) {
        val detached = synchronized(serverStateLock) {
            if (expectedGeneration != null && expectedGeneration != serverGeneration) return
            isActive = false
            serviceReady = false
            ++serverGeneration
            val server = gattServer
            gattServer = null
            characteristic = null
            serverLinkIDs.clear()
            closingServerLinks.clear()
            val advertising = activeAdvertising
            activeAdvertising = null
            DetachedServer(server, advertising,
                connectionTracker.getAllTrackedLinks().filter { !it.connection.isClient })
        }
        stopAdvertising(detached.advertising)
        val server = detached.server
        if (server != null) {
            detached.connections.forEach { link ->
                try {
                    server.cancelConnection(link.connection.device)
                } catch (_: SecurityException) {
                    Log.w(TAG, "Bluetooth permission revoked while cancelling server connection")
                } catch (e: Exception) {
                    Log.w(TAG, "Error cancelling server connection: ${e.message}")
                }
            }
            closeServer(server)
        }
        detached.connections.forEach { link ->
            if (connectionTracker.cleanupDeviceConnectionIfCurrent(
                    link.deviceAddress,
                    link.connection.linkID
                )
            ) {
                connectionTracker.removeSubscribedDevice(link.connection.device)
                val peerID = link.connection.peerID
                if (peerID != null && !connectionTracker.isPeerConnected(peerID)) {
                    delegate?.onDeviceDisconnected(
                        link.connection.device,
                        link.connection.linkID,
                        peerID
                    )
                } else {
                    delegate?.onLinkClosed(link.deviceAddress, link.connection.linkID)
                }
            }
        }
        Log.i(TAG, "GATT server stopped")
    }

    private fun closeServer(server: BluetoothGattServer) {
        try {
            server.close()
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while closing server")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean = synchronized(serverStateLock) {
        isActive && serverGeneration == generation
    }

    private fun callbackServer(generation: Long): BluetoothGattServer? {
        if (!isCurrentGeneration(generation)) return null
        if (!permissionManager.hasBluetoothPermissions()) {
            stopGeneration(generation)
            return null
        }
        return synchronized(serverStateLock) {
            gattServer.takeIf { isActive && serverGeneration == generation }
        }
    }

    private fun sendResponse(
        generation: Long, device: BluetoothDevice, requestID: Int, status: Int
    ): Boolean {
        val server = callbackServer(generation) ?: return false
        return try {
            server.sendResponse(device, requestID, status, 0, null)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while responding to GATT request")
            stopGeneration(generation)
            false
        } catch (_: Exception) {
            Log.w(TAG, "GATT response failed")
            stopGeneration(generation)
            false
        }
    }

    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    private fun setupGattServer(generation: Long) {
        if (!isCurrentGeneration(generation)) return
        if (!permissionManager.hasBluetoothPermissions()) {
            stopGeneration(generation)
            return
        }
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (callbackServer(generation) == null) return
                val address = device.address
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) return
                        val registered = synchronized(serverStateLock) {
                            if (!isActive || serverGeneration != generation) return
                            if (closingServerLinks.containsKey(address)) {
                                return@synchronized false
                            }
                            val existing = serverLinkIDs[address]
                            if (existing != null && connectionTracker.hasLink(address, existing)) {
                                return@synchronized false
                            }
                            val linkID = UUID.randomUUID().toString()
                            serverLinkIDs[address] = linkID
                            connectionTracker.addServerConnection(address,
                                BluetoothConnectionTracker.DeviceConnection(device = device,
                                    rssi = connectionTracker.getBestRSSI(address) ?: Int.MIN_VALUE,
                                    isClient = false, linkID = linkID))
                            true
                        }
                        // A central can remain connected without ever writing CCCD.
                        // Enforce connection budgets now, separately from readiness.
                        if (registered && isCurrentGeneration(generation)) {
                            delegate?.onServerConnectionRegistered(device)
                        }
                        // Readiness is announced only after the CCCD response is accepted.
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val disconnected = synchronized(serverStateLock) {
                            if (!isActive || serverGeneration != generation) return
                            val linkID = serverLinkIDs.remove(address)
                            closingServerLinks.remove(address)
                            val peerID = linkID?.let(connectionTracker::getPeerForLink)
                            val removed = linkID != null && connectionTracker.cleanupDeviceConnectionIfCurrent(address, linkID)
                            if (removed) connectionTracker.removeSubscribedDevice(device)
                            Triple(linkID, peerID, removed)
                        }
                        if (disconnected.third) {
                            val peerID = disconnected.second
                            if (peerID != null && connectionTracker.isPeerConnected(peerID)) {
                                delegate?.onLinkClosed(address, disconnected.first)
                            } else if (peerID != null) {
                                delegate?.onDeviceDisconnected(device, disconnected.first, peerID)
                            } else {
                                delegate?.onLinkClosed(address, disconnected.first)
                            }
                        }
                    }
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (callbackServer(generation) == null || service.uuid != AppConstants.Mesh.Gatt.SERVICE_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stopGeneration(generation)
                    return
                }
                synchronized(serverStateLock) {
                    if (!isActive || serverGeneration != generation) return
                    serviceReady = true
                }
                startAdvertising()
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (callbackServer(generation) == null || !serviceReady) return
                val address = device.address
                val linkID = synchronized(serverStateLock) {
                    serverLinkIDs[address]?.takeIf { current ->
                        !closingServerLinks.containsKey(address) &&
                            connectionTracker.hasLink(address, current)
                    }
                }
                val valid = characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID &&
                    !preparedWrite && offset == 0 && linkID != null
                // Rate-limited per link before any decoding or signature work.
                val packet = if (valid && inboundLimiter.tryAcquire(address, linkID)) BitchatPacket.fromBinaryData(value) else null
                if (responseNeeded && !sendResponse(generation, device, requestId,
                        if (packet != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE)) return
                if (packet == null || linkID == null || callbackServer(generation) == null) return
                val stillAccepted = synchronized(serverStateLock) {
                    isActive && serverGeneration == generation &&
                        serverLinkIDs[address] == linkID &&
                        !closingServerLinks.containsKey(address) &&
                        connectionTracker.hasLink(address, linkID)
                }
                if (!stillAccepted) return
                val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                delegate?.onPacketReceived(packet, peerID, device, linkID)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (callbackServer(generation) == null || !serviceReady) return
                val address = device.address
                val linkID = synchronized(serverStateLock) {
                    serverLinkIDs[address]?.takeIf { current ->
                        !closingServerLinks.containsKey(address) &&
                            connectionTracker.hasLink(address, current)
                    }
                }
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)
                val disable = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.contentEquals(value)
                val valid = linkID != null && descriptor.uuid == AppConstants.Mesh.Gatt.DESCRIPTOR_UUID &&
                    descriptor.characteristic?.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID &&
                    !preparedWrite && offset == 0 && (enable || disable)
                if (responseNeeded && !sendResponse(generation, device, requestId,
                        if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE)) return
                if (!valid) return
                synchronized(serverStateLock) {
                    if (!isActive || serverGeneration != generation || serverLinkIDs[address] != linkID ||
                        closingServerLinks.containsKey(address) ||
                        !connectionTracker.hasLink(address, linkID)) return
                    if (enable) connectionTracker.addSubscribedDevice(device)
                    else connectionTracker.removeSubscribedDevice(device)
                }
                if (enable) connectionScope.launch {
                    delay(100)
                    val stillAccepted = synchronized(serverStateLock) {
                        isActive && serverGeneration == generation &&
                            serverLinkIDs[address] == linkID &&
                            !closingServerLinks.containsKey(address) &&
                            connectionTracker.hasLink(address, linkID)
                    }
                    if (stillAccepted && callbackServer(generation) != null &&
                        connectionTracker.getSubscribedDevices().contains(device)) {
                        delegate?.onDeviceConnected(device)
                    }
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                if (callbackServer(generation) == null) return
                delegate?.onGattServerNotificationComplete(device.address, serverLinkIDs[device.address], status)
            }
        }
        val opened = try {
            bluetoothManager.openGattServer(context, serverCallback)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while opening GATT server")
            null
        } catch (_: Exception) {
            Log.w(TAG, "GATT server open failed")
            null
        }
        if (opened == null) {
            stopGeneration(generation)
            return
        }
        val newCharacteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        newCharacteristic.addDescriptor(BluetoothGattDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service = BluetoothGattService(AppConstants.Mesh.Gatt.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(newCharacteristic)
        val adopted = synchronized(serverStateLock) {
            if (!isActive || serverGeneration != generation) false else {
                gattServer = opened
                characteristic = newCharacteristic
                true
            }
        }
        if (!adopted) {
            closeServer(opened)
            return
        }
        val accepted = try {
            opened.addService(service)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while publishing GATT service")
            false
        } catch (_: Exception) {
            Log.w(TAG, "GATT service publication failed")
            false
        }
        if (!accepted) stopGeneration(generation)
    }

    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()

        // Guard conditions – never throw here to avoid crashing the app from a background coroutine
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.w(TAG, "Not starting advertising: missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Not starting advertising: bluetoothAdapter is null")
            return
        }
        if (!isActive || !serviceReady) {
            return
        }
        if (!enabled) {
            Log.d(TAG, "Not starting advertising: GATT Server disabled via debug settings")
            return
        }
        val advertiser = bleAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "Not starting advertising: BLE advertiser not available on this device")
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Not starting advertising: multiple advertisement not supported on this device")
            return
        }

        val settings = powerManager.getAdvertiseSettings()
        
        val data = advertiseData()
        val scanResponse = scanResponseData()

        val generation = synchronized(serverStateLock) {
            if (!isActive || !serviceReady) return
            serverGeneration
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (!isCurrentGeneration(generation)) return
                advertiseRetryCount = 0
                val mode = try {
                    powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]
                } catch (_: Exception) { "unknown" }
                Log.i(TAG, "Advertising started (power mode: $mode)")
            }

            override fun onStartFailure(errorCode: Int) {
                // A set a later start replaced must not restart the one now running.
                if (!isCurrentGeneration(generation) || activeAdvertising?.callback !== this) return
                Log.e(TAG, "Advertising failed: $errorCode")
                // Previously this only logged, so if advertising failed this device became
                // undiscoverable until a manual BLE toggle. Retry transient failures with backoff.
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> Unit // already advertising, no retry
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> Unit // config issue, not retrying
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Unit // unsupported, not retrying
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                        scheduleAdvertiseRestart("too-many-advertisers", generation)
                    }
                    ADVERTISE_FAILED_INTERNAL_ERROR -> {
                        scheduleAdvertiseRestart("internal-error", generation)
                    }
                    else -> {
                        scheduleAdvertiseRestart("unknown-$errorCode", generation)
                    }
                }
            }
        }
        
        // Two overlapping restarts would each start a set and keep only the last: one at a time,
        // stop whichever set is still recorded, so one advertiser, and one address, is ever running.
        synchronized(advertiseLock) {
            val previous = synchronized(serverStateLock) {
                if (!isActive || serverGeneration != generation) return
                activeAdvertising.also {
                    activeAdvertising = ActiveAdvertising(advertiser, callback)
                }
            }
            if (previous != null) stopAdvertising(previous)
            try {
                advertiser.startAdvertising(settings, data, scanResponse, callback)
                if (!isCurrentGeneration(generation)) {
                    val stale = synchronized(serverStateLock) {
                        activeAdvertising?.takeIf { it.callback === callback }?.also {
                            activeAdvertising = null
                        }
                    }
                    stopAdvertising(stale)
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException starting advertising (missing permission?): ${se.message}")
                synchronized(serverStateLock) {
                    if (activeAdvertising?.callback === callback) activeAdvertising = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting advertising: ${e.message}")
                synchronized(serverStateLock) {
                    if (activeAdvertising?.callback === callback) activeAdvertising = null
                }
            }
        }
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        val active = synchronized(advertiseLock) {
            synchronized(serverStateLock) {
                activeAdvertising.also { activeAdvertising = null }
            }
        }
        stopAdvertising(active)
    }

    @Suppress("DEPRECATION")
    private fun stopAdvertising(active: ActiveAdvertising?) {
        if (active == null) return
        try {
            active.advertiser.stopAdvertising(active.callback)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while stopping advertising")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
    }
    
    /**
     * Schedule an advertising restart with incremental backoff after a transient failure.
     */
    private fun scheduleAdvertiseRestart(reason: String, generation: Long) {
        advertiseRetryCount++
        val delayMs = (ADVERTISE_RETRY_BASE_MS * advertiseRetryCount).coerceAtMost(ADVERTISE_MAX_RETRY_DELAY_MS)
        Log.w(TAG, "Scheduling advertising restart in ${delayMs}ms (attempt $advertiseRetryCount, reason=$reason)")
        connectionScope.launch {
            delay(delayMs)
            if (isCurrentGeneration(generation) && isServerRoleEnabled()) {
                stopAdvertising()
                delay(100)
                if (isCurrentGeneration(generation)) startAdvertising()
            }
        }
    }

    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        val generation = synchronized(serverStateLock) { serverGeneration }
        connectionScope.launch {
            if (!isCurrentGeneration(generation)) return@launch
            stopAdvertising()
            delay(100)
            if (isCurrentGeneration(generation)) startAdvertising()
        }
    }
}
