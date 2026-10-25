package com.bitchat.android.ui.pave

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.ui.hasQrCamera
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

/**
 * Échanger: your code on top, the camera below, both live so two people verify each other in one go.
 * Two checks: "they scanned you" and "you scanned them". With both, the camera stops and a success
 * card shows the contact's name with a short key fingerprint, so the nickname is never the only
 * identity cue; [onCompleted] follows only on "Ouvrir la discussion", never on a timer.
 * Your own fingerprint sits under your code. The checks never show a name.
 *
 * @param myQrPayload the `bitchat://verify` payload built by the app; null while it is not ready.
 * @param renewsInMinutes minutes before the code renews itself (it expires after five).
 * @param onScanned every newly decoded text, unparsed; the caller verifies it.
 * @param myFingerprint [PaveFingerprint.short] of your Noise static key, computed off the main thread.
 * @param contactFingerprint the same for the contact's verified key, once known; shown on success.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ExchangeScreen(
    myQrPayload: String?,
    myNickname: String,
    myFingerprint: String?,
    renewsInMinutes: Int,
    progress: ExchangeProgress,
    contactNickname: String?,
    contactFingerprint: String?,
    onScanned: (String) -> Unit,
    onCompleted: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    decoder: QrFrameDecoder = ZxingQrFrameDecoder,
) {
    val c = WeshPave.colors
    val haptics = rememberWeshHaptics()
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    // Only an explicit tap opens the chat, and only once.
    var completed by rememberSaveable { mutableStateOf(false) }
    val complete = { completed = completeOnce(completed) { currentOnCompleted() } }
    LaunchedEffect(progress.iScannedThem) { if (progress.iScannedThem) haptics.success() }

    val modules = remember(myQrPayload) { myQrPayload?.let(::encodeQrModules) }
    // The contact's nickname comes from their QR code: sanitized here so no caller can forget it.
    val contact = remoteDisplayName(contactNickname)
    val me = remoteDisplayName(myNickname)
    val fingerprints = exchangeFingerprints(myFingerprint, contactFingerprint, progress)
    val shape = RoundedCornerShape(WeshRadius.control)

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        PaveHeader(stringResource(R.string.wesh_pave_exchange_title), onClose = onClose)
        Text(stringResource(R.string.wesh_pave_exchange_intro), style = WeshType.body, color = c.ink, modifier = Modifier.fillMaxWidth())

        val codeLabel = stringResource(R.string.wesh_pave_exchange_my_code)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(shape)
                .background(WeshTokens.QrBackground)
                .padding(WeshSpace.s4)
                .semantics { contentDescription = codeLabel },
        ) {
            if (modules != null) {
                QrModules(modules, Modifier.size(200.dp))
            } else {
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.wesh_pave_qr_unavailable),
                        style = WeshType.label,
                        color = WeshTokens.QrModule,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.wesh_pave_exchange_renew, me, renewsInMinutes),
            style = WeshType.caption,
            color = c.inkMuted,
        )
        FingerprintBlock(stringResource(R.string.wesh_pave_fingerprint_mine), fingerprints.mine, Modifier.fillMaxWidth())

        if (progress.complete) {
            ExchangeSuccess(contact, fingerprints.contact, onOpen = complete)
        } else {
            CameraArea(onScanned, decoder)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(WeshSpace.s3), modifier = Modifier.fillMaxWidth()) {
            ExchangeStep(
                done = progress.theyScannedMe,
                text = if (progress.theyScannedMe) stringResource(R.string.wesh_pave_exchange_they_scanned)
                else stringResource(R.string.wesh_pave_exchange_they_pending),
                modifier = Modifier.weight(1f),
            )
            ExchangeStep(
                done = progress.iScannedThem,
                text = if (progress.iScannedThem) stringResource(R.string.wesh_pave_exchange_i_scanned)
                else stringResource(R.string.wesh_pave_exchange_i_pending),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.wesh_pave_exchange_footer),
            style = WeshType.caption,
            color = c.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraArea(onScanned: (String) -> Unit, decoder: QrFrameDecoder) {
    val c = WeshPave.colors
    val shape = RoundedCornerShape(WeshRadius.control)
    val box = Modifier.fillMaxWidth().height(240.dp).clip(shape).background(c.surface)
    if (LocalInspectionMode.current) {
        Box(box, contentAlignment = Alignment.Center) { Viewfinder() }
        return
    }
    val context = LocalContext.current
    val hasCamera = remember(context) { hasQrCamera(context.packageManager) }
    if (!hasCamera) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(shape).background(c.surface).padding(WeshSpace.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WeshSpace.s3, Alignment.CenterVertically),
        ) {
            Icon(Icons.Rounded.NoPhotography, contentDescription = null, tint = c.warning, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.wesh_camera_unavailable), style = WeshType.headline, color = c.ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.wesh_camera_unavailable_body), style = WeshType.body, color = c.ink, textAlign = TextAlign.Center)
        }
        return
    }
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    // Never asked on open: the rationale comes first, the request only on "Autoriser la caméra".
    var asked by rememberSaveable { mutableStateOf(false) }
    val card = cameraCard(camera.status.isGranted, asked, camera.status.shouldShowRationale)
    if (card == CameraCard.Scanner) {
        Box(box, contentAlignment = Alignment.Center) {
            PaveQrScanner(onScanned, Modifier.fillMaxSize(), decoder)
            Viewfinder()
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(shape).background(c.surface).padding(WeshSpace.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s3, Alignment.CenterVertically),
    ) {
        if (card == CameraCard.Settings) {
            Icon(Icons.Rounded.NoPhotography, contentDescription = null, tint = c.warning, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.wesh_pave_camera_denied), style = WeshType.headline, color = c.ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.wesh_pave_camera_denied_body), style = WeshType.body, color = c.ink, textAlign = TextAlign.Center)
            SecondaryButton(stringResource(R.string.wesh_pave_open_settings), {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            })
        } else {
            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = c.ink, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.wesh_pave_camera_rationale), style = WeshType.body, color = c.ink, textAlign = TextAlign.Center)
            SecondaryButton(stringResource(R.string.wesh_pave_camera_allow), {
                asked = true
                camera.launchPermissionRequest()
            })
        }
    }
}

/** Short fingerprints Échanger shows: yours under your code, the contact's once both checks are done. */
internal data class ExchangeFingerprints(val mine: String?, val contact: String?)

