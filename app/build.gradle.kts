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

android {
    namespace = "digital.vasic.lava.client"

    defaultConfig {
        applicationId = "digital.vasic.lava.client"
        versionCode = 1049
        versionName = "1.2.29"
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

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)

    debugImplementation(libs.leakcanary)

    // JVM unit tests for app-internal helpers (FirebaseInitializer post-§6.O)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)

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
    kspAndroidTest(libs.hilt.compiler)
}
