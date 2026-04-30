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

// SP-3a Task 3.41 — Real-tracker integration test source set.
//
// `integrationTest` is a separate source set that compiles against everything
// `test` sees and reuses the same JUnit4 runtime. Tests under it hit real
// rutor.info over the network and are SKIPPED by default. To run them:
//
//   ./gradlew :core:tracker:rutor:integrationTest -PrealTrackers=true
//
// Without the property, the task short-circuits with onlyIf=false. CI never
// runs them automatically (per Local-Only CI/CD constitutional rule §3,
// real-tracker tests are operator-driven, not gate-driven).
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
    description = "Real-tracker integration tests against rutor.info. Operator-driven."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnit()
    onlyIf {
        project.hasProperty("realTrackers") && project.property("realTrackers") == "true"
    }
}
