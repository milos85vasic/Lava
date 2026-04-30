plugins {
    id("lava.kotlin.tracker.module")
}

dependencies {
    // Legacy DTOs and NetworkApi interface, transitional dep removed in Spec 2.
    api(project(":core:network:api"))

    // TokenProvider for auth-bearing UseCase calls.
    api(project(":core:auth:api"))

    // @Inject annotation surface for feature classes wired in :core:tracker:client (Section F).
    implementation(libs.javax.inject)

    implementation(libs.ktor.client.core)
    // Jsoup is brought in by the convention plugin; do not re-declare.
}
