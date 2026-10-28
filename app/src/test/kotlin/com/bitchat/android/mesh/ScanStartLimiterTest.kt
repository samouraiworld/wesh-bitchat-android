package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStartLimiterTest {
    private val limiter = ScanStartLimiter()

    @Test fun `first start is immediate even at a monotonic time of zero`() {
        assertEquals(0L, limiter.delayBeforeStart(0L))
    }

    @Test fun `starts stay five seconds apart`() {
        limiter.recordStart(1_000L)
        assertEquals(5_000L, limiter.delayBeforeStart(1_000L))
        assertEquals(2_000L, limiter.delayBeforeStart(4_000L))
        assertEquals(0L, limiter.delayBeforeStart(6_000L))
    }

    @Test fun `a fifth start within thirty seconds waits for the oldest to leave the window`() {
        listOf(0L, 5_000L, 10_000L, 15_000L).forEach(limiter::recordStart)
        // Android refuses the sixth start in 30 s; Wesh stops at four to keep one of margin.
        assertEquals(10_000L, limiter.delayBeforeStart(20_000L))
        assertEquals(1_000L, limiter.delayBeforeStart(29_000L))
        assertEquals(0L, limiter.delayBeforeStart(30_000L))
    }

    @Test fun `no thirty second window ever admits more than four starts`() {
        val starts = mutableListOf<Long>()
        var now = 0L
        while (now < 300_000L) {
            if (limiter.delayBeforeStart(now) == 0L) {
                limiter.recordStart(now)
                starts += now
            }
            now += 500L
        }
        starts.forEach { first ->
            assertEquals(true, starts.count { it >= first && it < first + 30_000L } <= 4)
        }
    }
}
