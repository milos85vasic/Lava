plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.auth"
}

dependencies {
    api(project(":core:auth:api"))

    implementation(project(":core:common"))
    implementation(project(":core:dispatchers"))
    implementation(project(":core:models"))
    implementation(project(":core:network:api"))
    implementation(project(":core:preferences"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
