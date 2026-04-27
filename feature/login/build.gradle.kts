plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.login"
}

dependencies {
    implementation(project(":core:auth:api"))
    implementation(project(":core:logger"))
}
