plugins {
    id("lava.android.library")
    id("lava.android.hilt")
    id("lava.kotlin.serialization")
}

android {
    namespace = "lava.network"
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlin.io.encoding.ExperimentalEncodingApi",
        )
    }
}

dependencies {
    api(project(":core:network:api"))

    implementation(project(":core:auth:api"))
    implementation(project(":core:data"))
    implementation(project(":core:dispatchers"))
    implementation(project(":core:logger"))
    implementation(project(":core:models"))
    implementation(project(":core:network:rutracker"))

    implementation(libs.coil.kt)

    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.kotlinx.json)

    debugImplementation(libs.chucker)
}
