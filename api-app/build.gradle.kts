@file:Suppress("UnstableApiUsage")

// :api-app — the standalone "Lava API" Android application. It hosts the
// embedded lava-api-go server (via :core:apiengine) as an on-device,
// LAN-reachable HTTPS API, advertised over mDNS so other devices discover it.
//
// Phase D-infra of the Lava API Android app plan delivers the headless
// infrastructure: the foreground Service, the lifecycle controller, the mDNS
// advertiser, and the per-install auth-key store. The Compose landing UI +
// notification copy + ViewModel are Phase D-ui (a separate dispatch); this
// module ships only a placeholder MainActivity so the manifest is valid and
// the APK assembles.
//
// Signing: this module REUSES the EXACT signing block :app uses (same
// .env-driven keystore password + keystore dir). Debug builds use the auto
// debug keystore; release builds use keystores/release.keystore. We do not
// invent any keys (§6.R / §6.H).

plugins {
    id("lava.android.application")
    id("lava.android.hilt")
}

// Mirror :app/build.gradle.kts's .env-driven signing inputs verbatim — the
// constitutional requirement is to reuse the SAME signing material, not invent
// a parallel scheme.
fun loadEnv(file: File = rootProject.file(".env")): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .toMap()
}

val env = loadEnv()
val keystorePassword = env["KEYSTORE_PASSWORD"] ?: "l@vAfl0wZ!"
val keystoreRootDir = env["KEYSTORE_ROOT_DIR"] ?: "keystores"

// API↔embed source-sync (§11.4.69 / §6.J, 2026-06-06): compute the
// single-source-of-truth hash of the lava-api-go source codebase compiled into
// the embed (liblavaapi.so, packaged into THIS api-app APK via :core:apiengine),
// at Gradle-config time, via the SAME script the build + gate use
// (scripts/compute-api-source-hash.sh). It is baked into
// BuildConfig.LAVA_API_SOURCE_HASH so the on-device sync Challenge (C05) asserts
// the RUNNING embed's reported hash (ApiStatus.sourceHash, surfaced from the
// .so's ldflags-injected version.SourceHash) equals the hash THIS APK was built
// against — equal hashes prove the on-device embed contains EXACTLY the current
// API codebase (no drift). Empty when the script is unavailable; the Challenge
// treats empty as a hard fail so a hashless build cannot pass the gate silently.
val lavaApiSourceHash: String = run {
    val script = rootProject.file("scripts/compute-api-source-hash.sh")
    if (!script.exists()) return@run ""
    runCatching {
        val proc = ProcessBuilder("bash", script.absolutePath)
            .directory(rootProject.projectDir)
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (proc.exitValue() == 0) out else ""
    }.getOrDefault("")
}

