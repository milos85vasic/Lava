plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    api(project(":core:tracker:registry"))
    implementation(libs.javax.inject)
    testImplementation(libs.okhttp.mockwebserver)
}

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
    description = "Real-library integration tests against gutenberg.org. Operator-driven."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnit()
    onlyIf {
        project.hasProperty("realTrackers") && project.property("realTrackers") == "true"
    }
}

// CROWN-JEWEL real-network gate: forward `-PrealTrackers=true` into the `test`
// JVM as a system property so GutenbergRealNetworkDownloadTest can read it via
// RealTrackerTestSupport.realTrackersEnabled(). Without the property the test
// `assumeTrue`-SKIPs and makes no outbound calls (suite gated off by default).
// gutenberg is anonymous, so no credentials are needed (§6.R: nothing hardcoded;
// the descriptor base URL is the only host literal, and it lives in production
// code, not the test). `:core:tracker:testing` is already a testImplementation
// via the tracker-module convention plugin.
tasks.named<Test>("test") {
    systemProperty("realTrackers", project.findProperty("realTrackers")?.toString() ?: "false")
    // Surface the honest SKIP reason (the assumeTrue message) so an unreachable
    // crown-jewel run is auditable in the gradle output, not silent.
    testLogging {
        events("skipped", "failed", "passed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
