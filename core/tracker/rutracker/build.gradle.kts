plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    // Keep transitional dependency on :core:network:api so the existing
    // RuTrackerInnerApi (legacy HTTP wire) still compiles. Removed in
    // Spec 2 when :proxy retires.
    api(project(":core:network:api"))

    implementation(libs.ktor.client.core)
    // Jsoup is brought in by the convention plugin; do not re-declare.
}
