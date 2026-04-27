plugins {
    id("lava.ktor.application")
}

group = "digital.vasic.lava.api"
version = "1.0.0"

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
}
