plugins {
    id("lava.ktor.application")
}

// Managed by scripts/tag.sh — do not edit manually.
val apiVersionName = "1.0.1"
val apiVersionCode = 1001

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
    implementation(project(":core:network:rutracker"))

    testImplementation(libs.junit4)
}
