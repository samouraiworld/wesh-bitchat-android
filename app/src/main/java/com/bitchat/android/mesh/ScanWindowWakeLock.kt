package com.bitchat.android.mesh

import android.content.Context
import android.util.Log

/**
 * Keeps the CPU awake for one bounded duty-cycle scan window. Without it a phone with the screen
 * off can suspend inside the window, so the scan stops late (radio on longer) or the next window
 * starts late. Every acquisition carries a timeout, so a stalled job cannot hold the CPU.
 *
 * Each window gets its own lock: a replaced schedule whose cancelled job releases late releases
 * only its own window, never the one that replaced it.
 */
interface ScanWindowWakeLock {
    fun acquire(timeoutMs: Long): Held

    /** One window's hold. Releasing it twice is harmless. */
    fun interface Held {
        fun release()
    }

    companion object {
        /** Margin after the window for the stop to run; the scan window itself stays unchanged. */
        const val MARGIN_MS = 5_000L
        /** No acquisition may exceed this, whatever the schedule. */
        const val MAX_MS = 15_000L

        fun timeoutFor(scanOnMs: Long): Long =
            if (scanOnMs >= MAX_MS - MARGIN_MS) MAX_MS else (scanOnMs + MARGIN_MS).coerceAtLeast(MARGIN_MS)
    }
}

internal class AndroidScanWindowWakeLock(private val context: Context) : ScanWindowWakeLock {
    override fun acquire(timeoutMs: Long): ScanWindowWakeLock.Held {
        val lock = try {
            (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "wesh:ble-scan-window")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(timeoutMs)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Scan window wake lock unavailable (${e.javaClass.simpleName})")
            null
        }
        return ScanWindowWakeLock.Held {
            try { lock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        }
    }

    private companion object { const val TAG = "ScanWindowWakeLock" }
}
