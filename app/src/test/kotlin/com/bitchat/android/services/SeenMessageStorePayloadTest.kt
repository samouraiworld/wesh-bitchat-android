package com.bitchat.android.services

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Seen sets survive an update: the JSON written by shipped builds must load unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class SeenMessageStorePayloadTest {
    private fun secureWith(json: String?): SecureIdentityStateManager {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("seen-payload-test", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        return SecureIdentityStateManager(prefs, testOnly = true).also { secure ->
            json?.let { secure.storeSecureValue(STORAGE_KEY, it) }
        }
    }

    private fun load(json: String) =
        SeenMessageStore(secureWith(json), SeenReceiptAdmission(), testOnly = true)

    @Test fun `release builds 108 and 109 keep their delivered set after the update`() {
        // R8 renamed the unannotated field to "a" in both shipped mapping files.
        val seen = load("""{"a":["delivered-1"],"read":["read-1"],"read_receipts_sent":["sent-1"]}""")

        assertTrue(seen.hasDelivered("delivered-1"))
        assertTrue(seen.hasBeenReadLocally("read-1"))
        assertTrue(seen.hasReadReceiptBeenSent("sent-1"))
    }

    @Test fun `unminified builds keep their delivered set after the update`() {
        val seen = load("""{"delivered":["delivered-1"],"read":["read-1"]}""")

        assertTrue(seen.hasDelivered("delivered-1"))
        assertTrue(seen.hasReadReceiptBeenSent("read-1"))
    }

    @Test fun `every persisted field has a name that minification cannot change`() {
        val payload = Class.forName("com.bitchat.android.services.SeenMessageStore\$StorePayload")
        val names = payload.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.getAnnotation(SerializedName::class.java)?.value }

        assertEquals(
            mapOf("delivered" to "delivered", "locallyRead" to "read", "readReceiptsSent" to "read_receipts_sent"),
            names
        )
    }

    private companion object {
        const val STORAGE_KEY = "seen_message_store_v1"
    }
}
