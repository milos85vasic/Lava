plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.menu"

    testOptions {
        unitTests {
            // Sub-project 2: ApiAppLauncherTest uses real Intent/Uri via
            // Robolectric (RobolectricTestRunner) rather than default-value
            // stubs, so its assertions exercise the real Intent the OS receives.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:auth:api"))
    implementation(project(":core:credentials"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":feature:account"))
    implementation(project(":feature:connection"))

    testImplementation(libs.okhttp.core)
    // Sub-project 2: real Intent/Uri behavior in ApiAppLauncherTest.
    testImplementation(libs.robolectric)
}
