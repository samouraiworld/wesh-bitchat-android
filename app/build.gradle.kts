import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

val githubReleaseCertSha256 = providers
    .environmentVariable("BITCHAT_GITHUB_RELEASE_CERT_SHA256")
    .orElse(providers.gradleProperty("BITCHAT_GITHUB_RELEASE_CERT_SHA256"))
    .orElse("")
val normalizedGithubReleaseCertSha256 = githubReleaseCertSha256.get()
    .replace(":", "")
    .trim()
    .lowercase()
require(
    normalizedGithubReleaseCertSha256.isEmpty() ||
        normalizedGithubReleaseCertSha256.matches(Regex("[a-f0-9]{64}"))
) {
    "BITCHAT_GITHUB_RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint"
}

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "org.samouraiworld.wesh.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Product-safety policy (recovery gate, permissions, QR-only private messages, no
        // geohash/location/updater/Wi-Fi Aware). Never switches the Internet on or off.
        buildConfigField("boolean", "WESH_POLICY", "true")
        // Whether the Internet opt-in can be turned on at all. Sockets still need the runtime
        // toggle (WeshNetworkPolicy.allowsNetwork()), which defaults off and needs the disclosure.
        buildConfigField("boolean", "WESH_INTERNET_AVAILABLE", "true")
        // True only for the isolated, debug-signed store-capture variant. Product code never
        // uses this to weaken the secure-window policy; the capture activity lives exclusively
        // in src/storeScreenshot and is absent from release artifacts.
        buildConfigField("boolean", "STORE_SCREENSHOT_MODE", "false")
        versionCode = 105
        versionName = "1.0.0"
        buildConfigField(
            "String",
            "GITHUB_RELEASE_CERT_SHA256",
            "\"$normalizedGithubReleaseCertSha256\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        debug {
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        create("storeScreenshot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".storecapture"
            versionNameSuffix = "-storecapture"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "STORE_SCREENSHOT_MODE", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo {
                // BUILDINFO.json and attestations carry the verified commit
                // without depending on host-specific Git/worktree paths.
                include = false
            }
        }
    }

    // APK splits for GitHub releases - creates arm64, x86_64, and universal APKs
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    bundle {
        // Every phone needs the French and English labels so remote nicknames are checked against both.
        language { enableSplit = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        // The capture manifest intentionally removes notification/foreground-service components.
        // Shared product classes are still compiled but unreachable in that isolated APK, so its
        // dedicated baseline contains only those four dead-code incidents. Every other variant
        // keeps the product baseline and permissions/service lint coverage.
        val storeScreenshotOnly = gradle.startParameter.taskNames.any {
            it.substringAfterLast(':') == "lintStoreScreenshot"
        }
        baseline = file(
            if (storeScreenshotOnly) "lint-store-screenshot-baseline.xml" else "lint-baseline.xml"
        )
        abortOnError = true
        checkReleaseBuilds = true
    }
}

composeCompiler {
    // Kotlin 2.4.10's optional Compose group-key mapping depends on unspecified
    // class-file iteration order. Keep the normal R8 mapping, but omit that
    // augmentation until its producer is deterministic across clean builds.
    includeComposeMappingFile.set(false)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    implementation(libs.zxing.core)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // Arti JNI libraries are vendored in src/main/jniLibs for all four ABIs.
    // tools/arti-build pins their source, dependencies, and native toolchain.

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    // Compose UI tests of single screens on Robolectric, with the JVM unit tests.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Robolectric resolves Android runtime jars itself (outside Gradle dependency resolution).
// Its legacy repo1 endpoint rejects cold GitHub-hosted runners with HTTP 403.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://repo.maven.apache.org/maven2"
    )
}

// A changed dependency lock must never silently package the previous vendored
// libraries. The native rebuild emits both manifests after all four ABIs pass.
val verifyNativeArtiInputs = tasks.register("verifyNativeArtiInputs") {
    val nativeRoot = rootProject.layout.projectDirectory
    val manifests = listOf("SHA256SUMS", "SOURCE_SHA256SUMS")
    inputs.files(manifests.map { nativeRoot.file("tools/arti-build/$it") })
    inputs.files(fileTree(nativeRoot.dir("tools/arti-build")) {
        include("Cargo.lock", "Cargo.toml", "Dockerfile", "TOOLCHAIN.env", "rust-toolchain.toml", "build-arti.sh", "native_symbols.py", "provenance.sh", "rebuild-in-container.sh", "src/lib.rs")
    })
    inputs.files(fileTree("src/main/jniLibs") { include("**/libarti_android.so") })
    doLast {
        val patches = nativeRoot.dir("tools/arti-build/patches").asFile
        check(!patches.isDirectory || patches.listFiles().isNullOrEmpty()) {
            "Native patches need explicit provenance support before use"
        }
        manifests.forEach { name ->
            val manifest = nativeRoot.file("tools/arti-build/$name").asFile
            check(manifest.isFile) { "Missing native provenance; rebuild all four Arti ABIs" }
            val entries = manifest.readLines().filter { it.isNotBlank() }
            val expectedPaths = if (name == "SHA256SUMS") {
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    .map { "app/src/main/jniLibs/$it/libarti_android.so" }.toSet()
            } else {
                listOf("Cargo.lock", "Cargo.toml", "Dockerfile", "TOOLCHAIN.env", "rust-toolchain.toml", "build-arti.sh", "native_symbols.py", "provenance.sh", "rebuild-in-container.sh", "src/lib.rs")
                    .map { "tools/arti-build/$it" }.toSet()
            }
            check(entries.size == expectedPaths.size && entries.map {
                it.split(Regex("\\s+"), limit = 2).getOrNull(1)
            }.toSet() == expectedPaths) {
                "Incomplete native provenance; rebuild all four Arti ABIs"
            }
            entries.forEach { line ->
                val fields = line.split(Regex("\\s+"), limit = 2)
                check(fields.size == 2 && fields[0].matches(Regex("[a-f0-9]{64}"))) {
                    "Invalid native checksum entry"
                }
                val relative = fields[1]
                check(!relative.startsWith("/") && !relative.split('/').contains("..")) {
                    "Native checksum paths must be repository relative"
                }
                val source = nativeRoot.file(relative).asFile
                check(source.isFile) { "Missing native input: $relative" }
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    var count = input.read(buffer)
                    while (count >= 0) {
                        if (count > 0) digest.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == fields[0]) { "Native input changed: $relative; rebuild all four Arti ABIs" }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyNativeArtiInputs) }
