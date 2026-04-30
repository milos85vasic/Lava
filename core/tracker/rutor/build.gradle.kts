plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    // RuTorClientFactory implements lava.tracker.registry.TrackerClientFactory
    // (SP-3a Task 3.40, Section J). Tracker:registry is a pure-Kotlin module
    // re-exporting lava.sdk:registry; declaring it here keeps the SDK seam
    // visible to consumers and lets RuTorClientFactory.kt compile.
    api(project(":core:tracker:registry"))

    // @Inject annotation surface for feature classes wired in :core:tracker:client (Section J).
    implementation(libs.javax.inject)

    // MockWebServer for the http/RuTorHttpClient tests (Tasks 3.9 / 3.10) and
    // the SP-3a Task 3.36-3.39 feature tests (each feature is exercised via a
    // MockWebServer-backed integration challenge).
    testImplementation(libs.okhttp.mockwebserver)
}
