plugins {
    id("lava.ktor.application")
}

group = "lava.proxy.rutracker"
version = "1.0.0"

application {
    mainClass.set("lava.proxy.rutracker.ApplicationKt")
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
    implementation(libs.jsoup)
    implementation(libs.koin)
    implementation(project(":core:network:rutracker"))
}
