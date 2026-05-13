package lava.tracker.rutracker

import lava.auth.api.TokenProvider
import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.registry.cloneBaseUrlOverride
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the RuTracker plugin.
 *
 * Hilt instantiates this and the [Provider]<[RuTrackerClient]> it holds; the
 * default [create] call unwraps the provider so original-tracker callers
 * get the singleton [RuTrackerClient] wired by
 * `:core:tracker:client:di:TrackerClientModule`.
 *
 * SP-4 Phase F.2.6 (2026-05-13): when [PluginConfig.cloneBaseUrlOverride]
 * is non-null (set by `LavaTrackerSdk.clientFor` for a cloned provider),
 * [create] returns a PER-CALL [RuTrackerClient] whose entire subgraph —
 * Ktor [io.ktor.client.HttpClient] → [RuTrackerInnerApi] → 14 use cases
 * → 8 mappers → 7 feature impls — is pinned to the clone's `primaryUrl`.
 * The clone's HttpClient is constructed by [RuTrackerHttpClientFactory];
 * the subgraph is assembled by [RuTrackerSubgraphBuilder]. Both helpers
 * mirror the Hilt-singleton wiring 1:1, so the only behavioral
 * difference between original-path and clone-path is the base URL.
 *
 * Acceptance gate (Phase B clone-success Toast drops "URL routing
 * pending" disclosure): satisfied when this factory's clone-branch is
 * reachable and its HttpClient hits the clone's primaryUrl — verified
 * by [RuTrackerClientFactoryCloneUrlTest] against a MockWebServer.
 */
class RuTrackerClientFactory @Inject constructor(
    private val clientProvider: Provider<RuTrackerClient>,
    // Provider<TokenProvider> instead of TokenProvider directly to break
    // a Hilt construction-time cycle introduced by F.2.6: the per-clone
    // subgraph needs TokenProvider, but the singleton-graph chain is
    //   RuTrackerClientFactory → TokenProvider → AuthServiceImpl →
    //   NetworkApi → SwitchingNetworkApi → LavaTrackerSdk →
    //   TrackerRegistry → RuTrackerClientFactory  ← CYCLE
    // Provider<T> defers resolution to the call site (only `create()`
    // with override branch ever calls .get()), which Hilt resolves
    // because original-tracker construction does NOT touch
    // TokenProvider. This is the canonical Hilt cycle-breaker pattern
    // and aligns with how `clientProvider` already works for the
    // singleton path.
    //
    // Discovered 2026-05-13 by APK build under operator's 24th §6.L
    // invocation: JVM unit tests (tests=877 failures=0 errors=0)
    // PASSED while `./gradlew :app:hiltJavaCompileDebug` FAILED with
    // [Dagger/DependencyCycle]. This is the canonical "tests green,
    // feature broken" failure mode §6.L exists to prevent. Forensic
    // anchor: see commit body's Bluff-Audit stamp.
    private val tokenProvider: Provider<TokenProvider>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = RuTrackerDescriptor

    override fun create(config: PluginConfig): TrackerClient {
        val override = config.cloneBaseUrlOverride
        if (override != null) {
            val baseUrl = override.trimEnd('/') + "/forum/"
            val cloneHttp = RuTrackerHttpClientFactory.create(baseUrl)
            return RuTrackerSubgraphBuilder.build(cloneHttp, tokenProvider.get())
        }
        return clientProvider.get()
    }
}
