plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.menu"

    testOptions {
        unitTests {
            // Sub-project 2: SiblingAppLauncherMenuTest uses real Intent/Uri via
            // Robolectric rather than default-value stubs, so its assertions
            // exercise the real Intent the OS receives.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Sub-project 2: SiblingAppLauncher (replaces the former feature-local ApiAppLauncher).
    implementation(project(":core:applink"))
    implementation(project(":core:auth:api"))
    implementation(project(":core:credentials"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":feature:account"))
    implementation(project(":feature:connection"))

    testImplementation(libs.okhttp.core)
    // Sub-project 2: real Intent/Uri behavior in SiblingAppLauncherMenuTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
}
