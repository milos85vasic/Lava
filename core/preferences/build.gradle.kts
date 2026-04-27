plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.securestorage"
}

dependencies {
    implementation(project(":core:dispatchers"))
    implementation(project(":core:models"))

    implementation(libs.androidx.security.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.json)
}
