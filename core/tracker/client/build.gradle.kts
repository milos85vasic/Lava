plugins {
    id("lava.android.library")
    id("lava.android.hilt")
    id("lava.kotlin.serialization")
}

android {
    namespace = "lava.tracker.client"
}

dependencies {
    api(project(":core:tracker:api"))
    api(project(":core:tracker:registry"))
    api(project(":core:tracker:mirror"))
    api(project(":core:tracker:rutracker"))
    api(project(":core:tracker:rutor"))

    implementation(project(":core:auth:api"))

    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(project(":core:tracker:testing"))
}
