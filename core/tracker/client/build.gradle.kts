plugins {
    id("lava.android.library")
    id("lava.android.hilt")
    id("lava.kotlin.serialization")
}

android {
    namespace = "lava.tracker.client"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:tracker:api"))
    api(project(":core:tracker:registry"))
    api(project(":core:tracker:mirror"))
    api(project(":core:tracker:rutracker"))
    api(project(":core:tracker:rutor"))

    implementation(project(":core:auth:api"))
    implementation(project(":core:database"))

    implementation(libs.javax.inject)
    implementation(libs.bundles.work)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.bundles.room)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(project(":core:tracker:testing"))
}
