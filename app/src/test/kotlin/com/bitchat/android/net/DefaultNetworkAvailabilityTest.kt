package com.bitchat.android.net

import android.app.Application
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class DefaultNetworkAvailabilityTest {
    @Test
    fun `an Internet route remains a candidate before Android validation`() {
        val capabilities = mock<NetworkCapabilities>()
        whenever(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(false)
        val noInternet = mock<NetworkCapabilities>()
        whenever(noInternet.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)

        assertTrue(hasInternetRoute(capabilities))
        assertFalse(hasInternetRoute(noInternet))
        assertFalse(hasInternetRoute(null))
    }

    @Test
    fun `capability loss on the current default is published immediately`() {
        val availability = DefaultNetworkAvailability(initialDefault = "wifi", initiallyAvailable = true)

        assertEquals(false, availability.onCapabilitiesChanged("wifi", available = false))
        assertEquals(null, availability.onCapabilitiesChanged("mobile", available = true))
    }

    @Test
    fun `losing the previous default cannot hide the new mobile route`() {
        val availability = DefaultNetworkAvailability(initialDefault = "wifi", initiallyAvailable = true)

        availability.onAvailable("mobile")
        assertEquals(true, availability.onCapabilitiesChanged("mobile", available = true))
        assertEquals(null, availability.onLost("wifi"))
        assertEquals(false, availability.onLost("mobile"))
    }

    @Test
    fun `a new default waits for its ordered capabilities instead of inheriting wifi`() {
        val availability = DefaultNetworkAvailability(initialDefault = "wifi", initiallyAvailable = true)

        availability.onAvailable("mobile")

        assertEquals(null, availability.onCapabilitiesChanged("wifi", available = false))
        assertEquals(false, availability.onCapabilitiesChanged("mobile", available = false))
    }
}
