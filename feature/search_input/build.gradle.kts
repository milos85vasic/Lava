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

    // SearchInputNavigationRoundtripTest (LVA-048 / LVA-049) runs under Robolectric
    // so the REAL android.net.Uri.encode (production encode side) and Uri.decode
    // (Navigation-Compose decode side) both execute on the JVM unit-test path.
    // navigation-compose provides NavHostController for the route-capturing fake
    // NavigationController.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.compose)
}
