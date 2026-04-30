plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    // @Inject annotation surface for feature classes wired in :core:tracker:client (Section J).
    implementation(libs.javax.inject)

    // MockWebServer for the http/RuTorHttpClient tests (Tasks 3.9 / 3.10).
    // OkHttp itself, kotlinx-coroutines, and JUnit4 are provided by the
    // lava.kotlin.tracker.module convention plugin.
    testImplementation(libs.okhttp.mockwebserver)
}
