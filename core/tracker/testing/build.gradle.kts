plugins {
    id("lava.kotlin.library")
}

dependencies {
    api(project(":core:tracker:api"))
    api("lava.sdk:testing")
    api(libs.junit4)

    // Real-network crown-jewel integration-test support (RealTrackerTestSupport):
    // the torrent/magnet validators live in :core:common, and the reachability
    // probe + .torrent download use OkHttp. These are api-scoped so the per-tracker
    // src/test crown-jewel tests can construct a RealTrackerHarness directly.
    api(project(":core:common"))
    api(libs.okhttp.core)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
