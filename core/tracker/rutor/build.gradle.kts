plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    // @Inject annotation surface for feature classes wired in :core:tracker:client (Section J).
    implementation(libs.javax.inject)

    // OkHttp + kotlinx-coroutines come from the convention plugin transitively;
    // additional rutor-specific http / mockwebserver wiring is added in Tasks 3.7 / 3.9.
}
