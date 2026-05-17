plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.search.input"
}

dependencies {
    implementation(libs.androidx.compose.material3)
    // Bug 3 fix (2026-05-17): SearchInputViewModel reads onboarded
    // providers from ProviderConfigRepository so the chip-bar default
    // matches the user's actual configuration instead of pre-selecting
    // all 4 hard-coded providers.
    implementation(project(":core:credentials"))
}
