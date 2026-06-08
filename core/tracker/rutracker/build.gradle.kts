plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    // Legacy DTOs and NetworkApi interface, transitional dep removed in Spec 2.
    api(project(":core:network:api"))

    // TokenProvider for auth-bearing UseCase calls.
    api(project(":core:auth:api"))

    // RuTrackerClientFactory implements lava.tracker.registry.TrackerClientFactory
    // (Section F, Task 2.28). Tracker:registry is a pure-Kotlin module re-exporting
    // lava.sdk:registry; adding it here keeps the SDK seam visible to consumers.
    api(project(":core:tracker:registry"))

    // @Inject annotation surface for feature classes wired in :core:tracker:client (Section F).
    implementation(libs.javax.inject)

    implementation(libs.ktor.client.core)
    // SP-4 Phase F.2.6 (2026-05-13): RuTrackerHttpClientFactory + RuTrackerSubgraphBuilder
    // construct per-clone HttpClient instances pinned to the clone's primaryUrl.
    // The plugins used are the same set the Hilt-singleton path uses in
    // :core:tracker:client:di:TrackerClientModule.provideRuTrackerHttpClient.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // MockWebServer for the F.2.6 falsifiability-rehearsal Challenge Test.
    testImplementation(libs.okhttp.mockwebserver)
    // Jsoup is brought in by the convention plugin; do not re-declare.

    // CROWN-JEWEL real-network suite support (RealTrackerHarness + validators).
    testImplementation(project(":core:tracker:testing"))
}

// CROWN-JEWEL real-network gate: forward `-PrealTrackers=true` into the `test`
// JVM as a system property so RuTrackerRealNetworkDownloadTest can read it via
// RealTrackerTestSupport.realTrackersEnabled(). Without the property the test
// `assumeTrue`-SKIPs and makes no outbound calls (suite gated off by default).
// The forked test JVM inherits the host environment, so the real credentials in
// the gitignored `.env` (RUTRACKER_USERNAME/PASSWORD) reach System.getenv when
// the operator has exported them — never hardcoded (§6.R).
tasks.named<Test>("test") {
    systemProperty("realTrackers", project.findProperty("realTrackers")?.toString() ?: "false")
    // Surface the honest SKIP reason (the assumeTrue message) so an unreachable /
    // login-failed crown-jewel run is auditable in the gradle output, not silent.
    testLogging {
        events("skipped", "failed", "passed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
