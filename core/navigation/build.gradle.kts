plugins {
    id("lava.android.library")
    id("lava.android.library.compose")
    id("kotlin-parcelize")
}

android {
    namespace = "lava.navigation"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-receivers")
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:logger"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewModel.savedState)
}
