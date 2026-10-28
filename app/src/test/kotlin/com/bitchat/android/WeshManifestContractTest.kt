package com.bitchat.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play policy surface of the Wesh manifest; the merged release manifest is checked in CI review. */
class WeshManifestContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun declarations(permission: String): List<String> =
        Regex("""<uses-permission[^>]*android:name="android\.permission\.$permission"[^>]*/>""").findAll(manifest).map { it.value }.toList()

    @Test
    fun `unused sensitive permissions are only declared for removal`() {
        listOf(
            "ACCESS_BACKGROUND_LOCATION", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
            "RECORD_AUDIO", "NEARBY_WIFI_DEVICES", "ACCESS_LOCAL_NETWORK",
            "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "FOREGROUND_SERVICE_LOCATION", "FOREGROUND_SERVICE_DATA_SYNC"
        ).forEach { permission ->
            val found = declarations(permission)
            assertEquals("$permission must be declared once, for removal", 1, found.size)
            assertTrue("$permission must use tools:node=remove", found.single().contains("""tools:node="remove""""))
        }
    }

    @Test
    fun `no media sharing surface ships`() {
        assertFalse(manifest.contains("FileProvider"))
        assertFalse(manifest.contains("<provider"))
        assertFalse(manifest.contains("FILE_PROVIDER_PATHS"))
        assertFalse(File("src/main/res/xml/file_paths.xml").exists())
        // Storage and media permissions may appear only as tools:node="remove" guards.
        val storage = Regex("""<uses-permission[^>]*android:name="android\.permission\.(READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|READ_MEDIA_[A-Z_]+)"[^>]*/>""")
            .findAll(manifest).map { it.value }.toList()
        assertTrue(storage.isNotEmpty())
        storage.forEach { assertTrue("$it must use tools:node=remove", it.contains("""tools:node="remove"""")) }
        // QR scanning keeps the camera, as an optional feature.
        assertEquals(1, declarations("CAMERA").size)
        assertTrue(manifest.contains("""<uses-feature android:name="android.hardware.camera" android:required="false" />"""))
    }

    @Test
    fun `internet stays for the planned Tor mode and location stays limited to legacy Bluetooth`() {
        assertEquals(1, declarations("INTERNET").size)
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION").forEach {
            assertTrue(declarations(it).single().contains("""android:maxSdkVersion="30""""))
        }
        assertTrue(declarations("BLUETOOTH_SCAN").single().contains("neverForLocation"))
    }

    @Test
    fun `wifi aware is not in the build and declares no manifest surface`() {
        // The Wi-Fi permissions above stay removed: the transport and its fixed passphrase are gone.
        assertFalse(File("src/main/java/com/bitchat/android/wifi-aware").exists())
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
        assertTrue(sources.none { it.readText().let { text -> "WifiAwareManager" in text || "bitchat_secret" in text } })
        assertFalse(manifest.contains("android.hardware.wifi.aware"))
        assertFalse(manifest.contains("wifiaware"))
        // The Internet mode watches the default network: ACCESS_NETWORK_STATE stays, once, not removed.
        assertFalse(declarations("ACCESS_NETWORK_STATE").single().contains("tools:node"))
    }

    @Test
    fun `only the wesh URL scheme is registered with the OS`() {
        listOf(manifest, File("src/debug/AndroidManifest.xml").readText()).forEach { source ->
            val schemes = Regex("""android:scheme="([^"]*)"""").findAll(source).map { it.groupValues[1] }.toList()
            assertTrue("unexpected URL schemes $schemes", schemes.all { it == "wesh" })
            assertFalse(source.contains("""android:scheme="bitchat""""))
        }
    }

    @Test
    fun `mesh service is a connected device service only and no APK sharing surface ships`() {
        assertTrue(manifest.contains("""android:foregroundServiceType="connectedDevice""""))
        assertFalse(manifest.contains("dataSync"))
        assertFalse(manifest.contains("HotspotActivity"))
        assertFalse(manifest.contains("SystemForegroundService"))
        assertFalse(manifest.contains("BitChat"))
    }

    @Test
    fun `the microphone can never be requested and no audio capture code ships`() {
        // RECORD_AUDIO and MODIFY_AUDIO_SETTINGS may appear only as tools:node="remove" guards.
        val audio = Regex("""<uses-permission(?:-sdk-23)?[^>]*android:name="android\.permission\.(RECORD_AUDIO|MODIFY_AUDIO_SETTINGS)"[^>]*/>""")
            .findAll(manifest).map { it.value }.toList()
        assertEquals(1, declarations("RECORD_AUDIO").size)
        audio.forEach { assertTrue("$it must use tools:node=remove", it.contains("""tools:node="remove"""")) }
        val debugManifest = File("src/debug/AndroidManifest.xml").readText()
        assertFalse(debugManifest.contains("RECORD_AUDIO") || debugManifest.contains("MODIFY_AUDIO_SETTINGS"))
        assertFalse(File("src/main/java/com/bitchat/android/features/voice").exists())
        val sources = listOf("src/main/java", "src/debug/java")
            .flatMap { root -> File(root).walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList() }
        assertTrue(sources.isNotEmpty())
        listOf("permission.RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS", "MediaRecorder", "AudioRecord").forEach { needle ->
            val offenders = sources.filter { it.readText().contains(needle) }.map { it.name }
            assertTrue("$needle still referenced by $offenders", offenders.isEmpty())
        }
    }

    @Test
    fun `no location SDK or location feature ships and background location is never requested`() {
        assertFalse(File("build.gradle.kts").readText().contains("gms"))
        assertFalse(File("gradle.lockfile").readText().contains("com.google.android.gms:"))
        assertFalse(File("src/main/java/com/bitchat/android/geohash").exists())
        assertFalse(manifest.contains("GeohashPickerActivity"))
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        listOf(
            "com.google.android.gms", "android.location.Geocoder", "requestLocationUpdates",
            "getLastKnownLocation", "getCurrentLocation", "ACCESS_BACKGROUND_LOCATION"
        ).forEach { needle ->
            val offenders = sources.filter { it.readText().contains(needle) }.map { it.name }
            assertTrue("$needle still referenced by $offenders", offenders.isEmpty())
        }
    }

    @Test
    fun `no app data leaves the phone by backup or device transfer`() {
        // The device_* domains are device-protected (direct boot) storage, which the wipe also clears.
        val domains = listOf(
            "root", "sharedpref", "database", "file", "external",
            "device_root", "device_sharedpref", "device_database", "device_file"
        )
        fun excludesAll(rules: String) = domains.forEach { domain ->
            assertTrue("$domain must be excluded whole", rules.contains("""<exclude domain="$domain" path="."/>"""))
        }
        fun noIncludes(rules: String) = assertFalse("no include may re-add data", rules.contains("<include"))
        val transfer = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val rules = Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(transfer)?.groupValues?.get(1)
            assertTrue("$section section missing", rules != null)
            excludesAll(rules!!)
            noIncludes(rules)
        }
        val legacy = File("src/main/res/xml/backup_rules.xml").readText()
        excludesAll(legacy)
        noIncludes(legacy)
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
    }
}
