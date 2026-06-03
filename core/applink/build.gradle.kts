plugins {
    id("lava.android.library")
}

android {
    namespace = "lava.applink"
    defaultConfig {
        // Release package ids (used for Play-Store fallback, which always
        // targets the release listing — debug .dev builds are side-loaded).
        buildConfigField("String", "CLIENT_RELEASE_PACKAGE", "\"digital.vasic.lava.client\"")
        buildConfigField("String", "API_RELEASE_PACKAGE", "\"digital.vasic.lava.api\"")
    }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests {
            // SiblingAppLauncherTest uses real Intent/Uri via Robolectric.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    testImplementation(libs.junit4)
    // SiblingAppLauncherTest: real Intent/Uri behavior (Robolectric) + fake PackageManager (mockk).
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
}
