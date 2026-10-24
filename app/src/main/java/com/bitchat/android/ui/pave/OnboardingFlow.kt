package com.bitchat.android.ui.pave

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.app.ActivityCompat
import androidx.compose.material.icons.rounded.Notifications
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bitchat.android.R
import com.bitchat.android.ui.WeshLinks

/** Where the welcome starts: first launch, back for a missing Bluetooth permission, or notifications only. */
enum class OnboardingStart { Welcome, Bluetooth, Notifications }

private enum class OnboardingStep { Welcome, Bluetooth, Notifications }

/** How the person answered the Bluetooth prompt. A simple refusal keeps "Continuer"; only a permanent one sends to Settings. */
internal enum class BluetoothRefusal { None, Once, Permanent }

/** The one way forward on the Bluetooth step: ask again (with the explanation) or open Settings. */
internal enum class BluetoothStepAction { Ask, OpenSettings }

internal fun bluetoothStepAction(refusal: BluetoothRefusal): BluetoothStepAction =
    if (refusal == BluetoothRefusal.Permanent) BluetoothStepAction.OpenSettings else BluetoothStepAction.Ask

internal fun bluetoothRefusal(granted: Boolean, shouldShowRationale: Boolean): BluetoothRefusal = when {
    granted -> BluetoothRefusal.None
    shouldShowRationale -> BluetoothRefusal.Once
    else -> BluetoothRefusal.Permanent
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * First launch: Bienvenue (editable nickname), then the Bluetooth explainer before the system
 * dialog, then, on Android 13+, the notifications explainer before its own dialog. On API 31+
 * Wesh never asks for location.
 *
 * @param onFinished nickname chosen, and whether the Bluetooth permissions were granted (always
 * true in V1: there is no way past the Bluetooth step without them).
 * @param initialNickname the nickname already chosen on this phone, if any; otherwise a random one.
 * @param askNotifications show the notifications step (Android 13+, not asked yet).
 * @param onNotificationsAsked the notifications prompt was shown or declined: it is never asked again.
 */
@Composable
fun OnboardingFlow(
    onFinished: (nickname: String, bluetoothGranted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sdkInt: Int = Build.VERSION.SDK_INT,
    initialNickname: String? = null,
    start: OnboardingStart = OnboardingStart.Welcome,
    askNotifications: Boolean = false,
    onNotificationsAsked: () -> Unit = {},
) {
    var step by rememberSaveable {
        mutableStateOf(
            when (start) {
                OnboardingStart.Welcome -> OnboardingStep.Welcome
                OnboardingStart.Bluetooth -> OnboardingStep.Bluetooth
                OnboardingStart.Notifications -> OnboardingStep.Notifications
            }
        )
    }
    // Someone back here for a missing permission keeps the nickname they already chose; the
    // notifications-only path never proposes one.
    var nickname by rememberSaveable {
        mutableStateOf(
            initialNickname?.takeIf(NicknameGenerator::isValid)
                ?: if (start == OnboardingStart.Welcome) NicknameGenerator.generate() else ""
        )
    }
    val context = LocalContext.current
    var refusal by rememberSaveable { mutableStateOf(BluetoothRefusal.None) }
    var bluetoothGranted by rememberSaveable { mutableStateOf(start == OnboardingStart.Notifications) }
    val permissions = paveBluetoothRuntimePermissions(sdkInt)

    fun afterBluetooth(granted: Boolean) {
        bluetoothGranted = granted
        if (askNotifications) step = OnboardingStep.Notifications else onFinished(nickname.trim(), granted)
    }
    fun afterNotifications() {
        onNotificationsAsked()
        onFinished(nickname.trim(), bluetoothGranted)
    }

    val inspection = LocalInspectionMode.current
    val request: () -> Unit = if (inspection) {
        {}
    } else {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val missing = permissions.filter { result[it] != true && ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            val activity = context.findActivity()
            refusal = bluetoothRefusal(
                granted = missing.isEmpty(),
                shouldShowRationale = activity != null && missing.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) },
            )
            if (refusal == BluetoothRefusal.None) afterBluetooth(true)
        };
        {
            val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) afterBluetooth(true) else launcher.launch(missing.toTypedArray())
        }
    }
    val requestNotifications: () -> Unit = if (inspection || sdkInt < Build.VERSION_CODES.TIRAMISU) {
        { afterNotifications() }
    } else {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterNotifications() };
        { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    // Back from Settings with Bluetooth now allowed: continue without another tap.
    LifecycleResumeEffect(step, refusal) {
        if (step == OnboardingStep.Bluetooth && refusal != BluetoothRefusal.None &&
            permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        ) {
            refusal = BluetoothRefusal.None
            afterBluetooth(true)
        }
        onPauseOrDispose {}
    }

    // From Bluetooth, back returns to the nickname, when the flow started there.
    val canGoBack = step == OnboardingStep.Bluetooth && start == OnboardingStart.Welcome
    BackHandler(canGoBack) { step = OnboardingStep.Welcome }

    Box(modifier.fillMaxSize().background(WeshPave.colors.bg).systemBarsPadding().imePadding()) {
        when (step) {
            OnboardingStep.Welcome -> WelcomeStep(
                nickname = nickname,
                onNicknameChange = { nickname = NicknameGenerator.clean(it) },
                onShuffle = { nickname = NicknameGenerator.next(nickname) },
                onStart = { step = OnboardingStep.Bluetooth },
            )
            OnboardingStep.Bluetooth -> BluetoothStep(
                legacyLocation = sdkInt < Build.VERSION_CODES.S,
                refusal = refusal,
                onBack = if (canGoBack) ({ step = OnboardingStep.Welcome }) else null,
                onContinue = request,
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
            OnboardingStep.Notifications -> NotificationsStep(onAllow = requestNotifications, onLater = ::afterNotifications)
        }
    }
}

@Composable
private fun NotificationsStep(onAllow: () -> Unit, onLater: () -> Unit) {
    val c = WeshPave.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        Icon(Icons.Rounded.Notifications, contentDescription = null, tint = c.ink, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.wesh_pave_fluo_notif_title), style = WeshType.title, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.wesh_pave_fluo_notif_body), style = WeshType.body, color = c.ink)
        Text(stringResource(R.string.wesh_pave_fluo_notif_optional), style = WeshType.body, color = c.ink)
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.wesh_pave_fluo_notif_allow), onAllow)
        SecondaryButton(stringResource(R.string.wesh_pave_fluo_notif_later), onLater, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WelcomeStep(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onShuffle: () -> Unit,
    onStart: () -> Unit,
) {
    val c = WeshPave.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        // Decorative street-poster blocks, hidden from TalkBack.
        Row(horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
            Box(Modifier.size(width = 72.dp, height = 24.dp).background(c.publicColor, RoundedCornerShape(WeshRadius.tail)))
            Box(Modifier.size(24.dp).background(c.privateColor, RoundedCornerShape(WeshRadius.tail)))
        }
        Text(stringResource(R.string.app_name).uppercase(), style = WeshType.wordmark, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.wesh_pave_welcome_tagline), style = WeshType.headline, color = c.ink)
        Spacer(Modifier.height(WeshSpace.s2))
        listOf(
            R.string.wesh_pave_welcome_point_account,
            R.string.wesh_pave_welcome_point_public,
            R.string.wesh_pave_welcome_point_private,
        ).forEach { Bullet(stringResource(it)) }
        Spacer(Modifier.height(WeshSpace.s4).weight(1f, fill = false))
        NicknameField(nickname, onNicknameChange, onShuffle)
        PrimaryButton(stringResource(R.string.wesh_pave_start), onStart, enabled = NicknameGenerator.isValid(nickname))
        TermsLine()
    }
}

