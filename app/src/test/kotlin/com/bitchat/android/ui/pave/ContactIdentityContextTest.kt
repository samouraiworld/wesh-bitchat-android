package com.bitchat.android.ui.pave

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.R
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ContactIdentityContextTest {
    private val compose = createComposeRule()
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(
                ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = app.packageName },
            )
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    @Test
    fun `local alias never replaces the QR name or fingerprint on the contact card`() {
        val fingerprint = PaveFingerprint.short(ByteArray(32) { it.toByte() })!!
        showContact("Synthetic QR name", fingerprint)
        compose.onNodeWithText("label-${R.string.wesh_pave_fluo_contact_qr_name} Synthetic QR name").assertExists()
        compose.onNodeWithText("Synthetic local alias").assertExists()
        compose.onNodeWithText(fingerprint).assertExists()
        compose.onNodeWithContentDescription("label-${R.string.wesh_pave_fingerprint_label} : ${PaveFingerprint.spoken(fingerprint)}").assertExists()
    }

    @Test
    fun `missing key cue is explicit instead of displaying an unrelated identity`() {
        showContact("Synthetic QR name", null)
        compose.onNodeWithText("label-${R.string.wesh_pave_fingerprint_unavailable}").assertExists()
    }

    @Test
    fun `non contact conversations do not expose a supplied key cue or note editor`() {
        showContact(null, "0000 1111 2222 3333")
        compose.onNodeWithText("0000 1111 2222 3333").assertDoesNotExist()
        compose.onNodeWithText("Synthetic local alias").assertDoesNotExist()
        compose.onNodeWithText("label-${R.string.wesh_pave_fingerprint_contact}").assertDoesNotExist()
    }

    private fun showContact(qrName: String?, fingerprint: String?) {
        compose.setContent {
            val labels = LabelContext(LocalContext.current)
            CompositionLocalProvider(LocalContext provides labels, LocalResources provides labels.resources) {
                WeshPaveTheme {
                    ContactSettingsScreen(
                        qrName = qrName, contactFingerprint = fingerprint,
                        alias = "Synthetic local alias", onAliasChange = {},
                        memo = "Synthetic memo", onMemoChange = {}, saving = false,
                        onSave = {}, feedback = null, feedbackIsError = false,
                        onBlock = {}, onRemove = {}, onBack = {},
                    )
                }
            }
        }
    }

    private class LabelContext(base: Context) : ContextWrapper(base) {
        @Suppress("DEPRECATION")
        private val labels = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "label-$id"
            override fun getString(id: Int): String = "label-$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String =
                (listOf("label-$id") + formatArgs).joinToString(" ")
        }
        override fun getResources(): Resources = labels
        override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
            LabelContext(baseContext.createConfigurationContext(overrideConfiguration))
    }
}
