package com.bitchat.android.store

import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreScreenshotContractTest {
    private val build = File("build.gradle.kts").readText()
    private val captureRoot = File("src/storeScreenshot")
    private val captureManifest = File(captureRoot, "AndroidManifest.xml").readText()
    private val captureActivity = File(captureRoot, "java/com/bitchat/android/store/StoreScreenshotActivity.kt").readText()

    @Test
    fun `capture build is debug signed isolated and explicitly gated`() {
        assertTrue(build.contains("create(\"storeScreenshot\")"))
        assertTrue(build.contains("applicationIdSuffix = \".storecapture\""))
        assertTrue(build.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
        assertTrue(build.contains("buildConfigField(\"boolean\", \"STORE_SCREENSHOT_MODE\", \"true\")"))
        assertTrue(build.contains("buildConfigField(\"boolean\", \"STORE_SCREENSHOT_MODE\", \"false\")"))
        assertTrue(captureActivity.contains("check(BuildConfig.STORE_SCREENSHOT_MODE)"))
    }

    @Test
    fun `release keeps secure window policy and cannot compile capture fixtures`() {
        val mainActivity = File("src/main/java/com/bitchat/android/MainActivity.kt").readText()
        val privacy = File("src/main/java/com/bitchat/android/ui/WeshWindowPrivacy.kt").readText()
        assertTrue(mainActivity.contains("WeshWindowPrivacy.apply(this)"))
        assertTrue(privacy.contains("FLAG_SECURE"))
        assertTrue(captureActivity.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"))
        assertFalse(File("src/main/java/com/bitchat/android/store/StoreScreenshotActivity.kt").exists())
        assertFalse(File("src/release/java/com/bitchat/android/store/StoreScreenshotActivity.kt").exists())
    }

    @Test
    fun `capture app cannot activate product runtime or sensitive permissions`() {
        assertTrue(captureManifest.contains("StoreCaptureApplication"))
        assertTrue(captureManifest.contains("<activity android:name=\".MainActivity\" tools:node=\"remove\""))
        assertTrue(
            Regex("<service\\s+android:name=\"\\.service\\.MeshForegroundService\"\\s+tools:node=\"remove\"\\s*/>")
                .containsMatchIn(captureManifest)
        )
        listOf(
            "android.permission.INTERNET",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
        ).forEach { permission ->
            assertTrue("$permission must be removed", Regex("android:name=\"$permission\"[^>]+tools:node=\"remove\"").containsMatchIn(captureManifest))
        }
        assertTrue(
            captureManifest.contains(
                "android:name=\"${'$'}{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\""
            )
        )
    }

    @Test
    fun `fixtures are synthetic bilingual and cover exactly seven canonical scenes`() {
        val text = File(captureRoot, "assets/store_scenes.json").readText()
        val root = JsonParser.parseString(text).asJsonObject
        assertTrue(root.get("synthetic").asBoolean)
        val scenes = root.getAsJsonArray("sceneOrder").map { it.asString }
        assertEquals(listOf("home", "public", "private", "exchange", "settings", "connection", "about"), scenes)
        assertEquals(setOf("fr", "en"), setOf("fr", "en").filter(root::has).toSet())
        listOf(
            Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"),
            Regex("(?:\\+|00)\\d[\\d .-]{7,}"),
            Regex("https?://", RegexOption.IGNORE_CASE),
            Regex("\\b(?:imei|serial|android[_ -]?id|ssid|bssid|mac address|peer[_ -]?id)\\b", RegexOption.IGNORE_CASE),
        ).forEach { forbidden -> assertFalse("possible sensitive metadata: $forbidden", forbidden.containsMatchIn(text)) }
    }

    @Test
    fun `launcher vectors contain Wesh and no stale BIT outline`() {
        listOf("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml", "wesh_launcher_foreground.xml").forEach { name ->
            val source = File("src/main/res/drawable/$name").readText()
            assertTrue(name, source.contains("M31,39 L40,69 L54,45 L68,69 L77,39"))
            assertFalse(name, source.contains("M187.45493"))
        }
    }

    @Test
    fun `Play assets have exact dimensions opaque RGBA pixels and no metadata chunks`() {
        assertOpaqueRgbaPng(File("../store-assets/play/icon-512.png"), 512, 512)
        assertOpaqueRgbaPng(File("../store-assets/play/fr-FR/feature-graphic.png"), 1024, 500)
        assertOpaqueRgbaPng(File("../store-assets/play/en-US/feature-graphic.png"), 1024, 500)
        val scenes = listOf("home", "public", "private", "exchange", "settings", "connection", "about")
        listOf("fr-FR", "en-US").forEach { locale ->
            scenes.forEachIndexed { index, scene ->
                val filename = "${(index + 1).toString().padStart(2, '0')}-$scene.png"
                assertOpaqueRgbaPng(
                    File("../store-assets/play/$locale/phoneScreenshots/$filename"),
                    1080,
                    1920,
                )
            }
        }
    }

    @Test
    fun `capture preserves system bars and waits for composed product views`() {
        val script = File("../tools/store-assets/capture_android_store_screenshots.sh").readText()
        assertFalse(script.contains("adb devices"))
        assertFalse(script.contains("immersive_mode_confirmations"))
        assertTrue(script.contains("command clock -e hhmm 0941"))
        assertTrue(script.contains("command exit"))
        assertTrue(script.contains("READY ${'$'}language ${'$'}scene"))
        assertTrue(script.contains("wait_store_capture_stability.py --target"))
        assertTrue(script.contains("\"${'$'}api\" != 35 || \"${'$'}density\" != 420"))
        assertTrue(script.contains("--context \"${'$'}temporary_dir/context.json\""))
        assertFalse(captureActivity.contains("insetsController.hide"))
        assertFalse(captureActivity.contains("isAppearanceLightStatusBars"))
        assertFalse(captureActivity.contains("isAppearanceLightNavigationBars"))
        assertTrue(captureActivity.contains("MirroredProductionFrame"))
        assertTrue(captureActivity.contains("Scaffold("))
        assertTrue(captureActivity.contains("containerColor = MaterialTheme.colorScheme.background"))
        assertTrue(captureActivity.contains("ConnexionSheet("))
        assertFalse(captureActivity.contains("ConnexionSheetContent("))
        assertTrue(captureActivity.contains("withFrameNanos"))
        assertTrue(captureActivity.contains("phonesInReach = 0"))
        assertTrue(captureActivity.contains("bluetoothOn = false"))
        assertTrue(captureActivity.contains("PaveBluetoothInfo(on = false, phonesInRange = 0)"))
        assertTrue(captureActivity.contains("available = true"))
        assertTrue(captureActivity.contains("enabled = true"))
        assertTrue(captureActivity.contains("status = PaveInternetStatus.On(relays = 2)"))
    }

    @Test
    fun `asset generation uses one digest pinned Linux renderer in local and CI`() {
        val dockerfile = File("../tools/store-assets/Dockerfile").readText()
        val runner = File("../tools/store-assets/run_in_container.sh").readText()
        val verifier = File("../tools/store-assets/verify_store_assets.sh").readText()
        val workflow = File("../.github/workflows/android-build.yml").readText()
        assertTrue(dockerfile.contains("python:3.12.12-slim-bookworm@sha256:"))
        assertTrue(dockerfile.contains("PIL.__version__ == \"12.2.0\""))
        assertTrue(runner.contains("--platform linux/amd64"))
        assertTrue(runner.contains("--network none"))
        assertTrue(verifier.contains("run_in_container.sh"))
        assertTrue(workflow.contains("run: tools/store-assets/verify_store_assets.sh"))
        assertFalse(workflow.contains("run: python3 tools/store-assets/verify_store_assets.py"))
    }

    private fun assertOpaqueRgbaPng(file: File, width: Int, height: Int) {
        val chunks = mutableListOf<String>()
        var ihdr: ByteArray? = null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val signature = ByteArray(8).also(input::readFully)
            assertTrue(file.path, signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
            while (true) {
                val length = input.readInt()
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val data = ByteArray(length).also(input::readFully)
                input.readInt() // CRC is validated by ImageIO below.
                chunks += type
                if (type == "IHDR") ihdr = data
                if (type == "IEND") break
            }
        }
        assertEquals(file.path, listOf("IHDR", "IDAT", "IEND"), chunks.distinct())
        DataInputStream(ihdr!!.inputStream()).use { header ->
            assertEquals(file.path, width, header.readInt())
            assertEquals(file.path, height, header.readInt())
            assertEquals(file.path, 8, header.readUnsignedByte())
            assertEquals(file.path, 6, header.readUnsignedByte()) // true-colour with alpha: 32-bit RGBA
        }
        val image: BufferedImage = ImageIO.read(file)
        assertTrue(file.path, image.colorModel.hasAlpha())
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                assertEquals(file.path, 255, image.getRGB(x, y).ushr(24))
            }
        }
    }
}
