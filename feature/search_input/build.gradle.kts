plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.search.input"
}

dependencies {
    implementation(libs.androidx.compose.material3)
}
