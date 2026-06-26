@file:Suppress("UnstableApiUsage")

plugins {
    id("lava.android.application")
    id("lava.android.hilt")
}

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
// the on-device embed (liblavaapi.so), at Gradle-config time, via the SAME
// script the build + gate use (scripts/compute-api-source-hash.sh). It is baked
// into BuildConfig.LAVA_API_SOURCE_HASH so the on-device sync Challenge can
// assert the RUNNING embed's reported hash (ApiStatus.sourceHash, surfaced from
// the .so's ldflags-injected version.SourceHash) equals the hash THIS APK was
// built against — equal hashes prove the embed contains EXACTLY the current API
// codebase (no drift). Empty when the script is unavailable; the Challenge
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
    namespace = "digital.vasic.lava.client"

    defaultConfig {
        applicationId = "digital.vasic.lava.client"
        // Release history (most recent first): 1070 = clean re-spin + §6.AC search
        // telemetry groundwork; 1071 = comprehensive §6.AC non-fatal telemetry
        // (distributed debug 0r7c809nc89gg + release 6bl8iu7jvq81g, §6.Z C00 GREEN).
        // 1072 = THE SEARCH-401 FIX (H1): AuthInterceptor previously overwrote the
        // per-install handoff key with the build-time LAVA_AUTH UUID (same "Lava-Auth"
        // header name, OkHttp .header() replace, interceptor fires last) → on-device
        // 401 → "Something went wrong". Fixed: the interceptor attaches the build-time
        // UUID ONLY IF the request does not already carry a Lava-Auth header, so the
        // handoff key survives to the wire (wire-level MockWebServer test, provably safe
        // for both on-device + remote-API paths). Auth rotated android-1.3.11-1072
        // (fresh pepper, append-only). versionName held (same 1.3.11 user-facing release).
        // 1076 / 1.3.12 (§6.Y, this cycle): ships REAL user-facing fixes → versionName BUMPED
        // 1.3.11→1.3.12 (per §6.Y.3 user-facing bug fixes warrant a patch bump):
        //   (1) FIXED the provider "Sync this provider" toggle CRASH (Settings→provider→toggle →
        //       release-only SerializationException at ProviderConfigViewModel.kt:92 — missing
        //       kotlinx-serialization plugin + no R8 keep-rules; all 3 wire classes; §11.4.146 RED→GREEN).
        //   (2) FIXED search-unusable (SearchInputViewModel hardcoded a 4-provider list divorced
        //       from the onboarded set → wrong providers → 0 results → "Something went wrong"; now
        //       reads ProviderConfigRepository.observeAll() filtered+sorted; loading/empty state added).
        //   (3) FIXED 5 display/onboarding bugs (result-chip names, provider count, API labels, select-all).
        // Plus 13 new/rewritten Compose UI Challenges (C48–C57 + C31–C35 render/interaction).
        // LVA-008 nav-teardown crash remains OPEN (conclusively upstream androidx defect, 8 candidates
        // falsified — minimal-repro authored for upstream filing). Auth rotated android-1.3.12-1076
        // (fresh pepper, active-list prepend). On-device R8-release + search verification at the §6.Z gate.
        // 1077 (§6.Y post-distribute bump, 2026-06-26): 1.3.12-1076 was §6.Z-gated
        // (C00 cold-start PASS on thinker containerized-KVM, .lava-ci-evidence/1076-c00-gate/)
        // and §6.AA two-stage distributed (debug 216fs8pr1dkbg + release 50dusshe2uru0).
        // versionName HELD — no new user-facing changes yet in the 1077 dev cycle (the video #3
        // deterministic-chip fix shipped in 1076). Auth NOT re-rotated until 1077 actually distributes.
        versionCode = 1077
        versionName = "1.3.12"
        // SP-3a Step 6 (2026-04-30): wire Hilt + Compose UI test infra so the
        // 8 Challenge Tests at app/src/androidTest/kotlin/lava/app/challenges/
        // become runnable on a connected device. The custom runner installs
        // HiltTestApplication as the test Application; without it
        // @HiltAndroidTest classes cannot inject.
        testInstrumentationRunner = "lava.app.LavaHiltTestRunner"

        // Constitutional clause 6.H — credentials come from .env at build
        // time, never from tracked source. Empty default makes the
        // corresponding Challenge Test fail with a clear "credential not
        // configured" message in environments without .env (CI, fresh
        // checkouts) rather than silently embedding placeholder strings.
        // Bluff-prevented: the original BuildConfigBridge inside C2
        // hardcoded real credentials matching .env literally — committed
        // 2026-04-30 in dd387385, classified as a Seventh Law clause 6
        // incident on 2026-05-04. See .lava-ci-evidence/sixth-law-incidents/
        // for the forensic record.
        buildConfigField("String", "RUTRACKER_USERNAME", "\"${env["RUTRACKER_USERNAME"].orEmpty()}\"")
        buildConfigField("String", "RUTRACKER_PASSWORD", "\"${env["RUTRACKER_PASSWORD"].orEmpty()}\"")
        buildConfigField("String", "KINOZAL_USERNAME", "\"${env["KINOZAL_USERNAME"].orEmpty()}\"")
        buildConfigField("String", "KINOZAL_PASSWORD", "\"${env["KINOZAL_PASSWORD"].orEmpty()}\"")
        buildConfigField("String", "NNMCLUB_USERNAME", "\"${env["NNMCLUB_USERNAME"].orEmpty()}\"")
        buildConfigField("String", "NNMCLUB_PASSWORD", "\"${env["NNMCLUB_PASSWORD"].orEmpty()}\"")
        // 2026-05-31: default Cloud / remote-server API option for the
        // onboarding "Choose your API" screen. §6.R: the value lives in .env
        // (placeholder https://lava.app:7777 documented in .env.example),
        // never a source literal. Empty when unconfigured → the cloud section
        // still renders the manual-entry field with no preset.
        buildConfigField("String", "DEFAULT_CLOUD_API", "\"${env["LAVA_DEFAULT_CLOUD_API"].orEmpty()}\"")
        // Sub-project 2 (on-device API): download page for the separate Lava
        // API app, used by the Settings "Run the API on this device" row when
        // the API app is not installed. §6.R: the value lives in .env
        // (placeholder https://lava.app/download/api-app documented in
        // .env.example); TODO sub-project 4: wire the real Firebase App
        // Distribution link. Empty when unconfigured → app-layer wiring falls
        // back to the documented placeholder constant.
        buildConfigField("String", "LAVA_API_APP_DOWNLOAD_URL", "\"${env["LAVA_API_APP_DOWNLOAD_URL"].orEmpty()}\"")
        // §6.R: client app download URL (Firebase App Distribution) — value lives
        // in .env (LAVA_CLIENT_APP_DOWNLOAD_URL). Empty when unconfigured →
        // OnboardingAppLinkModule falls back to a placeholder constant.
        buildConfigField("String", "LAVA_CLIENT_APP_DOWNLOAD_URL", "\"${env["LAVA_CLIENT_APP_DOWNLOAD_URL"].orEmpty()}\"")

        // Task 3.1 (2026-06-03): variant-aware authority + package for the
        // API app's key ContentProvider. The authority is the API app's
        // applicationId + ".keyprovider". §6.R: no literals in source code —
        // the debug applicationId is "digital.vasic.lava.api.dev" (the api-app's
        // debug applicationIdSuffix = ".dev"); release is "digital.vasic.lava.api".
        // These are build constants (not secrets), so they live in BuildConfig
        // per the §6.R exemption for package IDs.
        // API↔embed source-sync hash (§11.4.69 / §6.J, 2026-06-06): the 64-hex
        // sha256 of the lava-api-go source the embed was built from, computed at
        // config time (see lavaApiSourceHash above). The on-device sync Challenge
        // asserts ApiStatus.sourceHash == this value. NOT a secret — it is a
        // build-derived integrity fingerprint, so a source literal here is the
        // generated-config form §6.R permits (it is computed from source, never
        // hand-typed).
        buildConfigField("String", "LAVA_API_SOURCE_HASH", "\"$lavaApiSourceHash\"")
        buildConfigField("String", "API_RELEASE_PACKAGE", "\"digital.vasic.lava.api\"")
        // API_TARGET_PACKAGE is overridden per build type below (debug → .dev,
        // release → same as API_RELEASE_PACKAGE). Declared here as a
        // placeholder so the field always exists in BuildConfig; the per-type
        // override wins at build time.
        buildConfigField("String", "API_TARGET_PACKAGE", "\"digital.vasic.lava.api\"")
        // P2-1 (2026-06-14): the client's <uses-permission> name for the
        // api-app's signature permission must match the api-app's variant-
        // defined name (release → READ_API_KEY, debug → dev.READ_API_KEY) so a
        // device with both variants installed never hits
        // INSTALL_FAILED_DUPLICATE_PERMISSION. §6.R: the manifest name flows
        // from the apiKeyPermission placeholder; release keeps the BYTE-IDENTICAL
        // name so existing release grants survive. Debug overrides below.
        manifestPlaceholders["apiKeyPermission"] = "digital.vasic.lava.permission.READ_API_KEY"
        buildConfigField("String", "API_KEY_PERMISSION", "\"digital.vasic.lava.permission.READ_API_KEY\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // 2026-05-05 (post-§6.O): JVM unit tests for app-internal helpers
    // need Android framework calls (Bundle, Log.w) to return default
    // values instead of throwing "not mocked" — the FirebaseAnalyticsTracker
    // tests verify nullable-SDK + throwing-SDK paths and don't care
    // about real Bundle/Log behavior.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Task 3.1 (2026-06-03): enable Android resources for Robolectric
        // (ApiKeyClientTest uses ApplicationProvider + ShadowContentResolver).
        unitTests.isIncludeAndroidResources = true
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
            // IMPORTANT-3a: debug client targets the .dev API app, not the
            // release one. The release fallback (API_RELEASE_PACKAGE) still
            // points to the Play Store listing (release id, no .dev suffix).
            buildConfigField("String", "API_TARGET_PACKAGE", "\"digital.vasic.lava.api.dev\"")
            // P2-1: debug client requests the .dev-suffixed permission the debug
            // api-app defines, matching the variant pair.
            manifestPlaceholders["apiKeyPermission"] = "digital.vasic.lava.permission.dev.READ_API_KEY"
            buildConfigField("String", "API_KEY_PERMISSION", "\"digital.vasic.lava.permission.dev.READ_API_KEY\"")
        }
    }

    // Phase 11 (2026-05-06): generated lava.auth.LavaAuthGenerated source.
    // The output dir is added per-variant in afterEvaluate below so each
    // variant only sees its own generated class — avoids Gradle 8.9's
    // implicit-dependency validation when two tasks write to dirs that
    // share the same source set.
}

