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
        versionCode = 1
        versionName = "0.1.0"
        // EncryptedSharedPreferences (androidx.security-crypto) requires API 23+
        // — the per-install auth-key store ([ApiKeyStore]) relies on it. The
        // standalone API-server app reasonably targets API 23+ (the client app
        // keeps minSdk 21).
        minSdk = 23
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
    implementation(project(":core:apiengine"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:notifications"))

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
}
