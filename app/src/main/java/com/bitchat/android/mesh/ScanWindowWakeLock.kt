package com.bitchat.android.mesh

import android.content.Context
import android.util.Log

/**
 * Keeps the CPU awake for one bounded duty-cycle scan window. Without it a phone with the screen
 * off can suspend inside the window, so the scan stops late (radio on longer) or the next window
 * starts late. Every acquisition carries a timeout, so a stalled job cannot hold the CPU.
 */
interface ScanWindowWakeLock {
    fun acquire(timeoutMs: Long)
    fun release()

    companion object {
        /** Margin after the window for the stop to run; the scan window itself stays unchanged. */
        const val MARGIN_MS = 5_000L
        /** No acquisition may exceed this, whatever the schedule. */
        const val MAX_MS = 15_000L

        fun timeoutFor(scanOnMs: Long): Long = (scanOnMs + MARGIN_MS).coerceIn(MARGIN_MS, MAX_MS)
    }
}

internal class AndroidScanWindowWakeLock(private val context: Context) : ScanWindowWakeLock {
    private val lock: android.os.PowerManager.WakeLock? by lazy {
        try {
            (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "wesh:ble-scan-window")
                ?.apply { setReferenceCounted(false) }
        } catch (e: Exception) {
            Log.w(TAG, "Scan window wake lock unavailable (${e.javaClass.simpleName})")
            null
        }
    }

    override fun acquire(timeoutMs: Long) {
        try { lock?.acquire(timeoutMs) } catch (_: Exception) { }
    }

    override fun release() {
        try { lock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
    }

    private companion object { const val TAG = "ScanWindowWakeLock" }
}
