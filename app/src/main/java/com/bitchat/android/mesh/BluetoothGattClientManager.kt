package com.bitchat.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.LocationManager
import android.os.ParcelUuid
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
    private val platformSdkInt: Int = Build.VERSION.SDK_INT,
    private val legacyLocationEnabledProvider: (() -> Boolean)? = null,
    private val beforeScanPublication: (() -> Unit)? = null,
    private val afterScanCallbackPreflight: (() -> Unit)? = null,
    private val afterScanScheduleOwnerCheck: ((shouldScan: Boolean) -> Unit)? = null,
    private val roleFailure: (
        ownerGeneration: Long,
        reason: String,
        action: BluetoothRoleFailureAction
    ) -> Unit = { _, _, _ -> },
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime
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
        val callback: ScanCallback,
        val generation: Long,
        var platformStartReturned: Boolean = false,
        var stopIssuedAfterStart: Boolean = false
    )
    private data class ScanAdmission(val callback: ScanCallback, val generation: Long)
    private data class ScanJobs(val dutyCycle: Job?, val watchdog: Job?)
    private data class ScanScheduleChange(
        val previous: ScanJobs,
        val next: Job?,
        val ownerGeneration: Long,
        val enabled: Boolean,
        val continuous: Boolean
    )
    private val scanLock = Any()
    @Volatile private var activeScan: ActiveScan? = null
    
    // Scan rate limiting to prevent "scanning too frequently" errors
    private val scanRateLimit = 5000L // Minimum 5 seconds between scan start attempts
    // A monotonic clock begins at boot, so the sentinel must still allow the first immediate scan.
    private var lastScanStartTime = -scanRateLimit
    private var lastScanStopTime = 0L
    @Volatile private var isCurrentlyScanning = false

    // Self-healing scan state.
    // scanningDesired distinguishes "we want to be scanning but it isn't running" (a fault to recover
    // from) from "scanning is intentionally off" (e.g. duty-cycle OFF window or client disabled).
    @Volatile private var scanningDesired = false
    @Volatile private var legacyLocationAllowed = true
    @Volatile private var scanSupported = true
    @Volatile private var lastScanResultTime = 0L
    private var scanRetryCount = 0
    private var scanWatchdogJob: Job? = null
    private var scanDutyCycleJob: Job? = null
    private var scanScheduleGeneration = 0L
    private var scanGeneration = 0L
    private var scanIntentGeneration = 0L
    private var locationObservationGeneration = 0L
    private var ownerGeneration = 0L
    
    // State management
    @Volatile private var isActive = false
    private val clientLock = Any()
    private val clientAttempts = mutableMapOf<String, ClientAttempt>()
    /**
     * Exact scan generation allowed to create a client attempt. Publication and invalidation take
     * [scanLock] then [clientLock]; dial admission takes only [clientLock]. This makes invalidation
     * and attempt creation linearizable without holding [scanLock] across Android platform calls.
     */
    private var dialableScan: ScanAdmission? = null
    private enum class ClientPhase { CONNECTING, MTU, DISCOVERY, CCCD, READY, CLOSED }
    
    /**
     * Start client manager
     */
    fun start(): Boolean = startResult() == BluetoothRoleStartResult.STARTED

    internal fun startResult(ownerGeneration: Long = 0L): BluetoothRoleStartResult {
        // Respect debug setting
        if (!isClientRoleEnabled()) {
            Log.i(TAG, "Client start skipped: BLE/GATT Client disabled in debug settings")
            return BluetoothRoleStartResult.DISABLED
        }

        if (isActive) {
            return BluetoothRoleStartResult.STARTED
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return BluetoothRoleStartResult.PERMISSION_DENIED
        }
        
        val bluetoothEnabled = try {
            bluetoothAdapter?.isEnabled == true
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked before startup")
            false
        }
        if (!bluetoothEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or permission is unavailable")
            return BluetoothRoleStartResult.ADAPTER_OFF
        }

        if (scanSupported) {
            val scannerAvailable = try {
                bleScanner != null
            } catch (_: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked while acquiring scanner")
                return BluetoothRoleStartResult.PERMISSION_DENIED
            }
            if (!scannerAvailable) {
                Log.e(TAG, "BLE scanner not available")
                return BluetoothRoleStartResult.RESOURCE_UNAVAILABLE
            }
        }

        val locationObservation = synchronized(scanLock) { locationObservationGeneration }
        val initialLocationAllowed = readLegacyLocationEnabled()
        synchronized(scanLock) {
            if (locationObservation == locationObservationGeneration &&
                legacyLocationAllowed != initialLocationAllowed) {
                legacyLocationAllowed = initialLocationAllowed
                ++scanIntentGeneration
            }
            this.ownerGeneration = ownerGeneration
            isActive = true
        }
        
        connectionScope.launch {
            applyPowerProfile(powerManager.profile.value)
        }
        
        return BluetoothRoleStartResult.STARTED
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        val stoppedScan: ActiveScan?
        val stoppedJobs: ScanJobs
        synchronized(scanLock) {
            isActive = false
            scanningDesired = false
            ++scanIntentGeneration
            stoppedScan = detachActiveScanLocked()
            stoppedJobs = detachScheduledScanJobsLocked()
        }
        cancelScanJobs(stoppedJobs)
        synchronized(clientLock) {
            clientAttempts.values.toList().forEach { it.finish() }
        }
        stoppedScan?.let(::stopScanGeneration)
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
        applyScanState(shouldScan, requiredScheduleOwner = null)
    }

    /**
     * Apply a schedule mutation only while its owner is current. Owner validation and desired-state
     * mutation share [scanLock], so a replaced duty job cannot stop a newer scan generation.
     */
    private fun applyScanState(shouldScan: Boolean, requiredScheduleOwner: Long?): Boolean {
        val enabled = isClientRoleEnabled()
        var scanToStop: ActiveScan? = null
        val desired = synchronized(scanLock) {
            if (requiredScheduleOwner != null &&
                requiredScheduleOwner != scanScheduleGeneration) return false
            if (requiredScheduleOwner != null) afterScanScheduleOwnerCheck?.invoke(shouldScan)
            val next = shouldScan && enabled && scanSupported && isActive
            if (scanningDesired != next) {
                scanningDesired = next
                ++scanIntentGeneration
            }
            if (!next) scanToStop = detachActiveScanLocked()
            next
        }
        if (desired) {
            startScanning()
        } else {
            scanToStop?.let(::stopScanGeneration)
        }
        return requiredScheduleOwner == null || (enabled && isActive && scanSupported)
    }

    /** Refresh the legacy Android Location gate from process-scope receiver state. */
    fun refreshLegacyLocationState() {
        if (platformSdkInt >= Build.VERSION_CODES.S) return
        onLegacyLocationChanged(readLegacyLocationEnabled())
    }

    /**
     * Android 11 and older gate BLE discovery on the system Location switch. Preserve the desired
     * power-profile state while Location is off, but detach the exact scan generation immediately.
     * Android 12+ intentionally ignores the legacy switch.
     */
    fun onLegacyLocationChanged(enabled: Boolean) {
        if (platformSdkInt >= Build.VERSION_CODES.S) return
        var scanToStop: ActiveScan? = null
        val changed = synchronized(scanLock) {
            ++locationObservationGeneration
            val stateChanged = if (legacyLocationAllowed == enabled) false else {
                legacyLocationAllowed = enabled
                ++scanIntentGeneration
                true
            }
            if (!enabled) scanToStop = detachActiveScanLocked()
            stateChanged
        }
        if (!enabled) {
            scanToStop?.let(::stopScanGeneration)
            return
        }
        if (changed && isActive && scanningDesired && scanSupported && isClientRoleEnabled()) {
            forceRestartScan()
        }
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun readLegacyLocationEnabled(): Boolean {
        if (platformSdkInt >= Build.VERSION_CODES.S) return true
        legacyLocationEnabledProvider?.let { provider ->
            return try { provider() } catch (_: Exception) { false }
        }
        return try {
            if (platformSdkInt >= Build.VERSION_CODES.P) {
                locationManagerEnabled()
            } else {
                Settings.Secure.getInt(
                    context.applicationContext.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF
                ) != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (_: Exception) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun locationManagerEnabled(): Boolean =
        (context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            ?.isLocationEnabled ?: false
    
    /**
     * Start scanning with rate limiting
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        // Respect debug setting
        val enabled = isClientRoleEnabled()
        val scanner = try {
            bleScanner
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while acquiring scanner")
            return
        }
        if (!permissionManager.hasBluetoothPermissions() || scanner == null || !isActive ||
            !scanningDesired || !legacyLocationAllowed || !scanSupported || !enabled) return
        
        // Rate limit scan starts to prevent "scanning too frequently" errors
        val currentTime = elapsedRealtimeMillis()
        if (isCurrentlyScanning) {
            return
        }

        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            val intentGeneration = synchronized(scanLock) { scanIntentGeneration }
            Log.d(TAG, "Scan rate limited: waiting ${remainingWait}ms before starting scan")
            
            // Schedule delayed scan start
            connectionScope.launch {
                delay(remainingWait)
                if (isCurrentScanIntent(intentGeneration) && isActive && scanningDesired &&
                    legacyLocationAllowed && scanSupported &&
                    !isCurrentlyScanning && isClientRoleEnabled()) {
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
            private fun currentAdmission(): ScanAdmission? = synchronized(scanLock) {
                activeScan?.takeIf {
                    it.callback === this && isActive && scanningDesired &&
                        legacyLocationAllowed && scanSupported
                }?.let { ScanAdmission(this, it.generation) }
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val admission = currentAdmission() ?: return
                afterScanCallbackPreflight?.invoke()
                if (permissionManager.hasBluetoothPermissions()) handleScanResult(result, admission)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    val admission = currentAdmission() ?: return
                    afterScanCallbackPreflight?.invoke()
                    if (permissionManager.hasBluetoothPermissions()) handleScanResult(result, admission)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (errorCode == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
                    disableUnsupportedScanGeneration(this)
                    Log.e(TAG, "Scan failed: $errorCode")
                    return
                }
                val failedScan = synchronized(scanLock) {
                    val active = activeScan
                    if (active?.callback !== this || !isActive) return
                    (detachActiveScanLocked(active) ?: return).also {
                        lastScanStopTime = elapsedRealtimeMillis()
                    }
                }

                when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> {
                        // Already started: the stack thinks a scan is running. Re-arm from a clean
                        // state so we don't stay wedged (stop then restart with backoff).
                        stopScanGeneration(failedScan)
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

        beforeScanPublication?.invoke()
        val scan = synchronized(scanLock) {
            if (!isActive || !scanningDesired || !legacyLocationAllowed || !scanSupported ||
                isCurrentlyScanning || !permissionManager.hasBluetoothPermissions() ||
                !isClientRoleEnabled()) return
            val scan = ActiveScan(scanner, callback, ++scanGeneration)
            activeScan = scan
            synchronized(clientLock) {
                dialableScan = ScanAdmission(callback, scan.generation)
            }
            lastScanStartTime = elapsedRealtimeMillis()
            isCurrentlyScanning = true
            scan
        }
        try {
            scanner.startScan(scanFilters, powerManager.getScanSettings(), callback)
            Log.i(TAG, "BLE scan started")
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while starting scan")
            synchronized(scanLock) {
                if (activeScan === scan) {
                    detachActiveScanLocked(scan)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan could not be started (${e.javaClass.simpleName})")
            synchronized(scanLock) {
                if (activeScan === scan) {
                    detachActiveScanLocked(scan)
                }
            }
        } finally {
            val stale = synchronized(scanLock) {
                scan.platformStartReturned = true
                activeScan !== scan || activeScan?.generation != scan.generation
            }
            if (stale) stopScanGeneration(scan)
        }
    }

    private fun disableUnsupportedScanGeneration(callback: ScanCallback) {
        val disabled = synchronized(scanLock) {
            val failedScan = activeScan ?: return
            if (!isActive || !scanSupported || failedScan.callback !== callback ||
                failedScan.generation != scanGeneration) return
            detachActiveScanLocked(failedScan)
            lastScanStopTime = elapsedRealtimeMillis()
            scanSupported = false
            scanningDesired = false
            ++scanIntentGeneration
            Triple(failedScan, ownerGeneration, detachScheduledScanJobsLocked())
        }
        stopScanGeneration(disabled.first)
        cancelScanJobs(disabled.third)
        connectionScope.launch {
            yield()
            roleFailure(
                disabled.second,
                "scan-feature-unsupported",
                BluetoothRoleFailureAction.DISABLE_CLIENT_SCAN
            )
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
            detachActiveScanLocked()
        } ?: return
        lastScanStopTime = elapsedRealtimeMillis()
        stopScanGeneration(scan)
    }

    /** Caller must hold [scanLock]. Lock order is always scan -> client. */
    private fun detachActiveScanLocked(expected: ActiveScan? = null): ActiveScan? {
        val scan = activeScan ?: run {
            synchronized(clientLock) { dialableScan = null }
            isCurrentlyScanning = false
            return null
        }
        if (expected != null && scan !== expected) return null
        synchronized(clientLock) {
            val admission = dialableScan
            if (admission?.callback === scan.callback && admission.generation == scan.generation) {
                dialableScan = null
            }
        }
        activeScan = null
        isCurrentlyScanning = false
        return scan
    }

    @Suppress("DEPRECATION")
    private fun stopScanGeneration(scan: ActiveScan) {
        val shouldStop = synchronized(scanLock) {
            if (!scan.platformStartReturned) true
            else if (scan.stopIssuedAfterStart) false else {
                scan.stopIssuedAfterStart = true
                true
            }
        }
        if (!shouldStop) return
        try {
            scan.scanner.stopScan(scan.callback)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while stopping scan")
        } catch (_: Exception) {
            Log.w(TAG, "Error stopping scan")
        }
    }

    private fun isCurrentScanIntent(generation: Long): Boolean = synchronized(scanLock) {
        generation == scanIntentGeneration
    }

    /**
     * Schedule a scan restart with incremental backoff. Used to recover from transient scan
     * failures that previously had no retry path (codes 2/3/5), leaving discovery dead until a
     * manual BLE toggle.
     */
    private fun scheduleScanRestart(reason: String, baseDelayMs: Long) {
        scanRetryCount++
        val delayMs = (baseDelayMs * scanRetryCount).coerceAtMost(SCAN_MAX_RETRY_DELAY_MS)
        val intentGeneration = synchronized(scanLock) { scanIntentGeneration }
        Log.w(TAG, "Scheduling scan restart in ${delayMs}ms (attempt $scanRetryCount, reason=$reason)")
        connectionScope.launch {
            delay(delayMs)
            if (isCurrentScanIntent(intentGeneration) && isActive && scanningDesired &&
                legacyLocationAllowed && scanSupported &&
                isClientRoleEnabled() && !isCurrentlyScanning) {
                startScanning()
            }
        }
    }

    /** Caller must hold [scanLock]. Invalidating the owner fences jobs before cancellation runs. */
    private fun detachScheduledScanJobsLocked(): ScanJobs {
        ++scanScheduleGeneration
        val jobs = ScanJobs(scanDutyCycleJob, scanWatchdogJob)
        scanDutyCycleJob = null
        scanWatchdogJob = null
        return jobs
    }

    private fun cancelScanJobs(jobs: ScanJobs) {
        jobs.dutyCycle?.cancel()
        jobs.watchdog?.cancel()
    }

    private fun isScanScheduleOwner(generation: Long): Boolean = synchronized(scanLock) {
        generation == scanScheduleGeneration && isActive && scanSupported
    }

    /**
     * Periodic watchdog that self-heals the scanner. Android can stop a scan without ever invoking
     * onScanFailed (internal stack reset, Doze, background throttling), which leaves the app
     * believing it is scanning while it is not. This re-arms the scanner in those cases.
     */
    private suspend fun runScanWatchdog(ownerGeneration: Long) {
        while (isScanScheduleOwner(ownerGeneration)) {
            delay(SCAN_WATCHDOG_INTERVAL_MS)
            if (!isScanScheduleOwner(ownerGeneration)) break
            try {
                // Only act when we are supposed to be scanning. Honors intentional OFF windows.
                if (!scanningDesired || !legacyLocationAllowed || !isClientRoleEnabled()) continue
                if (!permissionManager.hasBluetoothPermissions() || bluetoothAdapter?.isEnabled != true) continue

                val now = elapsedRealtimeMillis()
                if (!isCurrentlyScanning) {
                    Log.w(TAG, "Watchdog: scan desired but not running -> restarting scan")
                    startScanning()
                } else {
                    val lastLivenessAt = maxOf(lastScanStartTime, lastScanResultTime)
                    if (now - lastLivenessAt >= SCAN_STALE_RESULT_MS) {
                        // This includes a scanner which never produced its first result.
                        Log.w(TAG, "Watchdog: scan liveness expired -> forcing scan restart")
                        forceRestartScan(ownerGeneration)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scan watchdog error: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun runDutyCycle(
        ownerGeneration: Long,
        schedule: PowerManager.BleSchedule
    ) {
        while (applyScanState(true, ownerGeneration)) {
            delay(schedule.scanOnMs)
            if (!applyScanState(false, ownerGeneration)) break
            delay(schedule.scanOffMs)
        }
    }

    /**
     * Force a clean scan restart, clearing a possibly-wedged isCurrentlyScanning flag.
     */
    private fun forceRestartScan(requiredScheduleOwner: Long? = null) {
        val (scan, intentGeneration) = synchronized(scanLock) {
            if (requiredScheduleOwner != null &&
                requiredScheduleOwner != scanScheduleGeneration) return
            detachActiveScanLocked() to scanIntentGeneration
        }
        scan?.let(::stopScanGeneration)
        connectionScope.launch {
            delay(500)
            val mayRestart = synchronized(scanLock) {
                (requiredScheduleOwner == null ||
                    requiredScheduleOwner == scanScheduleGeneration) &&
                    intentGeneration == scanIntentGeneration && isActive && scanningDesired &&
                    legacyLocationAllowed && scanSupported && !isCurrentlyScanning
            }
            if (mayRestart && isClientRoleEnabled()) {
                startScanning()
            }
        }
    }
    
    /**
     * Handle scan result and initiate connection if appropriate
     */
    private fun handleScanResult(result: ScanResult, admission: ScanAdmission) {
        if (!permissionManager.hasBluetoothPermissions() || !isScanAdmissionCurrent(admission)) return
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
        lastScanResultTime = elapsedRealtimeMillis()
        scanRetryCount = 0

        // Legacy peers may expose an identity-shaped value. It is untrusted debug metadata only.
        val serviceData = scanRecord.getServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
        val advertisedPeerIDHint = if (serviceData != null && serviceData.size >= 8) {
            serviceData.joinToString("") { "%02x".format(it) }
        } else {
            null
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
                    peerID = advertisedPeerIDHint
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
                        peerID = advertisedPeerIDHint
                    )
                )
            } catch (_: Exception) { }
            return
        }

        // A signed, cooperative tie-break may have rejected this exact outgoing address while an
        // authenticated winner remains live. Do not let scan/pending cleanup recreate the loser.
        if (connectionTracker.shouldSuppressClientDial(deviceAddress)) {
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
        
        // Service data is an unauthenticated compatibility/debug hint. It must never select,
        // suppress, or authenticate a connection; only a validated ANNOUNCE may bind identity.
        connectToDevice(device, rssi, admission)
    }
    
    /**
     * Connect to a device as GATT client
     */
    private fun connectToDevice(
        device: BluetoothDevice,
        rssi: Int,
        scanAdmission: ScanAdmission? = null
    ): Boolean =
        synchronized(clientLock) {
            if (scanAdmission != null && dialableScan != scanAdmission) return@synchronized false
            if (!isClientRoleEnabled() || !permissionManager.hasBluetoothPermissions()) return@synchronized false
            val address = device.address
            if (connectionTracker.shouldSuppressClientDial(address) ||
                clientAttempts.containsKey(address) || connectionTracker.isDeviceConnected(address) ||
                !connectionTracker.addPendingConnection(address)) return@synchronized false
            val attempt = ClientAttempt(device, address, rssi)
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

    private fun isScanAdmissionCurrent(admission: ScanAdmission): Boolean =
        synchronized(clientLock) { dialableScan == admission }

    /** One callback owns one GATT generation, including the interval before MTU completes. */
    @Suppress("DEPRECATION")
    private inner class ClientAttempt(
        val device: BluetoothDevice,
        val address: String,
        val rssi: Int
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
                device = device, gatt = gatt, rssi = rssi, isClient = true, linkID = linkID
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
        if (!isActive || !enabled || !scanSupported) return
        
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
        val roleEnabled = isClientRoleEnabled()
        val change = synchronized(scanLock) {
            val previous = detachScheduledScanJobsLocked()
            val owner = scanScheduleGeneration
            val enabled = isActive && roleEnabled && scanSupported
            if (!enabled) {
                ScanScheduleChange(previous, null, owner, enabled = false, continuous = false)
            } else {
                val next = if (profile.ble.continuousScan) {
                    connectionScope.launch(start = CoroutineStart.LAZY) { runScanWatchdog(owner) }
                } else {
                    connectionScope.launch(start = CoroutineStart.LAZY) { runDutyCycle(owner, profile.ble) }
                }
                if (profile.ble.continuousScan) scanWatchdogJob = next else scanDutyCycleJob = next
                ScanScheduleChange(previous, next, owner, enabled = true, continuous = profile.ble.continuousScan)
            }
        }
        cancelScanJobs(change.previous)
        change.next?.start()
        if (!change.enabled) {
            applyScanState(false, change.ownerGeneration)
        } else if (change.continuous) {
            applyScanState(true, change.ownerGeneration)
        }
    }
} 
