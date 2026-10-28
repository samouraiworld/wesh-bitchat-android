package com.bitchat.android.ui

/** Public Wesh pages and the report address used by the app. Keep every URL and address here. */
internal object WeshLinks {
    const val TERMS_URL = "https://wesh.samourai.app/conditions"
    const val PRIVACY_URL_FR = "https://wesh.samourai.app/confidentialite"
    const val PRIVACY_URL_EN = "https://wesh.samourai.app/en/privacy"
    const val SOURCE_URL = "https://github.com/samouraiworld/wesh-bitchat-android"
    const val GPL3_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    fun privacyUrl(languageTag: String): String =
        if (languageTag.substringBefore('-').substringBefore('_').equals("fr", ignoreCase = true)) {
            PRIVACY_URL_FR
        } else {
            PRIVACY_URL_EN
        }

    /** Reports the user chooses to send reach the publisher here, by an e-mail they write and send. */
    const val REPORT_EMAIL = "support@samourai.coop"
}
