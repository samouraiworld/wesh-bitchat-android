package com.bitchat.android.store

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.bitchat.android.BuildConfig
import com.bitchat.android.ui.pave.AboutScreen
import com.bitchat.android.ui.pave.ConnexionSheet
import com.bitchat.android.ui.pave.ExchangeProgress
import com.bitchat.android.ui.pave.ExchangeScreen
import com.bitchat.android.ui.pave.FakeWeshInternetModeState
import com.bitchat.android.ui.pave.HomeScreen
import com.bitchat.android.ui.pave.PaveBluetoothInfo
import com.bitchat.android.ui.pave.PaveComposerState
import com.bitchat.android.ui.pave.PaveContactItem
import com.bitchat.android.ui.pave.PaveDelivery
import com.bitchat.android.ui.pave.PaveHomeState
import com.bitchat.android.ui.pave.PaveInternetStatus
import com.bitchat.android.ui.pave.PaveMessageItem
import com.bitchat.android.ui.pave.PaveSettingsSummary
import com.bitchat.android.ui.pave.PaveTransport
import com.bitchat.android.ui.pave.PrivateChatScreen
import com.bitchat.android.ui.pave.PublicRoomScreen
import com.bitchat.android.ui.pave.SettingsScreen
import com.bitchat.android.ui.pave.WeshAppearance
import com.bitchat.android.ui.pave.WeshPave
import com.bitchat.android.ui.pave.WeshPaveTheme
import com.bitchat.android.ui.pave.WeshThemeId
import java.util.Locale
import org.json.JSONObject

/**
 * Renders seven deterministic, synthetic store scenes. This class and its no-runtime Application
 * exist only in the debug-signed `storeScreenshot` source set and cannot enter Release.
 */
class StoreScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        check(BuildConfig.STORE_SCREENSHOT_MODE) { "Store capture is disabled for this build" }
        applyFixtureLocale(intent.getStringExtra(EXTRA_LANGUAGE))
        super.onCreate(savedInstanceState)

        // The product activity always applies FLAG_SECURE. Only this isolated activity clears it.
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(true)
        }
        enableEdgeToEdge()
        val language = normalizedLanguage(intent.getStringExtra(EXTRA_LANGUAGE))
        val scene = intent.getStringExtra(EXTRA_SCENE).takeIf { it in SCENES } ?: SCENES.first()

        val fixture = StoreFixture.load(this, language)
        setContent {
            MirroredProductionFrame {
                StoreScene(scene, fixture)
                LaunchedEffect(scene, language) {
                    // The runner disables OS animations. Await real composition frames instead
                    // of a fixed capture delay or a forced product-runtime state.
                    repeat(2) { withFrameNanos { } }
                    reportFullyDrawn()
                    Log.i("WeshStoreCapture", "READY $language $scene")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyFixtureLocale(requested: String?) {
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(normalizedLanguage(requested)))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    companion object {
        const val EXTRA_SCENE = "scene"
        const val EXTRA_LANGUAGE = "language"
        val SCENES = listOf("home", "public", "private", "exchange", "settings", "connection", "about")

        private fun normalizedLanguage(requested: String?): String = if (requested == "fr") "fr" else "en"
    }
}

/**
 * Infrastructure mirror, not a shared product root. MainActivity wraps the app in this standard
 * Theme/Scaffold. Its COMPLETE route passes through OnboardingFlowScreen -> WeshScreen without
 * forwarding the computed innerPadding; real screens consume their own system-bar insets.
 * Additional padding here would change the frozen layout. The manifest binds the root-chain files
 * and the complete app/src/main tree before the captures can be reviewed.
 */
@androidx.compose.runtime.Composable
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
private fun MirroredProductionFrame(content: @androidx.compose.runtime.Composable () -> Unit) {
    WeshPaveTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
        ) { _ ->
            WeshPaveTheme(WeshThemeId.Nuit, content)
        }
    }
}

