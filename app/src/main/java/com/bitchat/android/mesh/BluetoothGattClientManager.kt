package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.os.Build
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlinx.coroutines.Job
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.ui.debug.DebugScanResult

/**
 * Manages GATT client operations, scanning, and client-side connections
 */
class BluetoothGattClientManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val inboundLimiter: InboundLinkRateLimiter = InboundLinkRateLimiter(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    
    companion object {
        private const val TAG = "BluetoothGattClientManager"
        // Self-healing scan recovery tuning
        private const val SCAN_RETRY_BASE_MS = 3_000L          // base backoff for transient scan failures
        private const val SCAN_MAX_RETRY_DELAY_MS = 30_000L    // cap on backoff delay
        private const val SCAN_WATCHDOG_INTERVAL_MS = 30_000L  // how often to verify the scanner is alive
        private const val SCAN_STALE_RESULT_MS = 120_000L      // force a scan restart if no results for this long
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    // Android returns no scanner while Bluetooth is off: read it at each use, not at construction.
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun isClientRoleEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true })
    }
    
    /**
     * Public: Connect to a device by MAC address (for debug UI)
     */
    fun connectToAddress(deviceAddress: String): Boolean {
        if (!isClientRoleEnabled()) {
            Log.d(TAG, "connectToAddress skipped: BLE client disabled")
            return false
        }
        if (!permissionManager.hasBluetoothPermissions()) return false
        return try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: return false
            connectToDevice(device, connectionTracker.getBestRSSI(deviceAddress) ?: -50)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked before connecting")
            false
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Invalid Bluetooth address")
            false
        }
    }

    // Scan management
    private data class ActiveScan(
        val scanner: BluetoothLeScanner,
        val callback: ScanCallback
    )
    private val scanLock = Any()
    @Volatile private var activeScan: ActiveScan? = null
    
    // Scan rate limiting to prevent "scanning too frequently" errors
    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    @Volatile private var isCurrentlyScanning = false
    private val scanRateLimit = 5000L // Minimum 5 seconds between scan start attempts

    // Self-healing scan state.
    // scanningDesired distinguishes "we want to be scanning but it isn't running" (a fault to recover
    // from) from "scanning is intentionally off" (e.g. duty-cycle OFF window or client disabled).
    @Volatile private var scanningDesired = false
    @Volatile private var lastScanResultTime = 0L
    private var scanRetryCount = 0
    private var scanWatchdogJob: Job? = null
    private var scanDutyCycleJob: Job? = null
    
    // State management
    @Volatile private var isActive = false
    private val clientLock = Any()
    private val clientAttempts = mutableMapOf<String, ClientAttempt>()
    private enum class ClientPhase { CONNECTING, MTU, DISCOVERY, CCCD, READY, CLOSED }
    
    /**
     * Start client manager
     */
    fun start(): Boolean {
        // Respect debug setting
        if (!isClientRoleEnabled()) {
            Log.i(TAG, "Client start skipped: BLE/GATT Client disabled in debug settings")
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
        
        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            applyPowerProfile(powerManager.profile.value)
        }
        
        return true
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        isActive = false
        synchronized(clientLock) {
            clientAttempts.values.toList().forEach { it.finish() }
        }
        scanningDesired = false
        scanDutyCycleJob?.cancel()
        scanDutyCycleJob = null
        stopScanWatchdog()
        stopScanning()
        Log.i(TAG, "GATT client manager stopped")
    }

    /** Close only the exact outgoing GATT generation rejected by authenticated tie-break. */
    fun disconnectLink(deviceAddress: String, linkID: String) = synchronized(clientLock) {
        clientAttempts[deviceAddress]?.takeIf { it.linkID == linkID }?.finish()
    }

    /**
     * Handle scan state changes from power manager
     */
    fun onScanStateChanged(shouldScan: Boolean) {
        val enabled = isClientRoleEnabled()
        scanningDesired = shouldScan && enabled
        if (shouldScan && enabled) {
            startScanning()
        } else {
            stopScanning()
        }
    }
    
    /**
     * Start scanning with rate limiting
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        // Respect debug setting
        val enabled = isClientRoleEnabled()
        val scanner = bleScanner
        if (!permissionManager.hasBluetoothPermissions() || scanner == null || !isActive || !scanningDesired || !enabled) return
        
        // Rate limit scan starts to prevent "scanning too frequently" errors
        val currentTime = currentTimeMillis()
        if (isCurrentlyScanning) {
            return
        }

        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.d(TAG, "Scan rate limited: waiting ${remainingWait}ms before starting scan")
            
            // Schedule delayed scan start
            connectionScope.launch {
                delay(remainingWait)
                if (isActive && scanningDesired && !isCurrentlyScanning && isClientRoleEnabled()) {
                    startScanning()
                }
            }
            return
        }
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .build()
        
        val scanFilters = listOf(scanFilter)

        val callback = object : ScanCallback() {
            private fun isCurrent(): Boolean = activeScan?.callback === this && isActive && scanningDesired

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (isCurrent() && permissionManager.hasBluetoothPermissions()) handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    if (isCurrent() && permissionManager.hasBluetoothPermissions()) handleScanResult(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!isCurrent()) return
                isCurrentlyScanning = false
                lastScanStopTime = currentTimeMillis()

                when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> {
                        // Already started: the stack thinks a scan is running. Re-arm from a clean
                        // state so we don't stay wedged (stop then restart with backoff).
                        stopScanning()
                        scheduleScanRestart("already-started", SCAN_RETRY_BASE_MS)
                    }
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> {
                        // App registration failed: common transient stack fault. Previously had NO
                        // retry, which left discovery dead until a manual BLE toggle.
                        scheduleScanRestart("registration-failed", SCAN_RETRY_BASE_MS)
                    }
                    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> {
                        scheduleScanRestart("internal-error", SCAN_RETRY_BASE_MS)
                    }
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> Unit // permanent: don't retry
                    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> {
                        // Out of hardware resources: back off longer so other scanners/connections
                        // can free up before we try again.
                        scheduleScanRestart("out-of-resources", SCAN_RETRY_BASE_MS * 3)
                    }
                    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> {
                        scheduleScanRestart("too-frequently", 10_000L)
                    }
                    else -> {
                        scheduleScanRestart("unknown-$errorCode", SCAN_RETRY_BASE_MS)
                    }
                }
                Log.e(TAG, "Scan failed: $errorCode")
            }
        }
        
        synchronized(scanLock) {
            if (!isActive || !scanningDesired || isCurrentlyScanning) return
            val scan = ActiveScan(scanner, callback)
            activeScan = scan
            try {
                lastScanStartTime = currentTime
                isCurrentlyScanning = true
                scanner.startScan(scanFilters, powerManager.getScanSettings(), callback)
                Log.i(TAG, "BLE scan started")
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting scan: ${e.message}")
                if (activeScan === scan) activeScan = null
                isCurrentlyScanning = false
            }
        }
    }
    
    /**
     * Stop scanning
     */
    @Suppress("DEPRECATION")
    private fun stopScanning() {
        val scan = synchronized(scanLock) {
            // Detach this generation before touching Android: permission loss cannot retain a
            // live flag, and adapter replacement cannot redirect the stop to a different scanner.
            activeScan.also {
                activeScan = null
                isCurrentlyScanning = false
            }
        } ?: return
        lastScanStopTime = currentTimeMillis()
        try {
            scan.scanner.stopScan(scan.callback)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while stopping scan")
        } catch (_: Exception) {
            Log.w(TAG, "Error stopping scan")
        }
    }

    /**
     * Schedule a scan restart with incremental backoff. Used to recover from transient scan
     * failures that previously had no retry path (codes 2/3/5), leaving discovery dead until a
     * manual BLE toggle.
     */
    private fun scheduleScanRestart(reason: String, baseDelayMs: Long) {
        scanRetryCount++
        val delayMs = (baseDelayMs * scanRetryCount).coerceAtMost(SCAN_MAX_RETRY_DELAY_MS)
        Log.w(TAG, "Scheduling scan restart in ${delayMs}ms (attempt $scanRetryCount, reason=$reason)")
        connectionScope.launch {
            delay(delayMs)
            if (isActive && scanningDesired && isClientRoleEnabled() && !isCurrentlyScanning) {
                startScanning()
            }
        }
    }

    /**
     * Periodic watchdog that self-heals the scanner. Android can stop a scan without ever invoking
     * onScanFailed (internal stack reset, Doze, background throttling), which leaves the app
     * believing it is scanning while it is not. This re-arms the scanner in those cases.
     */
    private fun startScanWatchdog() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = connectionScope.launch {
            while (isActive) {
                delay(SCAN_WATCHDOG_INTERVAL_MS)
                try {
                    // Only act when we are supposed to be scanning. Honors duty-cycle OFF windows
                    // and the client-disabled state via scanningDesired.
                    if (!isActive || !scanningDesired || !isClientRoleEnabled()) continue
                    if (!permissionManager.hasBluetoothPermissions() || bluetoothAdapter?.isEnabled != true) continue

                    val now = currentTimeMillis()
                    if (!isCurrentlyScanning) {
                        Log.w(TAG, "Watchdog: scan desired but not running -> restarting scan")
                        startScanning()
                    } else if (lastScanResultTime > 0L &&
                        now - lastScanResultTime > SCAN_STALE_RESULT_MS &&
                        now - lastScanStartTime > SCAN_STALE_RESULT_MS) {
                        // We think we're scanning but haven't seen anything for a long time. The scan
                        // may have silently died (flag wedged true). Force a clean re-arm.
                        Log.w(TAG, "Watchdog: no scan results for ${(now - lastScanResultTime) / 1000}s -> forcing scan restart")
                        forceRestartScan()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Scan watchdog error: ${e.message}")
                }
            }
        }
    }

    private fun stopScanWatchdog() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = null
    }

    /**
     * Force a clean scan restart, clearing a possibly-wedged isCurrentlyScanning flag.
     */
    private fun forceRestartScan() {
        stopScanning()
        connectionScope.launch {
            delay(500)
            if (isActive && scanningDesired && isClientRoleEnabled() && !isCurrentlyScanning) {
                startScanning()
            }
        }
    }
    
    /**
     * Handle scan result and initiate connection if appropriate
     */
    private fun handleScanResult(result: ScanResult) {
        if (!permissionManager.hasBluetoothPermissions()) return
        val device = result.device
        val rssi = result.rssi
        val deviceAddress = device.address
        val scanRecord = result.scanRecord
        
        // CRITICAL: Only process devices that have our service UUID
        val hasOurService = scanRecord?.serviceUuids?.any { it.uuid == AppConstants.Mesh.Gatt.SERVICE_UUID } == true
        if (!hasOurService) {
            return
        }

        // Proof the scanner is alive and finding our network: refresh liveness and clear backoff.
        lastScanResultTime = currentTimeMillis()
        scanRetryCount = 0

        // Try to extract peerID from Service Data (if available) for stable identity
        val serviceData = scanRecord?.getServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
        val peerID = if (serviceData != null && serviceData.size >= 8) {
            serviceData.joinToString("") { "%02x".format(it) }
        } else {
            null
        }

        if (peerID != null) {
            if (connectionTracker.isPeerConnected(peerID)) {
                 return
            }
        }

        // Store RSSI from scan results for later use (especially for server connections)
        connectionTracker.updateScanRSSI(deviceAddress, rssi)

        // A name is optional debug data; its permission must not gate scan processing.
        val deviceName = scanRecord.deviceName ?: if (permissionManager.hasBluetoothPermissions()) {
            try {
                device.name
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        // Publish scan result to debug UI buffer
        try {
            DebugSettingsManager.getInstance().addScanResult(
                DebugScanResult(
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    rssi = rssi,
                    peerID = peerID // Use the discovered peerID if available
                )
            )
        } catch (_: Exception) { }
        
        // Power-aware RSSI filtering
        if (rssi < powerManager.getRSSIThreshold()) {
            // Even if we skip connecting, still publish scan result to debug UI
            try {
                DebugSettingsManager.getInstance().addScanResult(
                    DebugScanResult(
                        deviceName = deviceName,
                        deviceAddress = deviceAddress,
                        rssi = rssi,
                        peerID = peerID
                    )
                )
            } catch (_: Exception) { }
            return
        }
        
        // Check if already connected OR already attempting to connect
        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            return
        }
        
        // Check if connection attempt is allowed
        if (!connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            return
        }
        
        // Check if connection limit is reached
        val dbg = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null }
        val maxOverall = dbg?.maxConnectionsOverall?.value ?: powerManager.getMaxConnections()
        val maxClient = dbg?.maxClientConnections?.value ?: maxOverall

        if (!connectionTracker.canConnectAsClient(maxOverall, maxClient)) {
            return
        }
        
        connectToDevice(device, rssi, peerID)
    }
    
    /**
     * Connect to a device as GATT client
     */
    private fun connectToDevice(device: BluetoothDevice, rssi: Int, peerID: String? = null): Boolean =
        synchronized(clientLock) {
            if (!isClientRoleEnabled() || !permissionManager.hasBluetoothPermissions()) return@synchronized false
            val address = device.address
            if (clientAttempts.containsKey(address) || connectionTracker.isDeviceConnected(address) ||
                !connectionTracker.addPendingConnection(address)) return@synchronized false
            val attempt = ClientAttempt(device, address, rssi, peerID)
            clientAttempts[address] = attempt
            attempt.timeout = connectionScope.launch {
                delay(30_000)
                synchronized(clientLock) {
                    if (attempt.phase != ClientPhase.READY) attempt.finish()
                }
            }
            try {
                val gatt = device.connectGatt(context, false, attempt, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    attempt.finish()
                    false
                } else {
                    // A callback may already have completed (or failed) while connectGatt returned.
                    if (attempt.phase == ClientPhase.CLOSED) {
                        attempt.release(gatt)
                        false
                    } else {
                        attempt.gatt = gatt
                        true
                    }
                }
            } catch (_: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked while connecting")
                attempt.finish()
                false
            } catch (_: Exception) {
                Log.w(TAG, "GATT connection could not be started")
                attempt.finish()
                false
            }
        }

    /** One callback owns one GATT generation, including the interval before MTU completes. */
    @Suppress("DEPRECATION")
    private inner class ClientAttempt(
        val device: BluetoothDevice,
        val address: String,
        val rssi: Int,
        val peerID: String?
    ) : BluetoothGattCallback() {
        val linkID = UUID.randomUUID().toString()
        var gatt: BluetoothGatt? = null
        var phase = ClientPhase.CONNECTING
        var timeout: Job? = null
        private var cccd: BluetoothGattDescriptor? = null
        private var releasedGatt: BluetoothGatt? = null

        private fun isCurrent(candidate: BluetoothGatt): Boolean {
            if (clientAttempts[address] !== this || phase == ClientPhase.CLOSED ||
                (gatt != null && gatt !== candidate)) return false
            // Once registered, only this exact generation owns its callbacks. A server link to
            // the same address remains independent until ANNOUNCE authentication elects a winner.
            if (phase >= ClientPhase.DISCOVERY && !connectionTracker.hasLink(address, linkID)) {
                finish()
                return false
            }
            gatt = candidate
            return true
        }

        private fun requestMtu(candidate: BluetoothGatt): Boolean = try {
            permissionManager.hasBluetoothPermissions() && candidate.requestMtu(517)
        } catch (_: SecurityException) { false }

        private fun discoverServices(candidate: BluetoothGatt): Boolean = try {
            permissionManager.hasBluetoothPermissions() && candidate.discoverServices()
        } catch (_: SecurityException) { false }

        private fun enableNotifications(candidate: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean = try {
            permissionManager.hasBluetoothPermissions() && candidate.setCharacteristicNotification(characteristic, true)
        } catch (_: SecurityException) { false }

        private fun writeCccd(candidate: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean = try {
            if (!permissionManager.hasBluetoothPermissions()) false
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                candidate.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                candidate.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) { false }

        fun release(candidate: BluetoothGatt) {
            if (releasedGatt === candidate) return
            releasedGatt = candidate
            try { candidate.disconnect() } catch (_: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked while disconnecting client")
            } catch (_: Exception) {
                Log.w(TAG, "Client disconnect failed")
            }
            try { candidate.close() } catch (_: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked while closing client")
            } catch (_: Exception) {
                Log.w(TAG, "Client close failed")
            }
        }

        /** [backOff] keeps the failed attempt on record, so the tracker spaces the next retry. */
        fun finish(backOff: Boolean = false) {
            if (phase == ClientPhase.CLOSED) return
            phase = ClientPhase.CLOSED
            timeout?.cancel()
            timeout = null
            if (clientAttempts[address] === this) {
                clientAttempts.remove(address)
                if (!backOff) connectionTracker.removePendingConnection(address)
                val disconnectedPeerID = connectionTracker.getPeerForLink(linkID)
                if (connectionTracker.cleanupDeviceConnectionIfCurrent(address, linkID)) {
                    delegate?.onDeviceDisconnected(device, linkID, disconnectedPeerID)
                }
            }
            gatt?.let(::release)
            gatt = null
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = synchronized(clientLock) {
            if (!isCurrent(gatt)) return@synchronized
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                // A connection that fails before it is ready (status 133 and the like) keeps its
                // attempt on record, so the next scan result cannot retry at once.
                finish(backOff = phase != ClientPhase.READY)
            } else if (newState == BluetoothProfile.STATE_CONNECTED && phase == ClientPhase.CONNECTING) {
                phase = ClientPhase.MTU
                connectionScope.launch {
                    delay(200)
                    synchronized(clientLock) {
                        if (isCurrent(gatt) && phase == ClientPhase.MTU && !requestMtu(gatt)) finish()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = synchronized(clientLock) {
            if (!isCurrent(gatt) || phase != ClientPhase.MTU) return@synchronized
            if (status != BluetoothGatt.GATT_SUCCESS || !permissionManager.hasBluetoothPermissions()) {
                finish()
                return@synchronized
            }
            connectionTracker.addDeviceConnection(address, BluetoothConnectionTracker.DeviceConnection(
                device = device, gatt = gatt, rssi = rssi, isClient = true, peerID = peerID, linkID = linkID
            ))
            phase = ClientPhase.DISCOVERY
            if (!discoverServices(gatt)) finish()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = synchronized(clientLock) {
            if (!isCurrent(gatt) || phase != ClientPhase.DISCOVERY) return@synchronized
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish()
                return@synchronized
            }
            val characteristic = gatt.getService(AppConstants.Mesh.Gatt.SERVICE_UUID)
                ?.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
            val descriptor = characteristic?.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
            if (characteristic == null || descriptor == null ||
                !enableNotifications(gatt, characteristic)) {
                finish()
                return@synchronized
            }
            // READY is acknowledged by the descriptor callback, never by a timer or a queued write.
            cccd = descriptor
            phase = ClientPhase.CCCD
            if (!writeCccd(gatt, descriptor)) finish()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = synchronized(clientLock) {
            if (!isCurrent(gatt) || phase != ClientPhase.CCCD || descriptor !== cccd) return@synchronized
            val characteristic = descriptor.characteristic
            if (status != BluetoothGatt.GATT_SUCCESS || !permissionManager.hasBluetoothPermissions() ||
                !connectionTracker.updateDeviceConnectionIfCurrent(address, linkID) { it.copy(characteristic = characteristic) }) {
                finish()
                return@synchronized
            }
            phase = ClientPhase.READY
            timeout?.cancel()
            timeout = null
            delegate?.onDeviceConnected(device)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) = synchronized(clientLock) {
            if (!isCurrent(gatt) || phase != ClientPhase.READY) return@synchronized
            if (!permissionManager.hasBluetoothPermissions()) {
                finish()
                return@synchronized
            }
            if (characteristic.uuid != AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) return@synchronized
            val value = characteristic.value ?: return@synchronized
            // Rate-limited per link before any decoding or signature work.
            if (!inboundLimiter.tryAcquire(address, linkID)) return@synchronized
            val packet = BitchatPacket.fromBinaryData(value) ?: return@synchronized
            val senderPeerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
            delegate?.onPacketReceived(packet, senderPeerID, device, linkID)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = synchronized(clientLock) {
            if (!isCurrent(gatt) || phase != ClientPhase.READY) return@synchronized
            if (!permissionManager.hasBluetoothPermissions()) {
                finish()
                return@synchronized
            }
            if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                delegate?.onGattClientWriteComplete(address, linkID, status)
            }
        }
    }

    /**
     * Restart scanning for power mode changes
     */
    fun restartScanning() {
        // Respect debug setting
        val enabled = isClientRoleEnabled()
        if (!isActive || !enabled) return
        
        connectionScope.launch {
            stopScanning()
            delay(1000) // Extra delay to avoid rate limiting
            applyPowerProfile(powerManager.profile.value)
        }
    }

    /**
     * Apply the current process-wide profile without ever disabling background discovery.
     */
    fun applyPowerProfile(profile: PowerManager.RuntimePerformanceProfile) {
        scanDutyCycleJob?.cancel()
        scanDutyCycleJob = null
        if (!isActive || !isClientRoleEnabled()) {
            onScanStateChanged(false)
            return
        }

        if (profile.ble.continuousScan) {
            startScanWatchdog()
            onScanStateChanged(true)
            return
        }

        // Duty-cycled scans are re-armed every window, so the continuous-scan watchdog would only
        // create background wakeups during intentional OFF periods.
        stopScanWatchdog()
        scanDutyCycleJob = connectionScope.launch {
            while (isActive && isClientRoleEnabled()) {
                onScanStateChanged(true)
                delay(profile.ble.scanOnMs)
                if (!isActive || !isClientRoleEnabled()) break
                onScanStateChanged(false)
                delay(profile.ble.scanOffMs)
            }
        }
    }
} 
