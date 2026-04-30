plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.search.result"
}

dependencies {
    implementation(project(":core:tracker:client"))
    implementation(project(":core:tracker:api"))
    implementation(libs.androidx.compose.material3)
}
