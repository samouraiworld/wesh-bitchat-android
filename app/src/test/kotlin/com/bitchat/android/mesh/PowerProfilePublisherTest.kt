package com.bitchat.android.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PowerProfilePublisherTest {

    @Test fun `a slower stale computation cannot overwrite a newer profile`() {
        // Battery and lifecycle inputs change on the main thread, direct peers on a background
        // dispatcher. Each change recomputes the profile from all inputs.
        val background = AtomicBoolean(false)
        val flow = MutableStateFlow("foreground")
        val publisher = PowerProfilePublisher(flow)
        val staleRead = CountDownLatch(1)
        val newerDone = CountDownLatch(1)

        val slow = thread(name = "synthetic-main-refresh") {
            publisher.publish {
                val snapshot = if (background.get()) "background" else "foreground"
                staleRead.countDown()
                // Give the newer refresh every chance to finish first if nothing serialises them.
                newerDone.await(300, TimeUnit.MILLISECONDS)
                snapshot
            }
        }
        assertTrue(staleRead.await(5, TimeUnit.SECONDS))
        background.set(true)
        val fast = thread(name = "synthetic-peer-refresh") {
            publisher.publish { if (background.get()) "background" else "foreground" }
            newerDone.countDown()
        }
        slow.join(5_000)
        fast.join(5_000)
        assertFalse(slow.isAlive || fast.isAlive)

        assertEquals("background", flow.value)
    }

    @Test fun `publish reports only real changes`() {
        val flow = MutableStateFlow(1)
        val publisher = PowerProfilePublisher(flow)
        assertEquals(null, publisher.publish { 1 })
        assertEquals(2, publisher.publish { 2 })
        assertEquals(2, flow.value)
    }
}
