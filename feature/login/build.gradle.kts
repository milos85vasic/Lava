plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.login"

    // Phase 5 (2026-06-11): ProviderLoginAuthUiTest renders the real Compose
    // auth form under Robolectric and asserts node presence — needs merged
    // Android resources on the unit-test classpath.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:auth:api"))
    implementation(project(":core:credentials"))
    implementation(project(":core:logger"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(project(":core:tracker:testing"))
    testImplementation(project(":core:testing"))
    testImplementation(project(":core:domain"))
    testImplementation(libs.orbit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.bundles.room)
    testImplementation(libs.ktor.client.okhttp)
    // Phase 5 (2026-06-11): Robolectric Compose UI test (ProviderLoginAuthUiTest).
    // ui-test-manifest contributes the ComponentActivity host so createComposeRule
    // resolves its host activity in BOTH debug + release unit-test variants —
    // mirrors core/designsystem/build.gradle.kts (A11yContentDescriptionTest).
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.testManifest)
}
