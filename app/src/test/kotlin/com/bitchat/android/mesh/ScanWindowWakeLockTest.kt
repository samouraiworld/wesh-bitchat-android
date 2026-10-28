package com.bitchat.android.mesh

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ScanWindowWakeLockTest {

    @Test fun `a cancelled window released late cannot release the window that replaced it`() {
        val lock = AndroidScanWindowWakeLock(RuntimeEnvironment.getApplication())
        val old = lock.acquire(6_000L)
        val replacement = lock.acquire(6_000L)
        val replacementLock = ShadowPowerManager.getLatestWakeLock()

        old.release()
        assertTrue(replacementLock.isHeld)

        replacement.release()
        assertFalse(replacementLock.isHeld)
        replacement.release() // A second release of the same window is harmless.
    }

    @Test fun `every window is bounded whatever the schedule`() {
        assertEquals(6_000L, ScanWindowWakeLock.timeoutFor(1_000L))
        assertEquals(13_000L, ScanWindowWakeLock.timeoutFor(8_000L))
        assertEquals(15_000L, ScanWindowWakeLock.timeoutFor(Long.MAX_VALUE - 1))
    }
}
