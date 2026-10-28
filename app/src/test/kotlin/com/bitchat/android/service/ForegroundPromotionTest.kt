package com.bitchat.android.service

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ForegroundPromotionTest {

    @Test fun `accepted promotion runs once and reports success`() {
        var calls = 0
        assertTrue(ForegroundPromotion.attempt("test") { calls++ })
        assertEquals(1, calls)
    }

    @Test fun `background start refusal is reported instead of crashing`() {
        assertFalse(ForegroundPromotion.attempt("test") {
            throw ForegroundServiceStartNotAllowedException("synthetic background refusal")
        })
    }

    @Test fun `missing connected device prerequisite is reported instead of crashing`() {
        assertFalse(ForegroundPromotion.attempt("test") {
            throw SecurityException("synthetic missing BLUETOOTH_CONNECT")
        })
    }

    @Test fun `older platforms refusing a background start are reported instead of crashing`() {
        assertFalse(ForegroundPromotion.attempt("test") {
            throw IllegalStateException("Not allowed to start service Intent")
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unrelated runtime errors still surface`() {
        ForegroundPromotion.attempt("test") { throw IllegalArgumentException("synthetic bad type") }
    }
}
