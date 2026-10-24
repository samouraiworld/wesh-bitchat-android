package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.ui.WeshLinks

/** Settings rows, in the order of the approved mockup. */
enum class PaveSettingsEntry { Profile, Connexion, Discretion, Meeting, Blocked, Appearance, About }

data class PaveSettingsSummary(
    val nickname: String,
    val previewsHidden: Boolean,
    val meeting: String?,
    val blockedCount: Int,
    val appearance: WeshAppearance,
)

@Composable
fun WeshAppearance.label(): String = stringResource(
    when (this) {
        WeshAppearance.Nuit -> R.string.wesh_pave_appearance_nuit
        WeshAppearance.Jour -> R.string.wesh_pave_appearance_jour
        WeshAppearance.Automatique -> R.string.wesh_pave_appearance_auto
    }
)

/** Réglages: hold-to-erase first, then the rows, then who publishes Wesh. */
@Composable
fun SettingsScreen(
    summary: PaveSettingsSummary,
    onErase: () -> Unit,
    onOpen: (PaveSettingsEntry) -> Unit,
    autoStartOnBoot: Boolean,
    onAutoStartOnBoot: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = WeshPave.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s4),
    ) {
        PaveHeader(stringResource(R.string.wesh_pave_settings_title), onBack = onBack)
        HoldToErase(
            onErase = onErase,
            compact = true,
            showShortcutHint = true,
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(WeshRadius.control))
                .padding(WeshSpace.s4),
        )
        Column {
            val rows = listOf(
                PaveSettingsEntry.Profile to (R.string.wesh_pave_settings_profile to remoteDisplayName(summary.nickname)),
                PaveSettingsEntry.Connexion to (R.string.wesh_pave_settings_connexion to stringResource(R.string.wesh_pave_settings_connexion_value)),
                PaveSettingsEntry.Discretion to (R.string.wesh_pave_settings_discretion to stringResource(
                    if (summary.previewsHidden) R.string.wesh_pave_settings_previews_hidden else R.string.wesh_pave_settings_previews_shown
                )),
                PaveSettingsEntry.Meeting to (R.string.wesh_pave_settings_meeting to (summary.meeting ?: stringResource(R.string.wesh_pave_settings_meeting_none))),
                PaveSettingsEntry.Blocked to (R.string.wesh_pave_settings_blocked to summary.blockedCount.toString()),
                PaveSettingsEntry.Appearance to (R.string.wesh_pave_settings_appearance to summary.appearance.label()),
                PaveSettingsEntry.About to (R.string.wesh_pave_settings_about to stringResource(R.string.wesh_pave_settings_about_value)),
            )
            rows.forEachIndexed { i, (entry, content) ->
                if (i > 0) HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
                SettingsRow(stringResource(content.first), content.second, onClick = { onOpen(entry) })
            }
        }
        Column {
            SwitchRow(stringResource(R.string.wesh_pave_settings_boot), autoStartOnBoot, onAutoStartOnBoot)
            Text(stringResource(R.string.wesh_pave_settings_boot_body), style = WeshType.caption, color = c.inkMuted)
        }
        Spacer(Modifier.height(WeshSpace.s4))
        Text(stringResource(R.string.wesh_pave_settings_footer), style = WeshType.caption, color = c.inkMuted)
    }
}

/** One navigable row: title on the left, current value and a chevron on the right. */
@Composable
fun SettingsRow(title: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier, external: Boolean = false) {
    val c = WeshPave.colors
    val opensBrowser = stringResource(R.string.wesh_pave_opens_browser)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WeshTouch.primary)
            .paveClickable(RoundedCornerShape(WeshRadius.control), if (external) opensBrowser else null, Role.Button, onClick = onClick)
            .padding(horizontal = WeshSpace.s1, vertical = WeshSpace.s2),
    ) {
        Text(title, style = WeshType.body, color = c.ink, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = WeshType.label, color = c.inkMuted, textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.weight(1f, fill = false).padding(start = WeshSpace.s3))
        }
        Icon(
            if (external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = c.inkMuted,
            modifier = Modifier.padding(start = WeshSpace.s1).size(22.dp),
        )
    }
}

