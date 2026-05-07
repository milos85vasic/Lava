plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.search.result"
}

dependencies {
    implementation(project(":core:network:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":core:tracker:api"))
    implementation(libs.okhttp.core)
    implementation(libs.androidx.compose.material3)
}
