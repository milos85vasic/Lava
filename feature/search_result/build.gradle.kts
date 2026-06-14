plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.search.result"
}

dependencies {
    implementation(project(":core:network:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":core:tracker:api"))
    // okhttp.core is needed on the (test) classpath because LavaTrackerSdk's
    // transitive type graph references okhttp3.OkHttpClient — the
    // SearchResultViewModelFallbackTest constructs a real SDK.
    implementation(libs.okhttp.core)
    implementation(libs.androidx.compose.material3)

    // SP-4 Phase F.1+D: LavaTrackerSdk's constructor declares a
    // `clonedProviderDao: ClonedProviderDao?` parameter; Kotlin's
    // compiler needs the type accessible in the test classpath even
    // when callers use the default null value.
    testImplementation(project(":core:database"))

    // SearchResultNavigationProviderIdsRoundtripTest (Bug-2-Layer-3 / §6.N
    // bluff-hunt 2026-06-13) runs under Robolectric so the REAL
    // android.net.Uri.encode (openSearchResult encode side) + Uri.decode
    // (Navigation-Compose decode side) execute on the JVM unit-test path.
    // navigation-compose provides NavHostController for the route-capturing
    // fake NavigationController. Mirrors feature/search_input's setup.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.compose)
}
