plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.credentials"
}

dependencies {
    api(project(":core:database"))
    api(project(":core:tracker:api"))

    implementation(project(":core:common"))
    implementation(project(":core:dispatchers"))
    implementation(project(":core:logger"))
    implementation(project(":core:models"))
    implementation(project(":core:preferences"))

    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.bundles.room)
}