@Composable
private fun Bullet(text: String) {
    val c = WeshPave.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(8.dp).background(c.publicColor, CircleShape))
        Spacer(Modifier.width(WeshSpace.s3))
        Text(text, style = WeshType.body, color = c.ink)
    }
}

@Composable
private fun NicknameField(nickname: String, onChange: (String) -> Unit, onShuffle: () -> Unit) {
    val c = WeshPave.colors
    val label = stringResource(R.string.wesh_pave_nickname_label)
    val shape = RoundedCornerShape(WeshRadius.control)
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
        Text(label, style = WeshType.label, color = c.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
            NoPersonalizedLearning {
                BasicTextField(
                    value = nickname,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = WeshType.body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = WeshTouch.primary)
                        .background(c.surface, shape)
                        .border(WeshStroke.s2, c.lineStrong, shape)
                        .padding(horizontal = WeshSpace.s4)
                        .semantics { contentDescription = label },
                    decorationBox = { inner -> Box(Modifier.heightIn(min = WeshTouch.primary), contentAlignment = Alignment.CenterStart) { inner() } },
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(WeshTouch.primary)
                    .paveClickable(shape, onClick = onShuffle)
                    .border(WeshStroke.s2, c.lineStrong, shape),
            ) {
                Icon(Icons.Rounded.Casino, contentDescription = stringResource(R.string.wesh_pave_nickname_shuffle), tint = c.ink)
            }
        }
        Text(stringResource(R.string.wesh_pave_nickname_help), style = WeshType.caption, color = c.inkMuted)
    }
}

