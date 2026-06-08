plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    // IPTorrentsClientFactory implements lava.tracker.registry.TrackerClientFactory
    // (same Section-J pattern as RuTor/Kinozal). Tracker:registry is a pure-Kotlin
    // module re-exporting lava.sdk:registry; declaring it here keeps the SDK seam
    // visible to consumers and lets IPTorrentsClientFactory.kt compile.
    api(project(":core:tracker:registry"))

    // @Inject annotation surface for feature classes wired in :core:tracker:client.
    implementation(libs.javax.inject)

    // MockWebServer backs the delegation/mapping test: it serves the EXACT JSON
    // wire format lava-api-go's GET /jackett/search route emits (a
    // provider.SearchResult), so the IPTorrents client's real HTTP + JSON parse +
    // domain-mapping path is exercised end-to-end with only the socket faked.
    testImplementation(libs.okhttp.mockwebserver)
}

// Real-stack §6.G integration test source set (mirrors the rutor module).
//
// `integrationTest` compiles against everything `test` sees. Tests under it hit
// a RUNNING lava-api-go sidecar (the Jackett/FlareSolverr local stack) over the
// network and are SKIPPED by default. To run them, with the sidecar up:
//
//   ./gradlew :core:tracker:iptorrents:integrationTest -PrealTrackers=true \
//     -DiptorrentsJackettBaseUrl=https://localhost:8443
//
// Without the property the task short-circuits with onlyIf=false. CI never runs
// them automatically (Local-Only CI/CD §3 — real-stack tests are operator-driven).
sourceSets {
    create("integrationTest") {
        kotlin.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

configurations {
    named("integrationTestImplementation") { extendsFrom(configurations["testImplementation"]) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations["testRuntimeOnly"]) }
}

tasks.register<Test>("integrationTest") {
    description = "Real-stack §6.G test against a running lava-api-go /jackett/search sidecar. Operator-driven."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnit()
    onlyIf {
        project.hasProperty("realTrackers") && project.property("realTrackers") == "true"
    }
}

// Forward `-PrealTrackers=true` into the `test` JVM so the §6.G real-stack test
// under src/test (the sidecar-presence-gated one) can read it via
// RealTrackerTestSupport.realTrackersEnabled(). Without it the test assumeTrue-SKIPs
// and makes no outbound calls. The base URL of the local sidecar is NEVER hardcoded
// (§6.R): it comes from -DiptorrentsJackettBaseUrl / IPTORRENTS_JACKETT_BASE_URL env.
tasks.named<Test>("test") {
    systemProperty("realTrackers", project.findProperty("realTrackers")?.toString() ?: "false")
    System.getProperty("iptorrentsJackettBaseUrl")?.let { systemProperty("iptorrentsJackettBaseUrl", it) }
    testLogging {
        events("skipped", "failed", "passed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
