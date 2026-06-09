plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.data"
}

dependencies {
    implementation(project(":core:auth:api"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:dispatchers"))
    implementation(project(":core:logger"))
    implementation(project(":core:models"))
    implementation(project(":core:network:api"))
    implementation(project(":core:preferences"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.bundles.room)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
