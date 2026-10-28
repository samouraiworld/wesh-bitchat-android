package com.bitchat.android.ui.pave

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.R
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notices shipped in the APK must cover every bundled library, Arti and its crates included. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ThirdPartyNoticesTest {
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

    private val notices = File("src/main/res/raw/third_party_notices.txt").readText()
    private val lock = File("../tools/arti-build/Cargo.lock")

    @Test fun `notices were generated from the Cargo lock the native library is built from`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(lock.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue("regenerate with tools/third-party-notices/generate_notices.py", notices.contains("Cargo.lock sha256 $digest"))
    }

    @Test fun `every listed Rust component is one of the locked crates and Arti is listed`() {
        val locked = Regex("""name = "([^"]+)"\nversion = "([^"]+)"""").findAll(lock.readText())
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSet()
        val section = notices.substringAfter("== Arti").substringBefore("== Licence texts ==")
        val listed = Regex("""^(\S+ \S+) — """, RegexOption.MULTILINE).findAll(section).map { it.groupValues[1] }.toList()
        assertTrue(listed.size > 300)
        assertEquals(emptyList<String>(), listed.filterNot(locked::contains))
        assertTrue(listed.containsAll(listOf("arti-client 0.38.0", "tor-proto 0.38.0", "ring 0.17.14", "rustls 0.23.46")))
    }

    @Test fun `every library the app is built with is listed`() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        val modules = Regex("""^([\w-]+) = \{ module = "([^"]+)"""", RegexOption.MULTILINE).findAll(catalog)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val bundles = Regex("""^([\w-]+) = \[(.*?)]""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .findAll(catalog.substringAfter("[bundles]"))
            .associate { m -> m.groupValues[1] to Regex("\"([\\w-]+)\"").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList() }
        val build = File("build.gradle.kts").readText()
        val aliases = Regex("""^\s*implementation\(libs\.([\w.]+)\)""", RegexOption.MULTILINE).findAll(build)
            .map { it.groupValues[1] }
            .flatMap { alias ->
                if (alias.startsWith("bundles.")) bundles.getValue(alias.removePrefix("bundles.").replace('.', '-'))
                else listOf(alias.replace('.', '-'))
            }.toList()
        assertTrue(aliases.size > 15)
        val missing = aliases.map(modules::getValue).filterNot { notices.contains("$it ") }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `fonts and the licence texts are shipped, not only their names`() {
        assertTrue(notices.contains("Archivo — SIL Open Font License 1.1"))
        assertTrue(notices.contains("Geist Mono — SIL Open Font License 1.1"))
        assertTrue(notices.contains("Apache License\n                           Version 2.0, January 2004"))
        assertTrue(notices.contains("Permission is hereby granted, free of charge"))
    }

    @Test fun `the licences screen opens the full notices in both shipped languages`() {
        assertEquals("Read the full notices", PaveStringResources.strings("values").getValue("wesh_pave_licenses_notices"))
        assertEquals("Lire les mentions complètes", PaveStringResources.strings("values-fr").getValue("wesh_pave_licenses_notices"))
        var opened = 0
        setContent { LicensesScreen(onBack = {}, onOpenNotices = { opened++ }) }
        compose.onNodeWithText("label-${R.string.wesh_pave_licenses_notices}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun `the full notices screen shows the shipped file`() {
        setContent { ThirdPartyNoticesScreen(onBack = {}) }
        compose.onNodeWithText("Wesh — third-party notices", substring = true).assertExists()
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            val shipped = ShippedResources(LocalContext.current)
            CompositionLocalProvider(LocalContext provides shipped, LocalResources provides shipped.resources) {
                WeshPaveTheme { content() }
            }
        }
    }

    /** Labels strings by id, as the other Pavé screen tests do, and serves the shipped notices file. */
    private class ShippedResources(base: Context) : ContextWrapper(base) {
        @Suppress("DEPRECATION")
        private val labels = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "label-$id"
            override fun getString(id: Int): String = "label-$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String =
                (listOf("label-$id") + formatArgs).joinToString(" ")
            override fun openRawResource(id: Int): InputStream {
                check(id == R.raw.third_party_notices)
                return File("src/main/res/raw/third_party_notices.txt").inputStream()
            }
        }

        override fun getResources(): Resources = labels
    }
}
