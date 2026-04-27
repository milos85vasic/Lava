plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.main"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