@Composable
private fun TermsLine() {
    val c = WeshPave.colors
    val link = stringResource(R.string.wesh_pave_terms_link)
    val parts = stringResource(R.string.wesh_pave_terms, "\u0000").split("\u0000")
    val text = buildAnnotatedString {
        append(parts.first())
        withLink(LinkAnnotation.Url(WeshLinks.TERMS_URL, TextLinkStyles(SpanStyle(color = c.ink, textDecoration = TextDecoration.Underline)))) {
            append(link)
        }
        append(parts.getOrElse(1) { "" })
    }
    Text(text, style = WeshType.caption, color = c.inkMuted, modifier = Modifier.heightIn(min = WeshTouch.min).padding(vertical = WeshSpace.s2))
}

@Composable
private fun BluetoothStep(
    legacyLocation: Boolean,
    refusal: BluetoothRefusal,
    onBack: (() -> Unit)?,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = WeshPave.colors
    // Back sits where PaveHeader puts it on other screens, above the scrolling content.
    Column(Modifier.fillMaxSize()) {
    if (onBack != null) {
        PaveIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.wesh_pave_back), onBack,
            Modifier.padding(start = WeshSpace.s1, top = WeshSpace.s2))
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        Icon(Icons.Rounded.Sensors, contentDescription = null, tint = c.success, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.wesh_pave_bt_title), style = WeshType.title, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.wesh_pave_bt_body), style = WeshType.body, color = c.ink)
        if (legacyLocation) {
            Text(stringResource(R.string.wesh_pave_bt_ask_legacy), style = WeshType.body, color = c.ink)
            Text(stringResource(R.string.wesh_pave_bt_legacy_no_gps), style = WeshType.body, color = c.ink)
        } else {
            Text(stringResource(R.string.wesh_pave_bt_ask), style = WeshType.body, color = c.ink)
            Text(stringResource(R.string.wesh_pave_bt_no_location), style = WeshType.body, color = c.ink)
        }
        Spacer(Modifier.weight(1f))
        if (refusal != BluetoothRefusal.None) {
            Text(
                stringResource(if (refusal == BluetoothRefusal.Permanent) R.string.wesh_pave_fluo_bt_required else R.string.wesh_pave_bt_denied),
                style = WeshType.body,
                color = c.warning,
            )
        }
        when (bluetoothStepAction(refusal)) {
            BluetoothStepAction.Ask -> PrimaryButton(stringResource(R.string.wesh_pave_continue), onContinue)
            BluetoothStepAction.OpenSettings -> PrimaryButton(stringResource(R.string.wesh_pave_open_settings), onOpenSettings)
        }
    }
    }
}

@PaveScreenPreviews
@Composable
private fun OnboardingWelcomePreview() = PavePreview(padded = false) { OnboardingFlow({ _, _ -> }) }

@PaveScreenPreviews
@Composable
private fun OnboardingBluetoothPreview() = PavePreview(padded = false) {
    BluetoothStep(legacyLocation = false, refusal = BluetoothRefusal.None, onBack = {}, onContinue = {}, onOpenSettings = {})
}

@PavePreviews
@Composable
private fun OnboardingBluetoothDeniedPreview() = PavePreview(padded = false) {
    BluetoothStep(legacyLocation = true, refusal = BluetoothRefusal.Permanent, onBack = null, onContinue = {}, onOpenSettings = {})
}
