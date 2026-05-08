plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.login"
}

dependencies {
    implementation(project(":core:auth:api"))
    implementation(project(":core:credentials"))
    implementation(project(":core:logger"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(project(":core:tracker:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.bundles.room)
    testImplementation(libs.ktor.client.okhttp)
}
