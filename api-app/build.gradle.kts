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
        // 17 added §6.AC telemetry (ApiEngineService FGS-budget non-fatal). The 17
        // RELEASE distribute shipped a STALE versionCode-16 binary (the rebuild failed
        // mid-package; incident 2026-06-23-apiapp-17-release-stale-binary.json). 18 is the
        // CLEAN-rebuilt corrective ship carrying the FGS telemetry on BOTH variants;
        // firebase-distribute.sh now aapt-verifies the picked APK's actual versionCode.
        // versionName held (diagnostics-only).
        // 22 (§6.Y, this cycle): version-parity bump alongside the client 1076/1.3.12 cycle.
        // api-app gains the okhttp-androidTest fix + C06/C07 UI Challenges (test-only); no
        // user-facing functional change → versionName held (0.2.11, diagnostics-only app).
        // 23 (§6.Y post-distribute bump, 2026-06-26): 0.2.11-22 was §6.Z-gated
        // (Challenge01ApiAppColdStartTest PASS on thinker containerized-KVM,
        // .lava-ci-evidence/1076-apiapp-gate/) and §6.AA two-stage distributed
        // (debug 6mn8lmmqke928 + release 15l34kl1d1138). versionName HELD (diagnostics-only).
        // §6.Y post-distribution bump: 23 (debug+release distributed) → 24 for the
        // F3 fix — the embedded liblavaapi.so no longer SIGSYS-crashes on x86_64
        // (Android seccomp legacy-syscall remap in the modernc/libc fork). This is
        // a real user-facing crash fix on x86_64 devices/emulators → versionName
        // patch bump 0.2.11 → 0.2.12 (§6.Y.3).
        // §6.Y production-readiness sweep: container submodule pin advance,
        // device-gate preflight, WaitForBoot liveness. versionName held
        // (diagnostics-only app; no user-facing functional change).
        // 26 (§6.Y, this cycle): the embedded lava-api-go engine (liblavaapi.so,
        // rebuilt this cycle for all 3 ABIs) now correctly classifies a
        // Cloudflare bot-mitigation challenge on RuTracker login as a distinct
        // 503 (with browser-realistic outbound headers) instead of a generic,
        // misleading 502 — a real correctness + telemetry improvement to this
        // app's embedded API surface. versionName patch bump 0.2.12 → 0.2.13
        // (§6.Y.3).
        versionCode = 26
        versionName = "0.2.13"
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
    // okhttp alignment constraint (§6.J / consistent-resolution fix, 2026-06-25).
    // api-app's production code does NOT use okhttp — the on-device API is a Go
    // embed (:core:apiengine). okhttp appears ONLY transitively on the runtime
    // classpath, pulled by com.google.firebase:firebase-perf:21.0.5 (via
    // :core:analytics-firebase → firebase-bom), which hard-depends on the stale
    // okhttp 3.x line (resolved 3.12.13). Meanwhile the androidTest scope below
    // declares okhttp 4.12.0 (the project-canonical pin in libs.versions.toml)
    // as the real HTTPS client the C02/C03 Challenges use to hit the on-device
    // embed over TLS.
    //
    // AGP's consistent resolution projects the runtime-resolved okhttp version
    // as a `strictly` constraint onto debugAndroidTestRuntimeClasspath. Without
    // alignment that constraint is `strictly 3.12.13`, which conflicts with the
    // androidTest okhttp 4.12.0 and FAILS :api-app:assembleDebugAndroidTest with
    // "Cannot find a version of 'com.squareup.okhttp3:okhttp' that satisfies the
    // version constraints ... {strictly 3.12.13} ... by consistent resolution".
    //
    // This constraint aligns okhttp to the SAME project-canonical 4.12.0 the
    // version catalog pins and that :app already resolves to (where :app's
    // runtime carries a direct okhttp 4.12.0 via :core:data, so firebase-perf's
    // 3.12.13 is upgraded by normal conflict resolution). Here :api-app has no
    // direct runtime okhttp, so we express the same alignment as a constraint:
    // it only takes effect because firebase-perf already pulls okhttp — it adds
    // NO unused production dependency, and it does not downgrade anything (3.12.13
    // was a stale transitive, never an intentional runtime pin). Runtime then
    // resolves to 4.12.0; consistent resolution projects `strictly 4.12.0` onto
    // androidTest; the androidTest 4.12.0 dep matches. §6.R: version flows from
    // the catalog (libs.okhttp.core), never a hardcoded literal here.
    constraints {
        implementation(libs.okhttp.core)
    }

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
