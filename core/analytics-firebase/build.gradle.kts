// :core:analytics-firebase — the shared Firebase-backed implementation of the
// project-wide lava.common.analytics.AnalyticsTracker contract.
//
// Decoupled Reusable Architecture (constitutional): the Firebase impl + DI
// module + resilient initializer were app-private inside :app. Both :app and
// :api-app need Crashlytics/Analytics/Performance wiring (§6.AC / §6.O), and
// copy-pasting the impl between the two apps is the canonical bluff vector the
// rule forbids (behaviour drifts, fixes don't propagate). This module is the
// single source of truth both apps consume.
//
// The google-services + crashlytics GRADLE plugins are NOT applied here — those
// process each app's google-services.json and upload the per-app mapping file,
// so they belong in the application modules (:app, :api-app) where they are
// already inherited via lava.android.application. This library only needs the
// Firebase runtime artifacts on the classpath; it is app-agnostic (no
// BuildConfig reference) — FirebaseInitializer.initialize(...) takes
// applicationId/versionName/versionCode as parameters.

plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.analytics.firebase"

    testOptions {
        // The Firebase impl + DI tests call android.util.Log / android.os.Bundle
        // (e.g. FirebaseAnalyticsTracker's runCatching warn logs, the no-op
        // fallback's Log.d). On the plain JVM those Android stubs throw
        // "not mocked" RuntimeExceptions unless default-values are returned —
        // the SAME setting :app uses, carried over with the moved tests so they
        // stay behaviorally identical (no bluff drift).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The AnalyticsTracker contract this module implements.
    api(project(":core:common"))

    // Firebase runtime artifacts. Exposed via `api` so consuming application
    // modules (:app, :api-app) get the ktx accessors (Firebase.crashlytics /
    // Firebase.analytics / Firebase.performance) transitively — the Application
    // classes call FirebaseInitializer.initialize(...) with those accessors.
    api(platform(libs.firebase.bom))
    api(libs.firebase.analytics)
    api(libs.firebase.crashlytics)
    api(libs.firebase.perf)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
