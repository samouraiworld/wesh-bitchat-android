package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/** Frame shared by the Settings sub-screens: header with back, then scrolling content. */
@Composable
private fun SettingsDetailFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val c = WeshPave.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .paveGrain(c.isNight)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        PaveHeader(title, onBack = onBack)
        content()
    }
}

@Composable
private fun PaveTextArea(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean, enabled: Boolean = true) {
    val c = WeshPave.colors
    val shape = RoundedCornerShape(WeshRadius.control)
    Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s2)) {
        Text(label, style = WeshType.label, color = c.ink)
        NoPersonalizedLearning {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 5,
                textStyle = WeshType.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(capitalization = if (singleLine) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WeshTouch.primary)
                    .background(c.surface, shape)
                    .border(WeshStroke.s2, c.lineStrong, shape)
                    .padding(WeshSpace.s4)
                    .semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun Feedback(text: String?, error: Boolean) {
    if (text == null) return
    val c = WeshPave.colors
    Text(
        text,
        style = WeshType.body,
        color = if (error) c.danger else c.ink,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Profil: your own nickname. It proves nothing, and the help line says so. */
@Composable
fun ProfileScreen(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    myFingerprint: String?,
    onSave: () -> Unit,
    feedback: String?,
    feedbackIsError: Boolean,
    onBack: () -> Unit,
) {
    val c = WeshPave.colors
    SettingsDetailFrame(stringResource(R.string.wesh_pave_settings_profile), onBack) {
        PaveTextArea(nickname, onNicknameChange, stringResource(R.string.wesh_pave_nickname_label), singleLine = true)
        Text(stringResource(R.string.wesh_pave_nickname_help), style = WeshType.caption, color = c.inkMuted)
        Column(verticalArrangement = Arrangement.spacedBy(WeshSpace.s1)) {
            Text(stringResource(R.string.wesh_pave_fingerprint_mine), style = WeshType.label, color = c.ink)
            if (myFingerprint != null) {
                val spoken = stringResource(R.string.wesh_pave_fingerprint_label) + " : " + PaveFingerprint.spoken(myFingerprint)
                Text(myFingerprint, style = WeshType.fingerprint, color = c.ink, modifier = Modifier.semantics { contentDescription = spoken })
            } else {
                Text(stringResource(R.string.wesh_pave_fingerprint_unavailable), style = WeshType.caption, color = c.warning)
            }
        }
        Feedback(feedback, feedbackIsError)
        PrimaryButton(stringResource(R.string.wesh_pave_fluo_save), onSave)
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = WeshPave.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WeshTouch.primary)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(label, style = WeshType.body, color = c.ink, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

/** Discrétion: notifications never carry message text; these switches decide the rest. */
@Composable
fun DiscretionScreen(
    publicMuted: Boolean,
    onPublicMuted: (Boolean) -> Unit,
    showSender: Boolean,
    onShowSender: (Boolean) -> Unit,
    onBack: () -> Unit,
    readReceipts: Boolean = true,
    onReadReceipts: (Boolean) -> Unit = {},
) {
    val c = WeshPave.colors
    SettingsDetailFrame(stringResource(R.string.wesh_pave_settings_discretion), onBack) {
        SwitchRow(stringResource(R.string.wesh_pave_fluo_mute_public), publicMuted, onPublicMuted)
        HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        SwitchRow(stringResource(R.string.wesh_pave_fluo_show_sender), showSender, onShowSender)
        Text(stringResource(R.string.wesh_pave_fluo_notifications_body), style = WeshType.caption, color = c.inkMuted)
        HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        SwitchRow(stringResource(R.string.wesh_pave_fluo_read_receipts), readReceipts, onReadReceipts)
    }
}

/** Rendez-vous: a personal note kept on this phone, never sent. */
@Composable
fun MeetingNoteScreen(
    note: String,
    onNoteChange: (String) -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
    feedback: String?,
    feedbackIsError: Boolean,
    onBack: () -> Unit,
    ready: Boolean = true,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val c = WeshPave.colors
    SettingsDetailFrame(stringResource(R.string.wesh_pave_settings_meeting), onBack) {
        PaveTextArea(note, onNoteChange, stringResource(R.string.wesh_pave_fluo_meeting_label), singleLine = false, enabled = ready)
        if (loading) Text(stringResource(R.string.wesh_pave_fluo_meeting_loading), style = WeshType.caption, color = c.inkMuted)
        if (!ready && !loading) Text(stringResource(R.string.wesh_pave_fluo_meeting_unavailable), style = WeshType.caption, color = c.warning)
        onRetry?.let { SecondaryButton(stringResource(R.string.wesh_pave_fluo_meeting_retry), it) }
        Text(stringResource(R.string.wesh_pave_fluo_meeting_body), style = WeshType.caption, color = c.inkMuted)
        Feedback(feedback, feedbackIsError)
        PrimaryButton(stringResource(R.string.wesh_pave_fluo_save), onSave, enabled = ready && !saving)
    }
}

/** Someone blocked on this phone: a contact by fingerprint, or a sender hidden for this session. */
@Immutable
data class PaveBlockedItem(val id: String, val nickname: String, val fingerprint: String?, val sessionOnly: Boolean)

@Composable
fun BlockedScreen(items: List<PaveBlockedItem>, onUnblock: (PaveBlockedItem) -> Unit, onBack: () -> Unit) {
    val c = WeshPave.colors
    SettingsDetailFrame(stringResource(R.string.wesh_pave_settings_blocked), onBack) {
        if (items.isEmpty()) Text(stringResource(R.string.wesh_pave_fluo_blocked_empty), style = WeshType.body, color = c.inkMuted)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WeshSpace.s1)) {
                    Text(remoteDisplayName(item.nickname), style = WeshType.headline, color = c.ink)
                    if (item.fingerprint != null) {
                        val spoken = stringResource(R.string.wesh_pave_fingerprint_label) + " : " + PaveFingerprint.spoken(item.fingerprint)
                        Text(item.fingerprint, style = WeshType.fingerprint, color = c.inkMuted, modifier = Modifier.semantics { contentDescription = spoken })
                    } else {
                        Text(stringResource(R.string.wesh_pave_fingerprint_unavailable), style = WeshType.caption, color = c.warning)
                    }
                    if (item.sessionOnly) Text(stringResource(R.string.wesh_pave_fluo_blocked_session), style = WeshType.caption, color = c.inkMuted)
                }
                SecondaryButton(stringResource(R.string.wesh_pave_fluo_unblock), { onUnblock(item) })
            }
            HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        }
    }
}

/** Apparence: Nuit (default, OLED), Jour (full sun) or follow the system. */
@Composable
fun AppearanceScreen(current: WeshAppearance, onPick: (WeshAppearance) -> Unit, onBack: () -> Unit) {
    val c = WeshPave.colors
    SettingsDetailFrame(stringResource(R.string.wesh_pave_settings_appearance), onBack) {
        WeshAppearance.entries.forEach { option ->
            val selected = option == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WeshTouch.primary)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(option) }),
            ) {
                Text(option.label(), style = WeshType.body, color = c.ink, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(24.dp)
                        .border(WeshStroke.s2, if (selected) c.ink else c.lineStrong, CircleShape)
                        .padding(5.dp)
                        .background(if (selected) c.ink else c.bg, CircleShape),
                )
            }
            HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        }
    }
}

@PaveScreenPreviews
@androidx.compose.runtime.Composable
private fun BlockedScreenPreview() = PavePreview(padded = false) {
    BlockedScreen(listOf(PaveBlockedItem("a", "Léo", "3C81 E0F2 9A47 15BD", false)), {}, {})
}
