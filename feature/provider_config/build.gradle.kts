plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.provider.config"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:credentials"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:sync"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":feature:credentials_manager"))

    implementation(libs.androidx.compose.material3)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:tracker:testing"))
    testImplementation(project(":core:tracker:registry"))
    testImplementation(libs.orbit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.bundles.room)
    testImplementation(libs.okhttp.core)
}
