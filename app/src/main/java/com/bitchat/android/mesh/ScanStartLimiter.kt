package com.bitchat.android.mesh

/**
 * Android refuses an app's sixth BLE scan start within 30 seconds, often without calling
 * onScanFailed: the scan then simply finds nobody. Wesh admits at most four starts per 30 seconds,
 * one below Android's limit, and keeps at least five seconds between two starts.
 */
internal class ScanStartLimiter(
    private val maxStarts: Int = 4,
    private val windowMs: Long = 30_000L,
    private val minIntervalMs: Long = 5_000L
) {
    private val starts = ArrayDeque<Long>()

    /** Milliseconds to wait at monotonic time [now] before a start is admitted; 0 to start now. */
    @Synchronized
    fun delayBeforeStart(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        var wait = starts.lastOrNull()?.let { minIntervalMs - (now - it) } ?: 0L
        if (starts.size >= maxStarts) wait = maxOf(wait, windowMs - (now - starts.first()))
        return wait.coerceAtLeast(0L)
    }

    @Synchronized
    fun recordStart(now: Long) {
        starts.addLast(now)
        while (starts.size > maxStarts) starts.removeFirst()
    }
}
