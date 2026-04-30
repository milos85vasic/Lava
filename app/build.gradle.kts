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
        versionCode = 1020
        versionName = "1.2.0"
        // SP-3a Step 6 (2026-04-30): wire Hilt + Compose UI test infra so the
        // 8 Challenge Tests at app/src/androidTest/kotlin/lava/app/challenges/
        // become runnable on a connected device. The custom runner installs
        // HiltTestApplication as the test Application; without it
        // @HiltAndroidTest classes cannot inject.
        testInstrumentationRunner = "lava.app.LavaHiltTestRunner"
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":feature:main"))
    implementation(project(":feature:menu"))
    implementation(project(":feature:rating"))
    implementation(project(":feature:search"))
    implementation(project(":feature:search_input"))
    implementation(project(":feature:search_result"))
    implementation(project(":feature:topic"))
    implementation(project(":feature:tracker_settings"))
    implementation(project(":feature:visited"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.bundles.orbit)
    implementation(libs.bundles.work)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    debugImplementation(libs.leakcanary)

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
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
