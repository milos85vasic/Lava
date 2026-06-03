package lava.applink

/** Pure decision — no Android side effects, so it is fully unit-testable. */
sealed interface LaunchDecision {
    data class Launch(
        val targetPackage: String,
        val extras: Map<String, String>,
    ) : LaunchDecision

    data class StoreRedirect(
        val marketUri: String,
        val webUri: String,
    ) : LaunchDecision
}

class CrossAppLauncher(private val checker: PackageChecker) {
    /**
     * @param targetPackage variant-aware package to launch (e.g. ….api.dev on debug)
     * @param releasePackage the Play-Store listing id (always the release id)
     */
    fun decideLaunch(
        targetPackage: String,
        releasePackage: String,
        extras: Map<String, String>,
    ): LaunchDecision =
        if (checker.isInstalled(targetPackage)) {
            LaunchDecision.Launch(targetPackage, extras)
        } else {
            LaunchDecision.StoreRedirect(
                marketUri = AppLinkContract.marketUri(releasePackage),
                webUri = AppLinkContract.playWebUri(releasePackage),
            )
        }
}
