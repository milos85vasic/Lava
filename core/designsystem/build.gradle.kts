plugins {
    id("lava.android.library")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.designsystem"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    implementation(project(":core:models"))

    api(libs.androidx.activity.compose)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.foundation.layout)
    api(libs.androidx.compose.foundation.tv)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.ui.util)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.lifecycle.runtime)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.compose.material.tv)
    implementation(libs.material3)

    debugApi(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test)
    // ui-test-manifest contributes the ComponentActivity host-activity declaration that
    // createComposeRule() launches via Robolectric's ActivityScenario. Without it on the
    // unit-test classpath of BOTH variants, the release variant's merged test manifest
    // omits ComponentActivity (the ui-tooling that declares it is debugApi-scoped), so
    // Robolectric throws RuntimeException("Unable to resolve activity for ...") at
    // RoboMonitoringInstrumentation.java:101. See A11yContentDescriptionTest for details.
    testImplementation(libs.androidx.compose.ui.testManifest)
    testImplementation(project(":core:testing"))
}
