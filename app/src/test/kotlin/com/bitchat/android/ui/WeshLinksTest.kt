package com.bitchat.android.ui

import com.bitchat.android.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeshLinksTest {
    @Test
    fun `internal candidate keeps version 1 0 0 and advances to code 105`() {
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
        assertEquals(105, BuildConfig.VERSION_CODE)
    }

    @Test
    fun `terms URL is defined once and used by the settings screen`() {
        assertEquals("https://wesh.samourai.app/conditions", WeshLinks.TERMS_URL)
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val definitions = sources.filter { it.readText().contains("wesh.samourai.app") }.map { it.name }
        assertEquals(listOf("WeshLinks.kt"), definitions)
        // The Pavé About screen (reached from Settings) and the welcome both link the terms.
        assertTrue(File("src/main/java/com/bitchat/android/ui/pave/SettingsScreen.kt").readText().contains("WeshLinks.TERMS_URL"))
        assertTrue(File("src/main/java/com/bitchat/android/ui/pave/OnboardingFlow.kt").readText().contains("WeshLinks.TERMS_URL"))
    }

    @Test
    fun `privacy policy uses the French page only for French locales`() {
        assertEquals("https://wesh.samourai.app/confidentialite", WeshLinks.PRIVACY_URL_FR)
        assertEquals("https://wesh.samourai.app/en/privacy", WeshLinks.PRIVACY_URL_EN)
        assertEquals(WeshLinks.PRIVACY_URL_FR, WeshLinks.privacyUrl("fr"))
        assertEquals(WeshLinks.PRIVACY_URL_FR, WeshLinks.privacyUrl("fr-FR"))
        assertEquals(WeshLinks.PRIVACY_URL_EN, WeshLinks.privacyUrl("en"))
        assertEquals(WeshLinks.PRIVACY_URL_EN, WeshLinks.privacyUrl("de-DE"))

        val settings = File("src/main/java/com/bitchat/android/ui/pave/SettingsScreen.kt").readText()
        assertTrue(settings.contains("WeshLinks.privacyUrl"))
        assertTrue(settings.contains("R.string.wesh_pave_about_privacy"))
    }

    @Test
    fun `the report address is defined once`() {
        assertEquals("support@samourai.coop", WeshLinks.REPORT_EMAIL)
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals(listOf("WeshLinks.kt"), sources.filter { it.readText().contains("samourai.coop") }.map { it.name })
    }
}