android {
    namespace = "lava.api.app"

    defaultConfig {
        applicationId = "digital.vasic.lava.api"
        // CLIENT_RELEASE_PACKAGE: used by SiblingAppLauncher for the client app.
        // §6.R: package-id constant, not a credential — permitted as BuildConfig.
        buildConfigField(
            "String",
            "CLIENT_RELEASE_PACKAGE",
            "\"digital.vasic.lava.client\"",
        )
        // §6.R: client app download URL (Firebase App Distribution) — value lives
        // in .env (LAVA_CLIENT_APP_DOWNLOAD_URL). Empty when unconfigured →
        // SiblingAppLauncher falls back to a placeholder constant.
        buildConfigField(
            "String",
            "LAVA_CLIENT_APP_DOWNLOAD_URL",
            "\"${env.getOrDefault("LAVA_CLIENT_APP_DOWNLOAD_URL", "")}\"",
        )
        // API_KEY_AUTHORITY default (release). Debug variant overrides below.
        // The manifest placeholder is set per-variant so the ContentProvider
        // authority matches the variant applicationId.
        buildConfigField(
            "String",
            "API_KEY_AUTHORITY",
            "\"digital.vasic.lava.api.keyprovider\"",
        )
        manifestPlaceholders["apiKeyAuthority"] = "digital.vasic.lava.api.keyprovider"
        // P2-1 (2026-06-14): variant-suffix the READ_API_KEY signature permission
        // so a device with BOTH the debug (.dev) and release api-app installed
        // never hits INSTALL_FAILED_DUPLICATE_PERMISSION (two differently-signed
        // packages contending to DEFINE the same fixed permission name). Mirrors
        // how apiKeyAuthority is already variant-suffixed. Release keeps the
        // BYTE-IDENTICAL name so existing release grants survive; debug overrides
        // to the .dev-suffixed name below. §6.R: no manifest literal — the name
        // flows from this placeholder. The matching BuildConfig field keeps
        // runtime code (ApiKeyProvider.attachInfoForTest) in lockstep with the
        // manifest per variant.
        manifestPlaceholders["apiKeyPermission"] = "digital.vasic.lava.permission.READ_API_KEY"
        buildConfigField(
            "String",
            "API_KEY_PERMISSION",
            "\"digital.vasic.lava.permission.READ_API_KEY\"",
        )
        // API↔embed source-sync hash (§11.4.69 / §6.J): the 64-hex sha256 of the
        // lava-api-go source the embed in this APK was built from (see
        // lavaApiSourceHash above). The on-device sync Challenge C05 asserts
        // NativeApiEngine().status().sourceHash == this value. NOT a secret — a
        // build-derived integrity fingerprint, so the generated-config form §6.R
        // permits (computed from source, never hand-typed).
        buildConfigField("String", "LAVA_API_SOURCE_HASH", "\"$lavaApiSourceHash\"")
        versionCode = 15
        versionName = "0.2.10"
        // EncryptedSharedPreferences (androidx.security-crypto) requires API 23+
        // — the per-install auth-key store ([ApiKeyStore]) relies on it. The
        // standalone API-server app reasonably targets API 23+ (the client app
        // keeps minSdk 21).
        minSdk = 23
        // Phase E: the Compose UI Challenge Tests are @HiltAndroidTest; the
        // custom runner swaps in HiltTestApplication so injection works in the
        // instrumented environment (same pattern :app uses).
        testInstrumentationRunner = "lava.api.app.LavaApiHiltTestRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("$keystoreRootDir/debug.keystore")
            storePassword = keystorePassword
            keyAlias = "debug"
            keyPassword = keystorePassword
        }
        create("release") {
            storeFile = rootProject.file("$keystoreRootDir/release.keystore")
            storePassword = keystorePassword
            keyAlias = "release"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            postprocessing {
                isRemoveUnusedCode = true
                isRemoveUnusedResources = true
                isObfuscate = false
                isOptimizeCode = true
                setProguardFiles(
                    listOf(
                        getDefaultProguardFile("proguard-defaults.txt"),
                        "proguard-rules.pro",
                    ),
                )
            }
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
            // Debug app-id is digital.vasic.lava.api.dev, so the provider
            // authority and the manifest placeholder must use the .dev suffix.
            buildConfigField(
                "String",
                "API_KEY_AUTHORITY",
                "\"digital.vasic.lava.api.dev.keyprovider\"",
            )
            manifestPlaceholders["apiKeyAuthority"] = "digital.vasic.lava.api.dev.keyprovider"
            // P2-1: debug defines the .dev-suffixed permission name so the
            // debug (.dev) and release api-app never collide on the same device.
            manifestPlaceholders["apiKeyPermission"] = "digital.vasic.lava.permission.dev.READ_API_KEY"
            buildConfigField(
                "String",
                "API_KEY_PERMISSION",
                "\"digital.vasic.lava.permission.dev.READ_API_KEY\"",
            )
        }
    }

    buildFeatures { buildConfig = true }

    testOptions {
        unitTests {
            // Required by Robolectric so it can find the app's resources
            // (e.g., ApplicationProvider.getApplicationContext()) in JVM tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Shared cross-app intent contract (AppLinkContract, SiblingAppLauncher,
    // PackageManagerSiblingAppLauncher). Both :app and :api-app depend on this
    // so the intent contract cannot drift between the two apps. §4 design spec.
    implementation(project(":core:applink"))
    implementation(project(":core:apiengine"))
    // Firebase Crashlytics/Analytics non-fatal telemetry (§6.AC / §6.O). The
    // shared :core:analytics-firebase module provides the AnalyticsTracker Hilt
    // binding + the resilient FirebaseInitializer + the Firebase artifacts
    // (transitively, via its api(...) deps). The google-services + crashlytics
    // gradle plugins are inherited via lava.android.application and process
    // THIS app's api-app/google-services.json (registers digital.vasic.lava.api
    // + .api.dev). NO Go-side REST key in the .so (§6.H) — telemetry routes
    // through this Kotlin bridge.
    implementation(project(":core:analytics-firebase"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:notifications"))
    // LavaTheme's `theme: Theme = Theme.SYSTEM` default arg references
    // lava.models.settings.Theme; :core:designsystem exposes it as `implementation`
    // (not api), so declare :core:models here for the classpath.
    implementation(project(":core:models"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // NativeApiEngine's default constructor references kotlinx.serialization.json.Json
    // (the embed's wire codec). :core:apiengine declares it as `implementation`,
    // so it is not exposed transitively — declare it here for the classpath.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.ktx)

    implementation(libs.bundles.orbit)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.orbit.test)
    testImplementation(libs.mockk)
    // Reuse the canonical MainDispatcherRule (swaps Dispatchers.Main for the
    // test scheduler so the ViewModel's viewModelScope coroutines run under
    // runTest). Same pattern every feature ViewModel test uses.
    testImplementation(project(":core:testing"))
    // Robolectric: used by ApiKeyProviderTest to exercise ContentProvider
    // query() against a real Robolectric application context without a device.
    // Pattern mirrors core:designsystem's PaletteContractTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // ----------------------------------------------------------------
    // Phase E: Compose UI Challenge Tests (instrumented). These drive the
    // REAL ApiControlScreen + ApiControlViewModel + ApiEngineController +
    // foreground Service on a real device/emulator, then issue real HTTPS
    // requests to the on-device embed. The Compose BOM is applied to
    // androidTestImplementation by the AndroidCompose convention plugin, so
    // the compose-ui-test libraries resolve without explicit versions.
    // ----------------------------------------------------------------
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.testManifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    // OkHttp is the real HTTPS client the Challenges use to hit the on-device
    // embed over TLS (C02/C03). It is a test-only boundary tool — the embed
    // itself is the production artifact under test.
    androidTestImplementation(libs.okhttp.core)
    kspAndroidTest(libs.hilt.compiler)
}