/** À propos: source code, licenses, donations, privacy policy, terms. */
@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit, onOpenLicenses: () -> Unit, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    val uri = LocalUriHandler.current
    val privacyUrl = WeshLinks.privacyUrl(LocalConfiguration.current.locales[0].language)
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s3),
    ) {
        PaveHeader(stringResource(R.string.wesh_pave_settings_about), onBack = onBack)
        Text(stringResource(R.string.app_name).uppercase(), style = WeshType.wordmark, color = c.ink)
        Text(stringResource(R.string.wesh_pave_about_version, versionName), style = WeshType.label, color = c.inkMuted)
        Text(stringResource(R.string.wesh_pave_settings_footer), style = WeshType.body, color = c.ink)
        Spacer(Modifier.height(WeshSpace.s2))
        SettingsRow(stringResource(R.string.wesh_pave_about_source), null, { uri.openUri(WeshLinks.SOURCE_URL) }, external = true)
        HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        SettingsRow(stringResource(R.string.wesh_pave_about_licenses), null, onOpenLicenses)
        HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        SettingsRow(stringResource(R.string.wesh_pave_about_donate), null, { uri.openUri(WeshLinks.DONATIONS_URL) }, external = true)
        HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        SettingsRow(stringResource(R.string.wesh_pave_about_privacy), null, { uri.openUri(privacyUrl) }, external = true)
        HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        SettingsRow(stringResource(R.string.wesh_pave_about_terms), null, { uri.openUri(WeshLinks.TERMS_URL) }, external = true)
    }
}

/** Third-party notices shipped with Wesh. Names and license identifiers stay untranslated. */
internal val WeshNotices = listOf(
    "bitchat-android" to "GPL-3.0",
    "Archivo" to "OFL-1.1",
    "AndroidX, Jetpack Compose, CameraX" to "Apache-2.0",
    "Kotlin, kotlinx.coroutines" to "Apache-2.0",
    "Accompanist" to "Apache-2.0",
    "ZXing" to "Apache-2.0",
    "Bouncy Castle" to "MIT",
    "Tink" to "Apache-2.0",
    "Gson" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "Nordic Android BLE Library" to "BSD-3-Clause",
    "Arti" to "MIT OR Apache-2.0",
    "Geist Mono" to "OFL-1.1",
)

/** Licences: Wesh is GPLv3; then the components it is built on. */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = WeshPave.colors
    val uri = LocalUriHandler.current
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WeshSpace.gutter, vertical = WeshSpace.s2),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s2),
    ) {
        PaveHeader(stringResource(R.string.wesh_pave_licenses_title), onBack = onBack)
        Text(stringResource(R.string.wesh_pave_licenses_intro), style = WeshType.body, color = c.ink)
        SettingsRow(stringResource(R.string.wesh_pave_licenses_full), null, { uri.openUri(WeshLinks.GPL3_URL) }, external = true)
        Text(
            stringResource(R.string.wesh_pave_licenses_components),
            style = WeshType.headline,
            color = c.ink,
            modifier = Modifier.padding(top = WeshSpace.s4).semantics { heading() },
        )
        WeshNotices.forEach { (name, license) ->
            Row(Modifier.fillMaxWidth().heightIn(min = WeshTouch.min).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = WeshType.body, color = c.ink, modifier = Modifier.weight(1f))
                Text(license, style = WeshType.label, color = c.inkMuted)
            }
            HorizontalDivider(thickness = WeshStroke.s1, color = c.line)
        }
    }
}

@PaveScreenPreviews
@Composable
private fun SettingsScreenPreview() = PavePreview(padded = false) {
    SettingsScreen(
        PaveSettingsSummary("Renard 42", previewsHidden = true, meeting = "Fontaine, 18 h", blockedCount = 2, appearance = WeshAppearance.Nuit),
        onErase = {}, onOpen = {}, autoStartOnBoot = true, onAutoStartOnBoot = {}, onBack = {},
    )
}

@PaveScreenPreviews
@Composable
private fun AboutScreenPreview() = PavePreview(padded = false) { AboutScreen("0.1.0", {}, {}) }

@PaveScreenPreviews
@Composable
private fun LicensesScreenPreview() = PavePreview(padded = false) { LicensesScreen({}) }
