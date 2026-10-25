package com.bitchat.android.ui.pave

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.R
import com.bitchat.android.ui.ScanTabContent
import org.junit.Assert.assertEquals
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
class CameraUnavailableScreenTest {
    private val compose = createComposeRule()
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).apply {
                setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
                addOrUpdateActivity(
                    ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = app.packageName },
                )
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    @Test
    fun `camera-less exchange keeps own QR and never offers permissions or settings or completes verification`() {
        var scanned = 0
        var completed = 0
        compose.setContent {
            val context = LabelContext(LocalContext.current)
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources) {
                WeshPaveTheme {
                    ExchangeScreen(
                        myQrPayload = "synthetic-qr", myNickname = "Synthetic", myFingerprint = "0000 1111 2222 3333",
                        renewsInMinutes = 5, progress = ExchangeProgress(theyScannedMe = true),
                        contactNickname = null, contactFingerprint = null,
                        onScanned = { scanned++ }, onCompleted = { completed++ }, onClose = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(label(R.string.wesh_pave_exchange_my_code)).assertExists()
        compose.onNodeWithText(label(R.string.wesh_camera_unavailable)).performScrollTo().assertExists()
        compose.onNodeWithText(label(R.string.wesh_pave_camera_allow)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.wesh_pave_open_settings)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.wesh_pave_exchange_open_chat)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.wesh_pave_exchange_i_pending)).assertExists()
        compose.runOnIdle { assertEquals(0, scanned); assertEquals(0, completed) }
    }

    @Test
    fun `legacy scanner also explains missing hardware without a permission or settings loop`() {
        var scanned = 0
        compose.setContent {
            val context = LabelContext(LocalContext.current)
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources) {
                WeshPaveTheme { ScanTabContent(accent = WeshPave.colors.ink, onScan = { scanned++ }) }
            }
        }

        compose.onNodeWithText(label(R.string.wesh_camera_unavailable)).assertExists()
        compose.onNodeWithText(label(R.string.wesh_camera_unavailable_body)).assertExists()
        compose.onNodeWithText(label(R.string.verify_request_camera)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.wesh_verify_app_settings)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, scanned) }
    }

    private fun label(id: Int) = "label-$id"

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
