package com.bitchat.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.mesh.BluetoothMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val CHANNEL_ID = "bitchat_mesh_service"
        private const val NOTIFICATION_ID = 10001
        internal const val FOREGROUND_SERVICE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        const val ACTION_START = "com.bitchat.android.service.START"
        const val ACTION_STOP = "com.bitchat.android.service.STOP"
        const val ACTION_QUIT = "com.bitchat.android.service.QUIT"
        const val ACTION_UPDATE_NOTIFICATION = "com.bitchat.android.service.UPDATE_NOTIFICATION"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_START }

            // Only launch as an FGS when onStartCommand can promote immediately.
            val shouldStartForeground = shouldStartAsForeground(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (shouldStartForeground) {
                    context.startForegroundService(intent)
                } else {
                    android.util.Log.i(
                        "MeshForegroundService",
                        "Not starting service on API>=26 (shouldStartForeground=$shouldStartForeground)"
                    )
                }
            } else {
                if (MeshServicePreferences.isBackgroundEnabled(true)) {
                    context.startService(intent)
                } else {
                    android.util.Log.i("MeshForegroundService", "Background disabled; not starting service (pre-O)")
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun shouldStartAsForeground(context: Context): Boolean {
            return MeshServicePreferences.isBackgroundEnabled(true) &&
                    hasBluetoothPermissionsStatic(context)
        }

        private fun hasBluetoothPermissionsStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                val fine = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarse = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                fine || coarse
            }
        }

    }

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var peerAvailabilityNotifier: PeerAvailabilityNotifier
    private var updateJob: Job? = null
    private var startupJob: Job? = null
    private val meshService: BluetoothMeshService?
        get() = MeshServiceHolder.meshService
    private val unifiedMeshService: com.bitchat.android.mesh.MeshService?
        get() = MeshServiceHolder.unifiedMeshService
    private val serviceJob = Job()
    // Service lifecycle callbacks and notification state are main-thread confined.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var isInForeground: Boolean = false
    private var isShuttingDown: Boolean = false
    private var lastNotifiedPeerCount: Int? = null

    // A sticky service can be recreated before the IO bootstrap loads preferences. Promote
    // promptly with a minimal notification, then enforce the stored policy before transport.
    private fun runtimeReady(): Boolean = !com.bitchat.android.BuildConfig.WESH_POLICY ||
        (com.bitchat.android.startup.WeshRuntime.transportAllowed &&
            com.bitchat.android.startup.WeshRuntime.get(applicationContext).state.value is
            com.bitchat.android.startup.StartupCoordinator.State.Ready)

    private fun backgroundRequested(): Boolean =
        (!com.bitchat.android.BuildConfig.WESH_POLICY || com.bitchat.android.startup.WeshRuntime.transportAllowed) &&
            (!runtimeReady() || MeshServicePreferences.isBackgroundEnabled(true))

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        peerAvailabilityNotifier = PeerAvailabilityNotifier(
            context = applicationContext,
            scope = scope,
            isAppCurrentlyInBackground = {
                !ProcessLifecycleOwner.get()
                    .lifecycle
                    .currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            }
        )
        createChannel()

        // Notification content is driven by peer-state changes, not a permanent timer.
        updateJob = scope.launch {
            com.bitchat.android.services.AppStateStore.peers
                .map { peers -> peers.distinct().size }
                .distinctUntilChanged()
                .collect { peerCount ->
                    peerAvailabilityNotifier.onPeerCountChanged(
                        peerCount = peerCount,
                        isAppInBackground = !ProcessLifecycleOwner.get()
                            .lifecycle
                            .currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                    )
                    if (isInForeground) updateNotification(force = false)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isShuttingDown && intent?.action == ACTION_START) {
            AppShutdownCoordinator.cancelPendingShutdown()
            isShuttingDown = false
        }
        if (isShuttingDown && intent?.action != ACTION_QUIT) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // Stop FGS and mesh cleanly
                startupJob?.cancel()
                startupJob = null
                updateJob?.cancel()
                updateJob = null
                try { com.bitchat.android.services.MessageRouter.tryGetInstance()?.stopOutboxScheduler() } catch (_: Exception) { }
                try { unifiedMeshService?.stopServices() ?: meshService?.stopServices() } catch (_: Exception) { }
                try { MeshServiceHolder.clear() } catch (_: Exception) { }
                try { stopForeground(true) } catch (_: Exception) { }
                clearMeshNotifications()
                isInForeground = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                isShuttingDown = true
                startupJob?.cancel()
                startupJob = null
                updateJob?.cancel()
                updateJob = null
                try { stopForeground(true) } catch (_: Exception) { }
                clearMeshNotifications()
                isInForeground = false
                // Fully stop all background activity, stop Tor (without changing setting), then kill the app
                AppShutdownCoordinator.requestFullShutdownAndKill(
                    app = application,
                    mesh = unifiedMeshService,
                    notificationManager = notificationManager,
                    stopForeground = {
                        try { stopForeground(true) } catch (_: Exception) { }
                        isInForeground = false
                    },
                    stopService = { stopSelf() }
                )
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {
                // If we became eligible and are not in foreground yet, promote once
                if (backgroundRequested() && hasAllRequiredPermissions() && !isInForeground) {
                    val count = getUnifiedActivePeerCount()
                    val n = buildNotification(count)
                    if (!startForegroundCompat(n)) return stopAfterRefusedPromotion()
                    isInForeground = true
                    lastNotifiedPeerCount = count
                } else {
                    updateNotification(force = true)
                }
            }
            else -> { /* ACTION_START or null */ }
        }

        // A sticky restart can outlive permission or policy changes. Do not wait for
        // encrypted storage when promotion/transport is already disallowed.
        if ((com.bitchat.android.BuildConfig.WESH_POLICY && !com.bitchat.android.startup.WeshRuntime.transportAllowed) ||
            !hasAllRequiredPermissions() || (runtimeReady() && !MeshServicePreferences.isBackgroundEnabled(true))) {
            startupJob?.cancel()
            startupJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            isInForeground = false
            clearMeshNotifications()
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote exactly once when eligible, otherwise stay background (or stop).
        // POST_NOTIFICATIONS is intentionally not an eligibility requirement: Android 13+
        // still allows foreground services and exposes them in the system task manager.
        if (backgroundRequested() && hasAllRequiredPermissions() && !isInForeground) {
            val count = getUnifiedActivePeerCount()
            val notification = buildNotification(count)
            if (!startForegroundCompat(notification)) return stopAfterRefusedPromotion()
            isInForeground = true
            lastNotifiedPeerCount = count
        }

        // Promotion above must finish before any suspendable secure startup work.
        ensureMeshStarted()
        return START_STICKY
    }

    private fun ensureMeshStarted() {
        if (isShuttingDown || startupJob?.isActive == true) return
        startupJob = scope.launch {
            try {
                if (com.bitchat.android.BuildConfig.WESH_POLICY) {
                    com.bitchat.android.startup.WeshRuntime.awaitTransportReady(applicationContext)
                }
                if (isShuttingDown) return@launch
                if ((com.bitchat.android.BuildConfig.WESH_POLICY && !com.bitchat.android.startup.WeshRuntime.transportAllowed) ||
                    !MeshServicePreferences.isBackgroundEnabled(true) || !hasAllRequiredPermissions()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isInForeground = false
                    clearMeshNotifications()
                    stopSelf()
                    return@launch
                }
                MeshServiceHolder.getUnifiedOrCreate(applicationContext).startServices()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unavailable identity never starts a substitute transport identity.
                stopForeground(STOP_FOREGROUND_REMOVE)
                isInForeground = false
                clearMeshNotifications()
                stopSelf()
            }
        }
    }

    private fun updateNotification(force: Boolean) {
        if (isShuttingDown) {
            clearMeshNotifications()
            return
        }
        val count = getUnifiedActivePeerCount()
        if (backgroundRequested() && hasAllRequiredPermissions()) {
            if (lastNotifiedPeerCount != count && startForegroundCompat(buildNotification(count))) {
                lastNotifiedPeerCount = count
            }
        } else if (force) {
            // If disabled and forced, make sure to remove any prior foreground state
            try { stopForeground(false) } catch (_: Exception) { }
            clearMeshNotifications()
            isInForeground = false
            lastNotifiedPeerCount = null
        }
    }

    private fun clearMeshNotifications() {
        notificationManager.cancel(NOTIFICATION_ID)
        peerAvailabilityNotifier.clear()
    }

    private fun hasAllRequiredPermissions(): Boolean {
        // For starting FGS with connectedDevice|dataSync, we need:
        // - Foreground service permissions (declared in manifest)
        // - One of the device-related permissions (we request BL perms at runtime)
        // POST_NOTIFICATIONS controls notification-drawer visibility, not FGS eligibility.
        return hasBluetoothPermissions()
    }

    private fun getUnifiedActivePeerCount(): Int {
        return try {
            unifiedMeshService?.getActivePeerCount() ?: meshService?.getActivePeerCount() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Prior to S, scanning requires location permissions
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun buildNotification(activePeers: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action: Quit Bitchat
        val quitIntent = Intent(this, MeshForegroundService::class.java).apply { action = ACTION_QUIT }
        val quitPendingIntent = PendingIntent.getService(
            this, 1, quitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = getString(R.string.app_name)
        val content = getString(R.string.mesh_service_notification_content, activePeers)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            // Add an action button that appears when notification is expanded
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_quit_bitchat),
                quitPendingIntent
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_service_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** False when Android refuses the promotion; the caller must not claim the foreground state. */
    private fun startForegroundCompat(notification: Notification): Boolean =
        ForegroundPromotion.attempt(TAG) {
            if (Build.VERSION.SDK_INT >= 34) {
                // BLE mesh only: the manifest declares connectedDevice and nothing else.
                startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }

    /**
     * A refused promotion leaves no foreground service to run: stop this one before Android stops
     * it. The mesh stays with the app process; the next app launch promotes the service again.
     */
    private fun stopAfterRefusedPromotion(): Int {
        startupJob?.cancel()
        startupJob = null
        isInForeground = false
        lastNotifiedPeerCount = null
        clearMeshNotifications()
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        startupJob?.cancel()
        startupJob = null
        updateJob?.cancel()
        updateJob = null
        // Cancel the service coroutine scope to prevent leaks
        try { serviceJob.cancel() } catch (_: Exception) { }
        // Best-effort ensure we are not marked foreground
        if (isInForeground) {
            try { stopForeground(true) } catch (_: Exception) { }
            isInForeground = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
