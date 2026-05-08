plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.feature.credentials"
}

dependencies {
    implementation(project(":core:credentials"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:logger"))
    implementation(project(":core:navigation"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:tracker:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.bundles.room)
    testImplementation(libs.orbit.test)
    testImplementation(libs.ktor.client.okhttp)
}
