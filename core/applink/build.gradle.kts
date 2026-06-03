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
}
