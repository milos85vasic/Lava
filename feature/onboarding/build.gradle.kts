plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.onboarding"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Task 3.2 (2026-06-03): shared cross-app linking contract + launcher
    // (AppLinkContract, CrossAppLauncher, LaunchDecision, PackageChecker).
    implementation(project(":core:applink"))
    implementation(project(":core:auth:api"))
    implementation(project(":core:credentials"))
    // Sweep Finding #8 (2026-05-17, §6.L 59th): onboarding needs the
    // ClonedProviderDao to identify which trackerIds are synthetic clones
    // so the onboarding wizard does not show them (clones are advanced
    // post-onboarding via Provider Config, not first-run).
    implementation(project(":core:database"))
    // 60th §6.L invocation (2026-05-18): ApiSelection step consumes
    // LocalNetworkDiscoveryService + ConnectionService + EndpointsRepository
    // for mDNS discovery + connectivity probe + persist on success.
    implementation(project(":core:data"))
    implementation(project(":core:models"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:tracker:testing"))
    testImplementation(project(":core:tracker:registry"))
    testImplementation(libs.orbit.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.core)
    // Task 3.3 (2026-06-03): MockWebServer for real probe in OnDeviceApiFlowTest.
    testImplementation(libs.okhttp.mockwebserver)
}
