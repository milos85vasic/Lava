package lava.tracker.testing

import lava.sdk.testing.HtmlFixtureLoader

/** Wraps the SDK's loader for Lava-side test resource paths under fixtures/<tracker>/<scope>/<file>. */
class LavaFixtureLoader(private val tracker: String) {
    private val sdkLoader = HtmlFixtureLoader(resourceRoot = "fixtures/$tracker")
    fun load(scope: String, fileName: String): String = sdkLoader.load("$scope/$fileName")
}