private data class StoreFixture(
    val self: String,
    val contact: String,
    val secondContact: String,
    val homePublic: String,
    val homePrivate: String,
    val publicIncoming: String,
    val publicOutgoing: String,
    val privateIncoming: String,
    val privateOutgoing: String,
    val meeting: String,
) {
    companion object {
        fun load(activity: ComponentActivity, language: String): StoreFixture {
            val root = activity.assets.open("store_scenes.json").bufferedReader().use { JSONObject(it.readText()) }
            check(root.getBoolean("synthetic")) { "Store fixtures must declare synthetic provenance" }
            check(root.getJSONArray("sceneOrder").let { scenes ->
                (0 until scenes.length()).map(scenes::getString)
            } == StoreScreenshotActivity.SCENES) { "Store fixture scene order changed" }
            val data = root.getJSONObject(language)
            return StoreFixture(
                self = data.getString("self"),
                contact = data.getString("contact"),
                secondContact = data.getString("secondContact"),
                homePublic = data.getString("homePublic"),
                homePrivate = data.getString("homePrivate"),
                publicIncoming = data.getString("publicIncoming"),
                publicOutgoing = data.getString("publicOutgoing"),
                privateIncoming = data.getString("privateIncoming"),
                privateOutgoing = data.getString("privateOutgoing"),
                meeting = data.getString("meeting"),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun StoreScene(scene: String, f: StoreFixture) {
    when (scene) {
        "home" -> StoreHomeScene(f, phonesInReach = 3, bluetoothOn = true, internetPill = null)

        "public" -> PublicRoomScreen(
            phonesInReach = 3,
            messages = listOf(
                PaveMessageItem("synthetic-public-01", f.publicIncoming, false, "18:03", f.secondContact, FINGERPRINT_B, transport = PaveTransport.Bluetooth),
                PaveMessageItem("synthetic-public-02", f.publicOutgoing, true, "18:04", null, null, delivery = PaveDelivery.Sent),
            ),
            composer = PaveComposerState("", canSend = true),
            onDraftChange = {},
            onSend = {},
            onBack = {},
            onMessageActions = {},
        )

        "private" -> PrivateChatScreen(
            nickname = f.contact,
            fingerprint = FINGERPRINT_A,
            verified = true,
            messages = listOf(
                PaveMessageItem("synthetic-private-01", f.privateIncoming, false, "18:05", null, null, transport = PaveTransport.Bluetooth),
                PaveMessageItem("synthetic-private-02", f.privateOutgoing, true, "18:06", null, null, delivery = PaveDelivery.Delivered, transport = PaveTransport.Bluetooth),
            ),
            composer = PaveComposerState("", canSend = true),
            onDraftChange = {},
            onSend = {},
            onRetry = {},
            onBack = {},
            onOpenContact = {},
        )

        "exchange" -> ExchangeScreen(
            myQrPayload = "wesh://store-capture/synthetic-only",
            myNickname = f.self,
            myFingerprint = FINGERPRINT_SELF,
            renewsInMinutes = 4,
            progress = ExchangeProgress(theyScannedMe = true, iScannedThem = true),
            contactNickname = f.contact,
            contactFingerprint = FINGERPRINT_A,
            onScanned = {},
            onCompleted = {},
            onClose = {},
        )

        "settings" -> SettingsScreen(
            summary = PaveSettingsSummary(f.self, previewsHidden = true, meeting = f.meeting, blockedCount = 0, appearance = WeshAppearance.Nuit),
            onErase = {},
            onOpen = {},
            autoStartOnBoot = false,
            onAutoStartOnBoot = {},
            onBack = {},
            modifier = Modifier.background(WeshPave.colors.bg),
        )

        "connection" -> Box(Modifier.fillMaxSize()) {
            StoreHomeScene(
                f,
                phonesInReach = 0,
                bluetoothOn = false,
                internetPill = PaveInternetStatus.On(relays = 2),
            )
            val internet = remember {
                FakeWeshInternetModeState(
                    available = true,
                    enabled = true,
                    status = PaveInternetStatus.On(relays = 2),
                    disclosureAccepted = true,
                )
            }
            ConnexionSheet(
                bluetooth = PaveBluetoothInfo(on = false, phonesInRange = 0),
                internet = internet,
                onDismiss = {},
            )
        }

        "about" -> AboutScreen(versionName = "1.0.0", onBack = {}, onOpenLicenses = {})
    }
}

@androidx.compose.runtime.Composable
private fun StoreHomeScene(
    f: StoreFixture,
    phonesInReach: Int,
    bluetoothOn: Boolean,
    internetPill: PaveInternetStatus?,
) {
    HomeScreen(
        state = PaveHomeState(
            phonesInReach = phonesInReach,
            bluetoothOn = bluetoothOn,
            publicPreviewSender = f.secondContact,
            publicPreviewText = f.homePublic,
            publicTime = "18:04",
            contacts = listOf(
                PaveContactItem("synthetic-01", f.contact, FINGERPRINT_A, true, f.homePrivate, "18:06"),
                PaveContactItem("synthetic-02", f.secondContact, FINGERPRINT_B, true, null, null),
            ),
            requestCount = 1,
            internetPill = internetPill,
        ),
        onOpenPublicRoom = {},
        onOpenContact = {},
        onOpenRequests = {},
        onExchange = {},
        onOpenSettings = {},
        onRetryContacts = {},
    )
}

private const val FINGERPRINT_SELF = "1020 3040 5060 7080"
private const val FINGERPRINT_A = "A1B2 C3D4 E5F6 0718"
private const val FINGERPRINT_B = "5E90 77AC 1B3F D402"