// Phase 11 (2026-05-06): build-time encryption of the per-build UUID.
// Reads .env + signing keystore, generates lava/auth/LavaAuthGenerated.kt
// into app/build/generated/lava-auth/main/ (gitignored). Wired BEFORE
// compileKotlin so the generated source is always present at compile time.
//
// The generated class implements lava.network.impl.LavaAuthBlobProvider;
// the runtime AuthInterceptorModule's reflection-based provider lookup
// (Phase 10) prefers it over StubLavaAuthBlobProvider when present.
// Single generation task: writes to a shared dir under generated/lava-auth/.
// Both debug and release use the same generated file path; only the keystore
// differs. Since the output dir is NOT in sourceSets.main (we add it per-variant
// below), Gradle 8.9's implicit-dependency validation does not fire.
// Each variant's source set gains the dir via afterEvaluate.
val generateLavaAuthClassDebug = tasks.register("generateLavaAuthClassDebug") {
    val outputDir = layout.buildDirectory.dir("generated/lava-auth/debug")
    outputs.dir(outputDir)
    inputs.file(rootProject.file(".env"))
    inputs.file(rootProject.file("$keystoreRootDir/debug.keystore"))
    doLast {
        val outFile = outputDir.get().asFile.resolve("lava/auth/LavaAuthGenerated.kt")
        LavaAuthCodegen.generate(
            envFile = rootProject.file(".env"),
            keystoreFile = rootProject.file("$keystoreRootDir/debug.keystore"),
            keystorePassword = keystorePassword,
            keyAlias = "debug",
            outputFile = outFile,
        )
    }
}

