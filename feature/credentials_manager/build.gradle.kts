plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.credentials.manager"
}

dependencies {
    implementation(project(":core:credentials"))

    implementation(libs.androidx.compose.material3)
}