internal fun exchangeFingerprints(myFingerprint: String?, contactFingerprint: String?, progress: ExchangeProgress) =
    ExchangeFingerprints(mine = myFingerprint, contact = if (progress.complete) contactFingerprint else null)

/** Runs [action] unless [done] is already set; returns the new flag, so completion happens once. */
internal fun completeOnce(done: Boolean, action: () -> Unit): Boolean {
    if (!done) action()
    return true
}

/** What the camera area shows: the scanner, the rationale with "Autoriser", or the way to Settings. */
internal enum class CameraCard { Scanner, Rationale, Settings }

/** Settings only after a permanent refusal (asked, and Android no longer shows its own dialog). */
internal fun cameraCard(granted: Boolean, asked: Boolean, shouldShowRationale: Boolean): CameraCard = when {
    granted -> CameraCard.Scanner
    asked && !shouldShowRationale -> CameraCard.Settings
    else -> CameraCard.Rationale
}

@Composable
private fun ExchangeSuccess(contact: String, fingerprint: String?, onOpen: () -> Unit) {
    val c = WeshPave.colors
    val shape = RoundedCornerShape(WeshRadius.control)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(WeshStroke.s2, c.success, shape)
            .padding(WeshSpace.s4),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2),
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(Icons.Rounded.Verified, contentDescription = null, tint = c.success, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.wesh_pave_exchange_success), style = WeshType.label, color = c.success)
        }
        Text(contact, style = WeshType.headline, color = c.ink)
        FingerprintBlock(stringResource(R.string.wesh_pave_fingerprint_contact), fingerprint)
        PrimaryButton(stringResource(R.string.wesh_pave_exchange_open_chat), onOpen)
    }
}

/** [label] (whose key it is) and the short fingerprint in monospace; TalkBack reads it character by character. */
@Composable
private fun FingerprintBlock(label: String, fingerprint: String?, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    if (fingerprint == null) {
        Text(stringResource(R.string.wesh_pave_fingerprint_unavailable), style = WeshType.caption, color = c.warning, modifier = modifier)
        return
    }
    val spoken = "$label : ${PaveFingerprint.spoken(fingerprint)}"
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = WeshType.caption, color = c.inkMuted)
        Text(fingerprint, style = WeshType.fingerprint, color = c.ink)
    }
}

@Composable
private fun Viewfinder() {
    val c = WeshPave.colors
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize().padding(WeshSpace.s3)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(PAVE_QR_VIEWFINDER)
                .border(WeshStroke.publicRule, c.publicColor, RoundedCornerShape(WeshRadius.control))
        )
        Text(
            stringResource(R.string.wesh_pave_exchange_aim),
            style = WeshType.label,
            color = c.ink,
            modifier = Modifier.clip(RoundedCornerShape(WeshRadius.control)).background(c.bg).padding(horizontal = WeshSpace.s3, vertical = WeshSpace.s1),
        )
    }
}

@Composable
private fun ExchangeStep(done: Boolean, text: String, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    val color = if (done) c.success else c.ink
    val state = stringResource(if (done) R.string.wesh_pave_step_done else R.string.wesh_pave_step_pending)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WeshSpace.s1),
        modifier = modifier
            .heightIn(min = WeshTouch.min)
            .border(WeshStroke.s2, if (done) c.success else c.lineStrong, RoundedCornerShape(WeshRadius.control))
            .padding(horizontal = WeshSpace.s3, vertical = WeshSpace.s2)
            .semantics(mergeDescendants = true) { stateDescription = state },
    ) {
        Icon(if (done) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = WeshType.label, color = color)
    }
}

@PaveScreenPreviews
@Composable
private fun ExchangeScreenPreview() = PavePreview(padded = false) {
    ExchangeScreen(
        myQrPayload = "wesh-preview-code",
        myNickname = "Renard 42",
        myFingerprint = "7F3A 91C2 0B5E 44D8",
        renewsInMinutes = 4,
        progress = ExchangeProgress(theyScannedMe = true),
        contactNickname = "Nora",
        contactFingerprint = null,
        onScanned = {}, onCompleted = {}, onClose = {},
    )
}

@PaveScreenPreviews
@Composable
private fun ExchangeSuccessPreview() = PavePreview(padded = false) {
    ExchangeScreen(
        myQrPayload = "wesh-preview-code",
        myNickname = "Renard 42",
        myFingerprint = "7F3A 91C2 0B5E 44D8",
        renewsInMinutes = 4,
        progress = ExchangeProgress(theyScannedMe = true, iScannedThem = true),
        contactNickname = "Nora",
        contactFingerprint = "A1B2 C3D4 E5F6 0718",
        onScanned = {}, onCompleted = {}, onClose = {},
    )
}