val generateLavaAuthClassRelease = tasks.register("generateLavaAuthClassRelease") {
    val outputDir = layout.buildDirectory.dir("generated/lava-auth/release")
    outputs.dir(outputDir)
    inputs.file(rootProject.file(".env"))
    inputs.file(rootProject.file("$keystoreRootDir/release.keystore"))
    doLast {
        val outFile = outputDir.get().asFile.resolve("lava/auth/LavaAuthGenerated.kt")
        LavaAuthCodegen.generate(
            envFile = rootProject.file(".env"),
            keystoreFile = rootProject.file("$keystoreRootDir/release.keystore"),
            keystorePassword = keystorePassword,
            keyAlias = "release",
            outputFile = outFile,
        )
    }
}

afterEvaluate {
    // Wire variant-specific compile/KSP tasks to the correct generation task
    // AND add the variant-specific generated dir to that variant's source set.
    // Using Gradle's task API (not AGP variant API) for compatibility.
    tasks.matching { it.name in listOf("compileDebugKotlin", "kspDebugKotlin") }.configureEach {
        dependsOn(generateLavaAuthClassDebug)
    }
    tasks.matching { it.name in listOf("compileReleaseKotlin", "kspReleaseKotlin") }.configureEach {
        dependsOn(generateLavaAuthClassRelease)
    }

    // Add generated source dirs per variant source set.
    android.sourceSets.matching { it.name == "debug" }.configureEach {
        kotlin.srcDir(layout.buildDirectory.dir("generated/lava-auth/debug"))
    }
    android.sourceSets.matching { it.name == "release" }.configureEach {
        kotlin.srcDir(layout.buildDirectory.dir("generated/lava-auth/release"))
    }
}

