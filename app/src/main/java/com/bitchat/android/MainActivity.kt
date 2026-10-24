package com.bitchat.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.onboarding.BluetoothCheckScreen
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BatteryOptimizationPreferenceManager
import com.bitchat.android.onboarding.BatteryOptimizationScreen
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import com.bitchat.android.onboarding.InitializationErrorScreen
import com.bitchat.android.onboarding.InitializingScreen
import com.bitchat.android.onboarding.LocationCheckScreen
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.OrientationAwareActivity
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.services.VerificationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : OrientationAwareActivity() {

    private lateinit var permissionManager: PermissionManager
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private lateinit var bluetoothStatusManager: BluetoothStatusManager
    private lateinit var locationStatusManager: LocationStatusManager
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager
    
    // Core mesh service - provided by the foreground service holder
    private lateinit var meshService: BluetoothMeshService
    private lateinit var unifiedMeshService: MeshService
    private val mainViewModel: MainViewModel by viewModels()
    private var pendingMeshForegroundServiceStart = false
    private var runtimeAttached by mutableStateOf(false)
    private val routeGate = com.bitchat.android.startup.StartupRouteGate()
    private val chatViewModel: ChatViewModel by viewModels { 
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(application, meshService, unifiedMeshService) as T
            }
        }
    }
    
    private val forceFinishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == com.bitchat.android.util.AppConstants.UI.ACTION_FORCE_FINISH) {
                android.util.Log.i("MainActivity", "Received force finish broadcast, closing UI")
                finishAffinity()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeGate.pending = savedInstanceState?.getBoolean("wesh_pending_route", true) ?: true
        if (BuildConfig.WESH_POLICY) {
            com.bitchat.android.ui.WeshWindowPrivacy.apply(this)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.setRecentsScreenshotEnabled(false)
        }
        // Register receiver for force finish signal from shutdown coordinator
        val filter = android.content.IntentFilter(com.bitchat.android.util.AppConstants.UI.ACTION_FORCE_FINISH)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            forceFinishReceiver,
            filter,
            packageName + ".permission.FORCE_FINISH",
            null,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        // Check if this is a quit request from the notification
        if (intent.getBooleanExtra("ACTION_QUIT_APP", false)) {
            android.util.Log.d("MainActivity", "Quit request received in onCreate, finishing activity")
            finish()
            return
        }

        if (BuildConfig.WESH_POLICY) {
            @Suppress("DEPRECATION")
            val pendingIntent = if (Build.VERSION.SDK_INT >= 33) {
                savedInstanceState?.getParcelable("wesh_pending_intent", Intent::class.java)
            } else {
                savedInstanceState?.getParcelable<Intent>("wesh_pending_intent")
            }
            setIntent(com.bitchat.android.startup.boundedWeshStartupIntent(pendingIntent ?: intent))
        }

        com.bitchat.android.service.AppShutdownCoordinator.cancelPendingShutdown()
        
        // Enable edge-to-edge display for modern Android look
        enableEdgeToEdge()

        // Initialize permission management
        permissionManager = PermissionManager(this)
        bluetoothStatusManager = BluetoothStatusManager(
            activity = this,
            context = this,
            onBluetoothEnabled = ::handleBluetoothEnabled,
            onBluetoothDisabled = ::handleBluetoothDisabled
        )
        if (requiresSystemLocationForMesh()) {
            locationStatusManager = LocationStatusManager(
                activity = this,
                context = this,
                onLocationEnabled = ::handleLocationEnabled,
                onLocationDisabled = ::handleLocationDisabled
            )
        }
        batteryOptimizationManager = BatteryOptimizationManager(
            activity = this,
            context = this,
            onBatteryOptimizationDisabled = ::handleBatteryOptimizationDisabled,
            onBatteryOptimizationFailed = ::handleBatteryOptimizationFailed
        )
        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager,
            onOnboardingComplete = ::handleOnboardingComplete,
            onOnboardingFailed = ::handleOnboardingFailed
        )
        
        setContent {
            com.bitchat.android.ui.pave.WeshPaveTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    val modifier = Modifier.fillMaxSize().padding(innerPadding)
                    if (BuildConfig.WESH_POLICY && !runtimeAttached) {
                        val startup by com.bitchat.android.startup.WeshRuntime.get(applicationContext).state.collectAsState()
                        val draftWipeRequired by com.bitchat.android.startup.WeshRuntime.draftWipeRequired.collectAsState()
                        val panicRecovery by com.bitchat.android.startup.WeshRuntime.recoveryState.collectAsState()
                        val startupFailures by com.bitchat.android.startup.WeshRuntime.get(applicationContext).failures.collectAsState()
                        com.bitchat.android.startup.StartupScreen(
                            draftWipeRequired = draftWipeRequired,
                            recovery = panicRecovery,
                            onEraseAll = { com.bitchat.android.startup.WeshRuntime.clearInterruptedPanic(applicationContext) },
                            offerIdentityReset = com.bitchat.android.startup.StartupRecoveryPolicy.offersIdentityReset(startupFailures, startup, draftWipeRequired),
                            onResetIdentity = { com.bitchat.android.startup.WeshRuntime.resetIdentityAfterFailures(applicationContext) },
                            failed = startup is com.bitchat.android.startup.StartupCoordinator.State.Failed,
                            onRetry = {
                                com.bitchat.android.startup.WeshRuntime.get(applicationContext).start(retry = true)
                            },
                            modifier = modifier
                        )
                    } else {
                        OnboardingFlowScreen(modifier)
                    }
                }
            }
        }
        
        // Collect state changes in a lifecycle-aware manner
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.onboardingState.collect { state ->
                    handleOnboardingStateChange(state)
                }
            }
        }

        if (BuildConfig.WESH_POLICY) {
            lifecycleScope.launch {
                com.bitchat.android.startup.WeshRuntime.get(applicationContext).state.collect { state ->
                    if (state is com.bitchat.android.startup.StartupCoordinator.State.Ready && !runtimeAttached) {
                        attachRuntime()
                    }
                }
            }
        } else {
            attachRuntime()
        }
    }
    
    private fun attachRuntime() {
        meshService = com.bitchat.android.service.MeshServiceHolder.getOrCreate(applicationContext)
        unifiedMeshService = com.bitchat.android.service.MeshServiceHolder.getUnifiedOrCreate(applicationContext)
        runtimeAttached = true
        startMeshForegroundServiceBestEffort()
        if (mainViewModel.onboardingState.value == OnboardingState.CHECKING) checkOnboardingStatus()
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            unifiedMeshService.delegate = chatViewModel
            dispatchPendingRoute()
            // A recreated activity skips initialization; making the key is idempotent.
            ensureWeshNostrKey()
        }
    }

    /** My QR always carries my npub: the key is made here, locally, and unused while Internet is off. */
    private fun ensureWeshNostrKey() {
        if (!BuildConfig.WESH_POLICY) return
        lifecycleScope.launch { com.bitchat.android.services.WeshQrIdentity.ensureKey(applicationContext) }
    }

    @Composable
    private fun OnboardingFlowScreen(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val onboardingState by mainViewModel.onboardingState.collectAsState()
        val bluetoothStatus by mainViewModel.bluetoothStatus.collectAsState()
        val locationStatus by mainViewModel.locationStatus.collectAsState()
        val batteryOptimizationStatus by mainViewModel.batteryOptimizationStatus.collectAsState()
        val errorMessage by mainViewModel.errorMessage.collectAsState()
        val isBluetoothLoading by mainViewModel.isBluetoothLoading.collectAsState()
        val isLocationLoading by mainViewModel.isLocationLoading.collectAsState()
        val isBatteryOptimizationLoading by mainViewModel.isBatteryOptimizationLoading.collectAsState()

        DisposableEffect(context, bluetoothStatusManager) {

            val receiver = bluetoothStatusManager.monitorBluetoothState(
                context = context,
                bluetoothStatusManager = bluetoothStatusManager,
                onBluetoothStateChanged = { status ->
                    if (status == BluetoothStatus.ENABLED && onboardingState == OnboardingState.BLUETOOTH_CHECK) {
                        checkBluetoothAndProceed()
                    }
                }
            )

            onDispose {
                if (receiver != null) {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (e: IllegalArgumentException) {
                        Log.w("BluetoothStatusUI", "Receiver was not registered")
                    }
                }
            }
        }

        when (onboardingState) {
            OnboardingState.PERMISSION_REQUESTING -> {
                InitializingScreen(modifier)
            }
            
            OnboardingState.BLUETOOTH_CHECK -> {
                BluetoothCheckScreen(
                    modifier = modifier,
                    status = bluetoothStatus,
                    onEnableBluetooth = {
                        mainViewModel.updateBluetoothLoading(true)
                        bluetoothStatusManager.requestEnableBluetooth()
                    },
                    onRetry = {
                        checkBluetoothAndProceed()
                    },
                    isLoading = isBluetoothLoading
                )
            }
            
            OnboardingState.LOCATION_CHECK -> {
                LocationCheckScreen(
                    modifier = modifier,
                    status = locationStatus,
                    onEnableLocation = {
                        mainViewModel.updateLocationLoading(true)
                        locationStatusManager.requestEnableLocation()
                    },
                    onRetry = {
                        checkLocationAndProceed()
                    },
                    isLoading = isLocationLoading
                )
            }
            
            OnboardingState.BATTERY_OPTIMIZATION_CHECK -> {
                BatteryOptimizationScreen(
                    modifier = modifier,
                    status = batteryOptimizationStatus,
                    onDisableBatteryOptimization = {
                        mainViewModel.updateBatteryOptimizationLoading(true)
                        batteryOptimizationManager.requestDisableBatteryOptimization()
                    },
                    onRetry = {
                        checkBatteryOptimizationAndProceed()
                    },
                    onSkip = {
                        // Skip battery optimization and proceed
                        proceedWithPermissionCheck()
                    },
                    isLoading = isBatteryOptimizationLoading
                )
            }
            
            OnboardingState.PERMISSION_EXPLANATION -> {
                // Pavé welcome: nickname, the Bluetooth explanation, then (Android 13+) the
                // notifications explanation, each before its system prompt.
                val prefs = chatViewModel.weshPreferences
                val policyEpoch = prefs.resetEpoch.collectAsState().value
                val currentNickname by chatViewModel.nickname.collectAsState()
                val initialNickname = remember(currentNickname) { welcomeNickname(prefs.welcomed, currentNickname) }
                val notifications = permissionManager.getUnrequestedOptionalPermissions()
                    .filter { it == android.Manifest.permission.POST_NOTIFICATIONS }
                val requiredGranted = permissionManager.areRequiredPermissionsGranted()
                com.bitchat.android.ui.pave.OnboardingFlow(
                    onFinished = { chosen, _ ->
                        prefs.withCurrentEpoch(policyEpoch) {
                            // Only a nickname the person actually set replaces the stored one.
                            if (chosen != initialNickname && com.bitchat.android.ui.pave.NicknameGenerator.isValid(chosen)) {
                                chatViewModel.setNickname(chosen, policyEpoch)
                            }
                            prefs.welcomed = true
                            mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
                            // The welcome only finishes with Bluetooth granted; the coordinator asks for anything else missing.
                            onboardingCoordinator.requestPermissions()
                        }
                    },
                    modifier = modifier,
                    initialNickname = initialNickname,
                    start = welcomeStart(prefs.welcomed, requiredGranted, notifications.isNotEmpty()),
                    askNotifications = notifications.isNotEmpty(),
                    onNotificationsAsked = {
                        prefs.withCurrentEpoch(policyEpoch) {
                            permissionManager.markOptionalPermissionsRequested(notifications)
                        }
                    },
                )
            }

            OnboardingState.CHECKING, OnboardingState.INITIALIZING, OnboardingState.COMPLETE -> {
                // Back is handled by the Wesh screens: an activity callback here would end the
                // private chat before the screen leaves it, showing it a frame without a contact.
                com.bitchat.android.ui.WeshScreen(viewModel = chatViewModel)
            }
            
            OnboardingState.ERROR -> {
                InitializationErrorScreen(
                    modifier = modifier,
                    errorMessage = errorMessage,
                    onRetry = {
                        mainViewModel.updateOnboardingState(OnboardingState.CHECKING)
                        checkOnboardingStatus()
                    },
                    onOpenSettings = {
                        onboardingCoordinator.openAppSettings()
                    }
                )
            }
        }
    }
    
    private fun handleOnboardingStateChange(state: OnboardingState) {

        when (state) {
            OnboardingState.COMPLETE -> {
                // App is fully initialized, mesh service is running
                android.util.Log.i("MainActivity", "Onboarding completed - app ready")
            }
            OnboardingState.ERROR -> {
                android.util.Log.e("MainActivity", "Onboarding error state reached")
            }
            else -> {}
        }
    }
    
    private fun checkOnboardingStatus() {
        lifecycleScope.launch {
            // Small delay to show the checking state
            delay(500)
            
            // First check Bluetooth status (always required)
            checkBluetoothAndProceed()
        }
    }
    
    /**
     * Check Bluetooth status and proceed with onboarding flow
     */
    private fun checkBluetoothAndProceed() {
        // For first-time users, skip Bluetooth check and go straight to permissions
        // We'll check Bluetooth after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }
        if (!permissionManager.areRequiredPermissionsGranted()) {
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check Bluetooth status first
        bluetoothStatusManager.logBluetoothStatus()
        mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())
        
        val bleRequired = try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        if (!bleRequired) {
            // Skip BLE checks entirely when BLE is disabled in debug settings
            checkLocationAndProceed()
            return
        }
        when (mainViewModel.bluetoothStatus.value) {
            BluetoothStatus.ENABLED -> {
                // Bluetooth is enabled, check location services next
                checkLocationAndProceed()
            }
            BluetoothStatus.DISABLED -> {
                // Show Bluetooth enable screen (should have permissions as existing user)
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                // Device doesn't support Bluetooth
                android.util.Log.e("MainActivity", "Bluetooth not supported")
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
        }
    }
    
    /**
     * Proceed with permission checking 
     */
    private fun proceedWithPermissionCheck() {
        lifecycleScope.launch {
            delay(200) // Small delay for smooth transition

            if (permissionManager.isFirstTimeLaunch()) {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (permissionManager.getUnrequestedOptionalPermissions().isNotEmpty()) {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (permissionManager.areRequiredPermissionsGranted()) {
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            } else {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
        }
    }
    
    /**
     * Handle Bluetooth enabled callback
     */
    private fun handleBluetoothEnabled() {
        mainViewModel.updateBluetoothLoading(false)
        mainViewModel.updateBluetoothStatus(BluetoothStatus.ENABLED)
        checkLocationAndProceed()
    }

    /**
     * Check Location services status and proceed with onboarding flow
     */
    private fun requiresSystemLocationForMesh(): Boolean =
        !BuildConfig.WESH_POLICY || Build.VERSION.SDK_INT < 31

    private fun checkLocationAndProceed() {
        // For first-time users, skip location check and go straight to permissions
        // We'll check location after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }
        
        if (!requiresSystemLocationForMesh()) {
            checkBatteryOptimizationAndProceed()
            return
        }

        // For existing users, check location status
        locationStatusManager.logLocationStatus()
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())
        
        when (mainViewModel.locationStatus.value) {
            LocationStatus.ENABLED -> {
                // Location services enabled, check battery optimization next
                checkBatteryOptimizationAndProceed()
            }
            LocationStatus.DISABLED -> {
                // Show location enable screen (should have permissions as existing user)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            LocationStatus.NOT_AVAILABLE -> {
                // Device doesn't support location services (very unusual)
                Log.e("MainActivity", "Location services not available")
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }

    /**
     * Handle Location enabled callback
     */
    private fun handleLocationEnabled() {
        if (!requiresSystemLocationForMesh()) return
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(LocationStatus.ENABLED)
        checkBatteryOptimizationAndProceed()
    }

    /**
     * Handle Location disabled callback
     */
    private fun handleLocationDisabled(message: String) {
        if (!requiresSystemLocationForMesh()) return
        Log.w("MainActivity", "Location services disabled or failed: $message")
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())

        mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
    }
    
    /**
     * Handle Bluetooth disabled callback
     */
    private fun handleBluetoothDisabled(message: String) {
        Log.w("MainActivity", "Bluetooth disabled or failed: $message")
        mainViewModel.updateBluetoothLoading(false)
        mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())
        
        when {
            mainViewModel.bluetoothStatus.value == BluetoothStatus.NOT_SUPPORTED -> {
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
            }
            !permissionManager.areRequiredPermissionsGranted() -> {
                // Actual permission state owns recovery; platform error wording varies.
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
            mainViewModel.onboardingState.value == OnboardingState.COMPLETE -> showBluetoothCheckUnlessItReturns()
            else -> {
                // Stay on Bluetooth check screen for retry
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
            }
        }
    }

    private var bluetoothOffCheck: kotlinx.coroutines.Job? = null

    /**
     * Shows the Bluetooth page only if Bluetooth stays off: some tablets turn it off on every
     * sleep and back on a few seconds after waking, and the page would flash each time.
     */
    private fun showBluetoothCheckUnlessItReturns() {
        if (bluetoothOffCheck?.isActive == true) return
        bluetoothOffCheck = lifecycleScope.launch {
            delay(BLUETOOTH_OFF_GRACE_MS)
            var waited = 0L
            while (bluetoothTurningOn() && waited < BLUETOOTH_TURNING_ON_MAX_MS) { delay(500); waited += 500 }
            val status = bluetoothStatusManager.checkBluetoothStatus()
            mainViewModel.updateBluetoothStatus(status)
            if (status != BluetoothStatus.ENABLED) {
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
                return@launch
            }

            // onResume returned as soon as Bluetooth entered the grace period. When the radio
            // recovers on Android 11 and below, resume the remaining gate instead of silently
            // skipping the system Location switch that BLE scanning still requires there.
            if (requiresSystemLocationForMesh()) {
                val location = locationStatusManager.checkLocationStatus()
                mainViewModel.updateLocationStatus(location)
                if (location != LocationStatus.ENABLED) {
                    mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                    mainViewModel.updateLocationLoading(false)
                }
            }
        }
    }

    private fun bluetoothTurningOn(): Boolean = try {
        (getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.state) ==
            android.bluetooth.BluetoothAdapter.STATE_TURNING_ON
    } catch (_: SecurityException) { false }
    
    private fun handleOnboardingComplete() {
        // After permissions are granted, re-check Bluetooth, Location, and Battery Optimization status
        val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
        val currentLocationStatus = if (requiresSystemLocationForMesh()) locationStatusManager.checkLocationStatus() else null
        val currentBatteryOptimizationStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        
        val bleRequired2 = try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        when {
            bleRequired2 && currentBluetoothStatus != BluetoothStatus.ENABLED -> {
                // Bluetooth still disabled, but now we have permissions to enable it
                mainViewModel.updateBluetoothStatus(currentBluetoothStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
            currentLocationStatus != null && currentLocationStatus != LocationStatus.ENABLED -> {
                // Location services still disabled, but now we have permissions to enable it
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            !BuildConfig.WESH_POLICY && currentBatteryOptimizationStatus == BatteryOptimizationStatus.ENABLED -> {
                // Battery optimization still enabled, show battery optimization screen
                mainViewModel.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                mainViewModel.updateBatteryOptimizationLoading(false)
            }
            else -> {
                // Both are enabled, proceed to app initialization
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            }
        }
    }
    
    private fun handleOnboardingFailed(message: String) {
        Log.e("MainActivity", "Onboarding failed: $message")
        if (!permissionManager.areRequiredPermissionsGranted()) {
            mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            return
        }
        mainViewModel.updateErrorMessage(message)
        mainViewModel.updateOnboardingState(OnboardingState.ERROR)
    }

    private fun startMeshForegroundServiceBestEffort() {
        if (!runtimeAttached) return
        if (BuildConfig.WESH_POLICY && !com.bitchat.android.startup.WeshRuntime.transportAllowed) {
            pendingMeshForegroundServiceStart = false
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingMeshForegroundServiceStart = true
            Log.i("MainActivity", "Deferring foreground mesh service start until activity is started")
            return
        }

        try {
            com.bitchat.android.service.MeshForegroundService.start(applicationContext)
            pendingMeshForegroundServiceStart = false
        } catch (e: Exception) {
            pendingMeshForegroundServiceStart = true
            Log.w("MainActivity", "Unable to start foreground mesh service; will retry when activity is started", e)
        }
    }
    
    /**
     * Check Battery Optimization status and proceed with onboarding flow
     */
    private fun checkBatteryOptimizationAndProceed() {
        // Wesh never shows BitChat's battery optimization screen: the Pavé welcome is the onboarding.
        if (BuildConfig.WESH_POLICY) {
            proceedWithPermissionCheck()
            return
        }
        // For first-time users, skip battery optimization check and go straight to permissions
        // We'll check battery optimization after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        // Check if user has previously skipped battery optimization
        if (BatteryOptimizationPreferenceManager.isSkipped(this)) {
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check battery optimization status
        batteryOptimizationManager.logBatteryOptimizationStatus()
        val currentBatteryOptimizationStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        mainViewModel.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
        
        when (currentBatteryOptimizationStatus) {
            BatteryOptimizationStatus.DISABLED, BatteryOptimizationStatus.NOT_SUPPORTED -> {
                // Battery optimization is disabled or not supported, proceed with permission check
                proceedWithPermissionCheck()
            }
            BatteryOptimizationStatus.ENABLED -> {
                // Show battery optimization disable screen
                mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                mainViewModel.updateBatteryOptimizationLoading(false)
            }
        }
    }
    
    /**
     * Handle Battery Optimization disabled callback
     */
    private fun handleBatteryOptimizationDisabled() {
        mainViewModel.updateBatteryOptimizationLoading(false)
        mainViewModel.updateBatteryOptimizationStatus(BatteryOptimizationStatus.DISABLED)
        proceedWithPermissionCheck()
    }
    
    /**
     * Handle Battery Optimization failed callback
     */
    private fun handleBatteryOptimizationFailed(message: String) {
        android.util.Log.w("MainActivity", "Battery optimization disable failed: $message")
        mainViewModel.updateBatteryOptimizationLoading(false)
        val currentStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        mainViewModel.updateBatteryOptimizationStatus(currentStatus)
        
        // Stay on battery optimization check screen for retry
        mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
    }
    
    private fun initializeApp() {
        lifecycleScope.launch {
            try {
                // Initialize the app with a proper delay to ensure Bluetooth stack is ready
                // This solves the issue where app needs restart to work on first install
                delay(1000) // Give the system time to process permission grants

                // Ensure all permissions are still granted (user might have revoked in settings)
                if (!permissionManager.areAllPermissionsGranted()) {
                    val missing = permissionManager.getMissingPermissions()
                    Log.w("MainActivity", "Permissions revoked during initialization: $missing")
                    // Back to the Pavé Bluetooth step, which explains and asks again: never a dead-end error.
                    mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
                    return@launch
                }

                // Set up unified mesh delegate and start enabled transports
                if (BuildConfig.WESH_POLICY && !com.bitchat.android.startup.WeshRuntime.transportAllowed) return@launch
                unifiedMeshService.delegate = chatViewModel
                unifiedMeshService.startServices()
                startMeshForegroundServiceBestEffort()

                // Keep the latest launch route bounded until secure startup and onboarding finish.

                // Small delay to ensure mesh service is fully initialized
                delay(500)
                Log.i("MainActivity", "App initialization complete")
                // Before any screen can show my QR. Covers phones onboarded before keys were made here.
                if (BuildConfig.WESH_POLICY) com.bitchat.android.services.WeshQrIdentity.ensureKey(applicationContext)
                mainViewModel.updateOnboardingState(OnboardingState.COMPLETE)
                dispatchPendingRoute()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to initialize app", e)
                handleOnboardingFailed(getString(R.string.wesh_pave_fluo_init_failed))
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Check if this is a quit request from the notification
        if (intent.getBooleanExtra("ACTION_QUIT_APP", false)) {
            android.util.Log.d("MainActivity", "Quit request received, finishing activity")
            finish()
            return
        }

        setIntent(if (BuildConfig.WESH_POLICY) com.bitchat.android.startup.boundedWeshStartupIntent(intent) else intent)
        routeGate.pending = true
        com.bitchat.android.service.AppShutdownCoordinator.cancelPendingShutdown()
        
        // Handle notification intents when app is already running
        if (runtimeAttached && mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            dispatchPendingRoute()
        }
    }

    private fun dispatchPendingRoute() {
        routeGate.dispatchIfReady(runtimeAttached, mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            handleNotificationIntent(intent)
            handleVerificationIntent(intent)
            if (intent.action == com.bitchat.android.ui.pave.PaveShortcuts.ACTION_HOLD_TO_ERASE) {
                intent.action = null
                com.bitchat.android.ui.pave.PaveShortcuts.requestErase()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("wesh_pending_route", routeGate.pending)
        if (BuildConfig.WESH_POLICY && routeGate.pending) {
            outState.putParcelable("wesh_pending_intent", com.bitchat.android.startup.boundedWeshStartupIntent(intent))
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (pendingMeshForegroundServiceStart) {
            startMeshForegroundServiceBestEffort()
        }
    }
    
    override fun onResume() {
        super.onResume()

        // Check Bluetooth and Location status on resume and handle accordingly
        if (runtimeAttached && mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            if (!permissionManager.areRequiredPermissionsGranted()) {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
                return
            }
            // Reattach mesh delegate to new ChatViewModel instance after Activity recreation
            try { unifiedMeshService.delegate = chatViewModel } catch (_: Exception) { }

            // Check if Bluetooth was disabled while app was backgrounded
            val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
            val bleRequired = try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
            if (bleRequired && currentBluetoothStatus != BluetoothStatus.ENABLED) {
                Log.w("MainActivity", "Bluetooth disabled while app was backgrounded")
                mainViewModel.updateBluetoothStatus(currentBluetoothStatus)
                showBluetoothCheckUnlessItReturns()
                return
            }
            
            if (!requiresSystemLocationForMesh()) return

            // Check if location services were disabled while app was backgrounded
            val currentLocationStatus = locationStatusManager.checkLocationStatus()
            if (currentLocationStatus != LocationStatus.ENABLED) {
                Log.w("MainActivity", "Location services disabled while app was backgrounded")
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Only set background state if app is fully initialized
        if (runtimeAttached && mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Detach UI delegate so the foreground service can own DM notifications while UI is closed
            try { unifiedMeshService.delegate = null } catch (_: Exception) { }
        }
    }
    
    /**
     * Handle intents from notification clicks - open a specific private chat
     */
    private fun handleNotificationIntent(intent: Intent) {
        val shouldOpenPrivateChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, 
            false
        )
        
        when {
            shouldOpenPrivateChat -> {
                val peerID = intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_PEER_ID)
                if (peerID != null) {
                    // Open the private chat sheet with this peer
                    if (BuildConfig.WESH_POLICY) {
                        val seenToken = com.bitchat.android.services.SeenMessageStore.captureReceiptToken()
                        lifecycleScope.launch { chatViewModel.startPrivateChat(peerID, seenToken) }
                    } else { chatViewModel.showMeshPeerList(); chatViewModel.showPrivateChatSheet(peerID) }
                    
                    // Clear notifications for this sender since user is now viewing the chat
                    chatViewModel.clearNotificationsForSender(peerID)
                }
            }
        }
    }

    private fun handleVerificationIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "bitchat" || uri.host != "verify") return

        if (BuildConfig.WESH_POLICY) {
            // A forwarded URL must not enroll silently: show review and require an explicit action.
            chatViewModel.requestWeshQRReview(uri.toString())
            return
        }
        chatViewModel.showVerificationSheet()
        val qr = VerificationService.verifyScannedQR(uri.toString())
        if (qr != null) {
            chatViewModel.beginQRVerification(qr)
        }
    }

    
    override fun onDestroy() {
        super.onDestroy()
        
        try { unregisterReceiver(forceFinishReceiver) } catch (_: Exception) { }
        
        // Cleanup location status manager
        try {
            if (::locationStatusManager.isInitialized) locationStatusManager.cleanup()
        } catch (e: Exception) {
            Log.w("MainActivity", "Error cleaning up location status manager: ${e.message}")
        }
        
        // Do not stop mesh here; ForegroundService owns lifecycle for background reliability
    }
}

/** The nickname the welcome starts from: the stored one, unless it is BitChat's default "anon1234". */
internal fun welcomeNickname(welcomed: Boolean, stored: String): String? = when {
    welcomed -> stored
    stored.isBlank() || Regex("^anon\\d{4}$").matches(stored) -> null
    else -> stored
}

/** First launch starts at the welcome; a returning person goes straight to what is missing. */
internal fun welcomeStart(welcomed: Boolean, requiredGranted: Boolean, notificationsPending: Boolean) = when {
    !welcomed && !requiredGranted -> com.bitchat.android.ui.pave.OnboardingStart.Welcome
    !requiredGranted -> com.bitchat.android.ui.pave.OnboardingStart.Bluetooth
    notificationsPending -> com.bitchat.android.ui.pave.OnboardingStart.Notifications
    else -> com.bitchat.android.ui.pave.OnboardingStart.Welcome
}

/** How long Bluetooth may stay off before the app asks to turn it on. */
private const val BLUETOOTH_OFF_GRACE_MS = 5_000L
/** How much longer to wait while Android reports Bluetooth as turning on. */
private const val BLUETOOTH_TURNING_ON_MAX_MS = 10_000L
