package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetErrorHelpTest {
    @Test
    fun `each connection failure has actionable help in both shipped languages`() {
        val actions = mapOf(
            "values" to mapOf(
                PaveInternetError.NoNetwork to "Wi-Fi or mobile data",
                PaveInternetError.TorBlocked to "try another network",
                PaveInternetError.RelaysUnreachable to "Wait a moment",
            ),
            "values-fr" to mapOf(
                PaveInternetError.NoNetwork to "Wi-Fi ou les données mobiles",
                PaveInternetError.TorBlocked to "essayez un autre réseau",
                PaveInternetError.RelaysUnreachable to "Patientez un instant",
            ),
        )
        for ((locale, reasons) in actions) {
            val strings = PaveStringResources.strings(locale)
            for ((reason, action) in reasons) {
                val help = strings.getValue(PaveStringResources.name(reason.recoveryHelp()))
                assertTrue("$locale $reason", help.contains(action))
                assertTrue("$locale $reason", help.contains(if (locale == "values") "Retry" else "Réessayer"))
            }
            val preservation = strings.getValue("wesh_pave_internet_help_preserved")
            assertTrue(preservation.contains("Bluetooth"))
            assertTrue(preservation.contains(if (locale == "values") "not confirmed" else "ne sont pas confirmés"))
        }
    }

    @Test
    fun `a Tor timeout does not claim proven network censorship`() {
        assertEquals("Tor could not connect.", PaveStringResources.strings("values").getValue("wesh_pave_internet_error_tor"))
        assertEquals("Tor n’a pas réussi à se connecter.", PaveStringResources.strings("values-fr").getValue("wesh_pave_internet_error_tor"))
        for (locale in listOf("values", "values-fr")) {
            val strings = PaveStringResources.strings(locale)
            for (reason in PaveInternetError.entries) {
                val help = strings.getValue(PaveStringResources.name(reason.recoveryHelp()))
                assertFalse(help.contains("%s") || help.contains("%1\$s"))
            }
        }
    }
}
