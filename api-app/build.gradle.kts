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
        versionCode = 5
        versionName = "0.2.1"
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
