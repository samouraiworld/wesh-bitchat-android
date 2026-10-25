package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R

/** Bluetooth side of the Connexion sheet. */
data class PaveBluetoothInfo(val on: Boolean, val phonesInRange: Int)

/** Bottom sheet opened from the status pill: Bluetooth, phones in range, Internet mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnexionSheet(
    bluetooth: PaveBluetoothInfo,
    internet: WeshInternetModeState,
    onDismiss: () -> Unit,
) {
    val c = WeshPave.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        contentColor = c.ink,
        scrimColor = c.bg.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = WeshRadius.sheet, topEnd = WeshRadius.sheet),
        dragHandle = {
            Box(Modifier.padding(top = WeshSpace.s3).size(width = 40.dp, height = 5.dp).background(c.lineStrong, RoundedCornerShape(3.dp)))
        },
    ) {
        ConnexionSheetContent(bluetooth, internet, onDismiss, Modifier.navigationBarsPadding())
    }
}

@Composable
fun ConnexionSheetContent(
    bluetooth: PaveBluetoothInfo,
    internet: WeshInternetModeState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = WeshPave.colors
    val enabled by internet.enabled.collectAsStateWithLifecycle()
    val status by internet.status.collectAsStateWithLifecycle()
    val card = RoundedCornerShape(WeshRadius.control)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = WeshSpace.gutter, end = WeshSpace.gutter, top = WeshSpace.s3, bottom = WeshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        Text(stringResource(R.string.wesh_pave_connexion_title), style = WeshType.headline, color = c.ink, modifier = Modifier.semantics { heading() })

        // Bluetooth
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(c.surface, card).padding(WeshSpace.s4).semantics(mergeDescendants = true) {},
        ) {
            Icon(
                if (bluetooth.on) Icons.Rounded.Sensors else Icons.Rounded.BluetoothDisabled,
                contentDescription = null,
                tint = if (bluetooth.on) c.success else c.danger,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f).padding(start = WeshSpace.s3)) {
                Text(
                    stringResource(if (bluetooth.on) R.string.wesh_pave_bt_active else R.string.wesh_pave_status_bluetooth_off),
                    style = WeshType.body.copy(fontWeight = WeshType.label.fontWeight),
                    color = c.ink,
                )
                Text(
                    when {
                        !bluetooth.on -> stringResource(R.string.wesh_pave_bt_off_body)
                        bluetooth.phonesInRange <= 0 -> stringResource(R.string.wesh_pave_status_nobody)
                        else -> pluralStringResource(R.plurals.wesh_pave_status_in_range, bluetooth.phonesInRange, bluetooth.phonesInRange)
                    },
                    style = WeshType.label.copy(fontWeight = WeshType.body.fontWeight),
                    color = c.inkMuted,
                )
            }
        }

        // Internet mode
        val switchLabel = stringResource(R.string.wesh_pave_internet_mode)
        var askDisclosure by rememberSaveable { mutableStateOf(false) }
        var refused by rememberSaveable { mutableStateOf(false) }
        val onOutcome = { outcome: InternetToggleOutcome ->
            askDisclosure = outcome == InternetToggleOutcome.NeedsDisclosure
            refused = outcome == InternetToggleOutcome.Refused
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(WeshSpace.s3),
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface, card)
                .border(WeshStroke.s2, if (enabled) c.internet else c.line, card)
                .padding(WeshSpace.s4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WeshTouch.min)
                    .toggleable(
                        value = enabled,
                        enabled = internet.available,
                        role = Role.Switch,
                        onValueChange = { onOutcome(toggleInternet(internet, it)) },
                    ),
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = c.internet, modifier = Modifier.size(22.dp))
                Text(switchLabel, style = WeshType.body.copy(fontWeight = WeshType.label.fontWeight), color = c.ink, modifier = Modifier.weight(1f).padding(start = WeshSpace.s3))
                Switch(
                    checked = enabled,
                    onCheckedChange = null,
                    enabled = internet.available,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = c.internet,
                        checkedThumbColor = c.onInternet,
                        uncheckedTrackColor = c.surfaceRaised,
                        uncheckedThumbColor = c.lineStrong,
                        uncheckedBorderColor = c.lineStrong,
                    ),
                )
            }
            Text(
                internetStatusText(internet.available, enabled, status),
                style = WeshType.label,
                color = when {
                    status is PaveInternetStatus.Error -> c.danger
                    enabled -> c.internet
                    else -> c.inkMuted
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (internet.available && enabled && status is PaveInternetStatus.Error) {
                Text(
                    stringResource((status as PaveInternetStatus.Error).reason.recoveryHelp()),
                    style = WeshType.body,
                    color = c.ink,
                )
                Text(
                    stringResource(R.string.wesh_pave_internet_help_preserved),
                    style = WeshType.caption,
                    color = c.inkMuted,
                )
                SecondaryButton(stringResource(R.string.wesh_pave_retry), { internet.retry() })
            }
            if (refused) {
                Text(
                    stringResource(R.string.wesh_pave_internet_refused),
                    style = WeshType.label,
                    color = c.danger,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (askDisclosure) {
                // First activation: the full disclosure, then an explicit choice.
                Text(stringResource(R.string.wesh_pave_internet_confirm_title), style = WeshType.label, color = c.ink)
                Text(stringResource(R.string.wesh_pave_internet_disclosure_full), style = WeshType.body, color = c.ink)
                PrimaryButton(stringResource(R.string.wesh_pave_internet_confirm_action), { onOutcome(confirmInternetDisclosure(internet)) })
                SecondaryButton(stringResource(R.string.wesh_pave_cancel), { askDisclosure = false }, Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.wesh_pave_internet_disclosure), style = WeshType.caption, color = c.inkMuted)
            }
        }

        // Bluetooth visibility notice
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(horizontal = WeshSpace.s1)) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = c.warning, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.wesh_pave_bt_visibility),
                style = WeshType.caption,
                color = c.ink,
                modifier = Modifier.padding(start = WeshSpace.s2),
            )
        }

        SecondaryButton(stringResource(R.string.wesh_pave_close), onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun internetStatusText(available: Boolean, enabled: Boolean, status: PaveInternetStatus): String = when {
    !available -> stringResource(R.string.wesh_pave_internet_unavailable)
    !enabled -> stringResource(R.string.wesh_pave_internet_off)
    else -> when (status) {
        // Indeterminate: Arti reports no real bootstrap progress.
        PaveInternetStatus.Off, is PaveInternetStatus.Connecting -> stringResource(R.string.wesh_pave_internet_connecting)
        is PaveInternetStatus.On -> pluralStringResource(R.plurals.wesh_pave_internet_on, status.relays, status.relays)
        is PaveInternetStatus.Error -> stringResource(
            when (status.reason) {
                PaveInternetError.TorBlocked -> R.string.wesh_pave_internet_error_tor
                PaveInternetError.NoNetwork -> R.string.wesh_pave_internet_error_network
                PaveInternetError.RelaysUnreachable -> R.string.wesh_pave_internet_error_relays
            }
        )
    }
}

@PavePreviews
@Composable
private fun ConnexionOnPreview() = PavePreview {
    Box(Modifier.background(WeshPave.colors.surfaceRaised)) {
        ConnexionSheetContent(PaveBluetoothInfo(true, 7), FakeWeshInternetModeState(enabled = true), {})
    }
}

@PavePreviews
@Composable
private fun ConnexionOffPreview() = PavePreview {
    Box(Modifier.background(WeshPave.colors.surfaceRaised)) {
        ConnexionSheetContent(PaveBluetoothInfo(false, 0), FakeWeshInternetModeState(enabled = false), {})
    }
}

@PavePreviews
@Composable
private fun ConnexionErrorPreview() = PavePreview {
    Box(Modifier.background(WeshPave.colors.surfaceRaised)) {
        ConnexionSheetContent(
            PaveBluetoothInfo(true, 0),
            FakeWeshInternetModeState(enabled = true, status = PaveInternetStatus.Error(PaveInternetError.TorBlocked)),
            {},
        )
    }
}