dependencies {
    // Task 3.1 (2026-06-03): shared cross-app linking contract + launcher.
    implementation(project(":core:applink"))
    implementation(project(":core:auth:impl"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:dispatchers"))
    implementation(project(":core:domain"))
    implementation(project(":core:downloads"))
    implementation(project(":core:logger"))
    implementation(project(":core:models"))
    implementation(project(":core:navigation"))
    implementation(project(":core:network:impl"))
    implementation(project(":core:notifications"))
    implementation(project(":core:preferences"))
    implementation(project(":core:tracker:client"))
    implementation(project(":core:ui"))
    implementation(project(":core:work:impl"))

    implementation(project(":feature:account"))
    implementation(project(":feature:bookmarks"))
    implementation(project(":feature:category"))
    implementation(project(":feature:connection"))
    implementation(project(":feature:favorites"))
    implementation(project(":feature:forum"))
    implementation(project(":feature:login"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:main"))
    implementation(project(":feature:menu"))
    implementation(project(":feature:rating"))
    implementation(project(":feature:search"))
    implementation(project(":feature:search_input"))
    implementation(project(":feature:search_result"))
    implementation(project(":feature:topic"))
    implementation(project(":feature:credentials"))
    implementation(project(":feature:credentials_manager"))
    implementation(project(":feature:provider_config"))
    implementation(project(":feature:visited"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.bundles.orbit)
    implementation(libs.bundles.work)
    implementation(libs.androidx.hilt.navigation.compose)

    // Firebase telemetry. The impl + DI + initializer live in the shared
    // :core:analytics-firebase module (Decoupled Reusable Architecture — both
    // :app and :api-app consume it; no copy-paste). The Firebase BOM +
    // analytics/crashlytics/perf artifacts come transitively via that module's
    // `api(...)` deps, so LavaApplication's Firebase.crashlytics/analytics/
    // performance ktx accessors + the google-services/crashlytics gradle
    // plugins (inherited via lava.android.application) still resolve.
    implementation(project(":core:analytics-firebase"))

    debugImplementation(libs.leakcanary)

    // JVM unit tests for app-internal helpers (FirebaseInitializer post-§6.O)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    // Task 3.1 (2026-06-03): Robolectric for ApiKeyClientTest (ContentResolver stub)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // ----------------------------------------------------------------
    // SP-3a Step 6 (2026-04-30): Compose UI + Hilt instrumentation test
    // dependencies. These wire the 8 Challenge Tests at
    // app/src/androidTest/kotlin/lava/app/challenges/ so they compile
    // and (on a connected device) run. The compose BOM is already
    // applied to androidTestImplementation by the convention plugin
    // (see buildSrc/.../AndroidCompose.kt) — these libraries are
    // declared without an explicit version because the BOM resolves
    // them.
    // ----------------------------------------------------------------
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.testManifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    // L1 fix (2026-05-05): force-upgrade Espresso core to 3.7.0 so the
    // matrix runs on Android 16 (API 36) without hitting the
    // `InputManager.getInstance NoSuchMethodException` from Espresso
    // 3.5's hidden-API reflection. The Compose BOM resolves Espresso
    // transitively to 3.5.0; this explicit dependency overrides that
    // resolution so even Compose `composeRule.waitUntil { … }` calls
    // (which delegate to Espresso.onIdle()) work on API 36.
    // See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.security.ktx)
    // C44 (Challenge44ApiSearchAuthTest) runs an on-device MockWebServer
    // inside the instrumented test process to stand in for the auth-gated
    // lava-api-go / on-device api-app deterministically (the real backend is
    // an e2e flow tracked separately). Same boundary the unit
    // ApiBackedTrackerClientTest fakes; here on a real device.
    androidTestImplementation(libs.okhttp.mockwebserver)
    // C43 (Challenge43ServerListNoDuplicateTest) builds a REAL in-memory
    // lava.database.AppDatabase on-device via Room.inMemoryDatabaseBuilder
    // to drive the real EndpointsRepositoryImpl de-dup path. :core:database
    // keeps room-runtime as `implementation`, so the Room entry point is not
    // on the app androidTest classpath transitively — declare it here.
    androidTestImplementation(libs.room.runtime)
    // C47 (Challenge47CredentialsLockedSearchSurvivesTest) reaches the
    // production CredentialsKeyHolder singleton via an @EntryPoint to force
    // the locked state. :feature:credentials_manager and :feature:credentials
    // both depend on :core:credentials via `implementation` (not `api`), so
    // CredentialsKeyHolder is NOT on the app androidTest classpath transitively
    // — declare it here using the same pattern as room.runtime above.
    androidTestImplementation(project(":core:credentials"))
    // C53 (Challenge53CredentialEditDialogSavePersistsTest) renders the real
    // CredentialsScreen backed by a real CredentialsViewModel whose SDK is wired
    // with FakeTrackerClient (the outermost boundary — the same construction the
    // unit CredentialsViewModelTest uses). FakeTrackerClient lives in
    // :core:tracker:testing, which :core:tracker:client only pulls as
    // testImplementation, so it is NOT on the app androidTest classpath
    // transitively — declare it here (same pattern as room.runtime / :core:credentials above).
    androidTestImplementation(project(":core:tracker:testing"))
    kspAndroidTest(libs.hilt.compiler)
}
