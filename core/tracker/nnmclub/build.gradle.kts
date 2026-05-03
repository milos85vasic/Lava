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
    description = "Real-tracker integration tests against nnmclub.to. Operator-driven."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnit()
    onlyIf {
        project.hasProperty("realTrackers") && project.property("realTrackers") == "true"
    }
}
