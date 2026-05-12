plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.provider.config"
}

dependencies {
    implementation(project(":core:credentials"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:sync"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":feature:credentials_manager"))

    implementation(libs.androidx.compose.material3)
}
