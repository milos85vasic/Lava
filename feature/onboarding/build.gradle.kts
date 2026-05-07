plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.onboarding"
}

dependencies {
    implementation(project(":core:auth:api"))
    implementation(project(":core:credentials"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)

    testImplementation(project(":core:testing"))
    testImplementation(libs.orbit.test)
}
