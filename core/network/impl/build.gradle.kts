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
    // SP-3a-2.33: SwitchingNetworkApi delegates to LavaTrackerSdk on the
    // direct-rutracker path. tracker:client transitively re-exports
    // tracker:api / tracker:registry / tracker:rutracker, so the existing
    // explicit `:core:tracker:rutracker` line still works. Verified no
    // circular dep: tracker:client depends only on :core:auth:api,
    // :core:tracker:api, :core:tracker:registry, :core:tracker:mirror,
    // :core:tracker:rutracker — none transitively pulls :core:network:impl.
    implementation(project(":core:tracker:client"))
    implementation(project(":core:tracker:rutracker"))

    implementation(libs.coil.kt)

    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.kotlinx.json)

    debugImplementation(libs.chucker)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:tracker:testing"))
}
