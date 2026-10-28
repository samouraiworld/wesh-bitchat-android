package com.bitchat.android.service

import android.util.Log

/**
 * Android may refuse a foreground-service start or promotion: from the background on Android 12+
 * (ForegroundServiceStartNotAllowedException, an IllegalStateException), when the connectedDevice
 * prerequisites are missing on Android 14+ (SecurityException), or for a background start on
 * Android 8+ (IllegalStateException). A refusal is reported to the caller and never crashes Wesh.
 */
internal object ForegroundPromotion {
    fun attempt(tag: String, start: () -> Unit): Boolean = try {
        start()
        true
    } catch (e: IllegalStateException) {
        Log.w(tag, "Foreground service start refused (${e.javaClass.simpleName})")
        false
    } catch (e: SecurityException) {
        Log.w(tag, "Foreground service start refused (${e.javaClass.simpleName})")
        false
    }
}
