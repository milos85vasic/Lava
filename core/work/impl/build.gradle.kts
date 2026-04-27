plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.work"
}

dependencies {
    api(project(":core:work:api"))

    implementation(project(":core:domain"))
    implementation(project(":core:models"))
    implementation(project(":core:notifications"))

    implementation(libs.bundles.work)
}
