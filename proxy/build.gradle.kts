plugins {
    id("lava.ktor.application")
}

// Managed by scripts/tag.sh — do not edit manually.
val apiVersionName = "1.0.4"
val apiVersionCode = 1004

group = "digital.vasic.lava.api"
version = apiVersionName

application {
    mainClass.set("digital.vasic.lava.api.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("app.jar")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlin.io.encoding.ExperimentalEncodingApi",
        )
    }
}

dependencies {
    implementation(libs.bundles.ktor)
    implementation(libs.jmdns)
    implementation(libs.jsoup)
    implementation(libs.koin)
    implementation(project(":core:tracker:rutracker"))

    testImplementation(libs.junit4)
}

// Plumb apiVersionName into the test JVM so ServiceAdvertisementTest's
// `API_VERSION constant tracks proxy apiVersionName` regression test can
// assert ServiceAdvertisement.API_VERSION matches the gradle-side declaration
// without duplicating the literal string. Added 2026-05-05 (10th anti-bluff
// invocation, Phase R6) after the 1.0.1→1.0.4 silent drift was exposed.
tasks.withType<Test>().configureEach {
    systemProperty("LAVA_PROXY_API_VERSION_NAME", apiVersionName)
}
