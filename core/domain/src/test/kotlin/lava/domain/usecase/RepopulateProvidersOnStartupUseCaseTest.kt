package lava.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.data.provider.InMemoryProviderCatalogStore
import lava.data.provider.ProviderCatalogRepository
import lava.models.settings.Endpoint
import lava.network.sse.SseBaseUrlBuilder
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.registry.PluginFactory
import lava.testing.repository.TestSettingsRepository
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.client.ApiBaseUrlHolder
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.testing.FakeTrackerClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Provider-availability restore on cold start — the load-bearing anti-bluff
 * proof for the operator's 2026-06-13 directive ("we used to have only 4
 * providers — ALL new providers MUST be available app-wide; cover with tests,
 * no false results / no bluff").
 *
 * ## What this simulates (the COLD-START defect)
 *
 * The dynamic `GET /providers` catalogue (5 native + 7 curated = 12 providers)
 * is loaded into the [DefaultTrackerRegistry] only by the onboarding flow's
 * in-session populate. After a process restart — NO onboarding re-run — the
 * registry has reverted to the BUNDLED set, and Settings / search / onboarding
 * re-entry read `sdk.listAvailableTrackers()` with no re-fetch. The user who
 * configured a lava-api-go instance then sees only the bundled providers.
 *
 * This test reproduces exactly that state: a FRESH registry seeded with only
 * the bundled providers (NOT pre-populated with the dynamic 12), a persisted
 * active [Endpoint.GoApi] (the choice the user made during onboarding), and the
 * REAL [RepopulateProvidersOnStartupUseCase] running its real
 * [FetchProvidersUseCase] → [ProviderCatalogRepository] over a [MockWebServer]
 * serving the full 12-provider `/providers` catalogue.
 *
 * ## Anti-Bluff posture (§6.J / Second + Sixth Laws)
 *
 *  - The SUT is the REAL [RepopulateProvidersOnStartupUseCase] wired to the REAL
 *    [FetchProvidersUseCase] + REAL [ProviderCatalogRepository] (real
 *    [OkHttpClient] + real [InMemoryProviderCatalogStore]) + REAL
 *    [DefaultTrackerRegistry.populateFrom] + REAL [LavaTrackerSdk]. NONE of these
 *    are mocked. Only the network socket ([MockWebServer]) is faked — the
 *    outermost boundary the Anti-Bluff Pact permits.
 *  - The PRIMARY assertion is on user-visible state: `sdk.listAvailableTrackers()`
 *    — the exact list `MenuViewModel.loadProviders()` and `ProviderConfigViewModel`
 *    read to render Settings. It asserts the FULL COUNT (all 12) AND the presence
 *    of the curated ids, NOT a weak `>= 1`.
 *
 * ## FALSIFIABILITY REHEARSAL (§6.J clause 2 / Sixth Law clause 2)
 *
 *   Mutation A (the real defect — the seam that closes the gap): in
 *     [RepopulateProvidersOnStartupUseCase.invoke], delete the
 *     `trackerRegistry.populateFrom(descriptors)` call on the success path.
 *   Observed: `cold start with a persisted GoApi endpoint repopulates ALL 12
 *     providers app-wide` FAILS — `sdk.listAvailableTrackers()` still has only
 *     the 4 bundled ids (assertEquals expected 12 actual 4; the curated-id
 *     assertTrue trips on "thepiratebay … MUST appear").  Reverted: yes.
 *   Mutation B: in the use case, change `as? Endpoint.GoApi` handling to always
 *     `return false` before the fetch (i.e. never repopulate).
 *   Observed: same failure — the list stays at the 4 bundled ids. Reverted: yes.
 *   Mutation C (graceful-degradation guard): make [ProviderCatalogRepository]
 *     return success even on HTTP 500. The `unreachable API keeps the bundled
 *     providers` test then FAILS because the registry would be wrongly cleared.
 *     Reverted: yes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepopulateProvidersOnStartupUseCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var registry: DefaultTrackerRegistry
    private lateinit var sdk: LavaTrackerSdk
    private lateinit var settings: TestSettingsRepository
    private val endpoints = lava.testing.repository.TestEndpointsRepository()
    private var activatedBaseUrl: String? = null
    private var activatedKey: String? = null

    /**
     * The 4 bundled providers a cold-started registry holds before any dynamic
     * fetch (the "only 4 providers" baseline the operator reported).
     */
    private val bundledIds = listOf("rutracker", "rutor", "nnmclub", "kinozal")

    /**
     * The full 12-provider catalogue the api-app's `GET /providers` returns:
     * 5 native + 7 curated. Each curated id is one the bundled registry never
     * had — its appearance proves the list came FROM the API.
     */
    private val allTwelveIds = listOf(
        "rutracker", "nnmclub", "kinozal", "archiveorg", "gutenberg",
        "thepiratebay", "yts", "torrentscsv", "bitsearch", "knaben",
        "nyaa", "torrentdownloads",
    )

    private val curatedIds = listOf(
        "thepiratebay",
        "yts",
        "torrentscsv",
        "bitsearch",
        "knaben",
        "nyaa",
        "torrentdownloads",
    )

    private fun catalogueJson(): String {
        val entries = allTwelveIds.joinToString(",\n") { id ->
            """
            {
              "id": "$id",
              "displayName": "${id.replaceFirstChar { it.uppercase() }}",
              "kind": "native",
              "capabilities": ["SEARCH"],
              "authType": "NONE",
              "encoding": "UTF-8",
              "baseUrls": [],
              "supportsAnonymous": true
            }
            """.trimIndent()
        }
        return """{ "providers": [ $entries ] }"""
    }

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        registry = DefaultTrackerRegistry()
        // Cold-start state: ONLY the bundled providers are registered (the
        // registry has NOT been pre-populated with the dynamic 12). This is the
        // exact post-restart state the defect produces.
        bundledIds.forEach { registerBundledTracker(it) }
        // The ApiBackedTrackerClient factory the production graph installs at DI
        // time (TrackerClientModule.provideTrackerRegistry); populateFrom of a
        // non-empty list requires it. FakeTrackerClient is the OUTERMOST boundary
        // (the client object below the SUT), per §6.J Second Law.
        registry.setApiClientFactory { descriptor -> FakeTrackerClient(descriptor) }
        sdk = LavaTrackerSdk(registry)

        settings = TestSettingsRepository()
    }

    @After
    fun teardown() {
        // Reset the process-wide active-base-URL holder the use case sets, so it
        // does not leak into other tests in the module.
        ApiBaseUrlHolder.reset()
        server.shutdown()
    }

    private fun registerBundledTracker(id: String) {
        val descriptor = object : TrackerDescriptor {
            override val trackerId: String = id
            override val displayName: String = id
            override val baseUrls = listOf(MirrorUrl(url = "https://$id.example", isPrimary = true))
            override val capabilities = setOf(TrackerCapability.SEARCH)
            override val authType: AuthType = AuthType.NONE
            override val encoding = "UTF-8"
            override val expectedHealthMarker = "ok"
            override val verified = true
            override val apiSupported = true
        }
        registry.register(
            object : PluginFactory<TrackerDescriptor, TrackerClient> {
                override val descriptor: TrackerDescriptor = descriptor
                override fun create(config: PluginConfig): TrackerClient = FakeTrackerClient(descriptor)
            },
        )
    }

    /**
     * Builds the REAL use case over the REAL catalogue stack. The repository
     * issues a real `GET {base}/providers` against the [server] socket; the
     * activator captures the base URL the use case re-activates (so we can assert
     * the dynamic clients were pointed at the chosen instance).
     */
    private fun buildUseCase(): RepopulateProvidersOnStartupUseCase {
        val repository = ProviderCatalogRepository(
            lanHttpClient = OkHttpClient(),
            authFieldName = "Lava-Auth",
            store = InMemoryProviderCatalogStore(),
        )
        return RepopulateProvidersOnStartupUseCase(
            settingsRepository = settings,
            fetchProvidersUseCase = FetchProvidersUseCase(repository),
            trackerRegistry = registry,
            activator = ActiveApiBaseUrlActivator { url, key ->
                activatedBaseUrl = url
                activatedKey = key
            },
            // MockWebServer serves plain HTTP — substitute an http builder for
            // the production Https one (the exact seam SseBaseUrlBuilder exposes).
            apiBaseUrlBuilder = SseBaseUrlBuilder { host, port -> "http://$host:$port" },
            endpointsRepository = endpoints,
        )
    }

    // CHALLENGE — primary assertion on sdk.listAvailableTrackers() (the list the
    // Settings/Menu + ProviderConfig screens render).
    @Test
    fun `cold start with a persisted GoApi endpoint repopulates ALL 12 providers app-wide`() =
        runTest {
            // Baseline: a freshly cold-started registry has ONLY the 4 bundled.
            val before = sdk.listAvailableTrackers().map { it.trackerId }.toSet()
            assertEquals(
                "cold-start baseline MUST be exactly the 4 bundled providers " +
                    "(the 'only 4 providers' state the user reported) — was $before",
                bundledIds.toSet(),
                before,
            )

            // The user picked a lava-api-go instance during onboarding; that
            // endpoint is persisted as the active endpoint.
            settings.setEndpoint(Endpoint.GoApi(host = server.hostName, port = server.port, key = "k"))

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(catalogueJson()),
            )

            val repopulated = buildUseCase().invoke()

            assertTrue("the startup re-populate MUST report success", repopulated)

            // PRIMARY: the list every Settings/search screen reads now contains
            // ALL 12 providers — asserted by FULL COUNT, not a weak >= 1.
            val after = sdk.listAvailableTrackers().map { it.trackerId }
            assertEquals(
                "after the cold-start re-populate the SDK MUST list ALL 12 providers " +
                    "(5 native + 7 curated) — was $after",
                12,
                after.size,
            )
            assertEquals(
                "the listed provider ids MUST be exactly the API catalogue — was $after",
                allTwelveIds.toSet(),
                after.toSet(),
            )
            // PRIMARY: every curated id (the providers the bundled registry never
            // had) MUST now be present — this is the user-visible fix.
            curatedIds.forEach { id ->
                assertTrue(
                    "curated provider '$id' MUST appear in the SDK list after cold-start " +
                        "re-populate — was $after",
                    after.contains(id),
                )
            }
            // SECONDARY: the dynamic clients were pointed at the chosen instance.
            assertEquals("http://${server.hostName}:${server.port}", activatedBaseUrl)
            // SECONDARY (2026-06-14 search fix): the per-endpoint key MUST be
            // activated too, else every cold-start search 401s ("Something went wrong").
            assertEquals(
                "the per-endpoint Lava-Auth key MUST be activated for the dynamic clients",
                "k",
                activatedKey,
            )
        }

    // CHALLENGE — existing-install HEAL (2026-06-14 search-routing fix). Primary
    // assertion on the PERSISTED active endpoint (settings.endpoint) — the exact
    // value the home search resolves its target host from
    // (NetworkApiRepositoryImpl.endpoint()). Reproduces the operator's broken
    // state: an install whose onboarding pre-dated the settings.endpoint write, so
    // the active endpoint is an orphan default NOT in the Room list while the REAL
    // chosen server lives only in the Room list. On-device Chucker proof:
    // /providers → real IP (200), /search → orphan host (UnknownHostException).
    //
    // §6.J FALSIFIABILITY: delete the `settingsRepository.setEndpoint(roomGoApis.last())`
    // line in RepopulateProvidersOnStartupUseCase.reconcileActiveEndpoint() →
    // settings.endpoint stays the orphan and this assertEquals fails (expected the
    // onboarded server, got the orphan). Confirmed.
    @Test
    fun `cold start heals a stale orphan active endpoint to the onboarded server in the list`() =
        runTest {
            // Orphan active endpoint the old onboarding never overwrote — a GoApi
            // whose host:port is NOT among the user's actual added servers.
            settings.setEndpoint(Endpoint.GoApi(host = "stale-orphan.invalid"))
            // The user's REAL onboarded server lives only in the Room list.
            val real = Endpoint.GoApi(host = server.hostName, port = server.port, key = "k")
            endpoints.add(real)
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(catalogueJson()),
            )

            buildUseCase().invoke()

            // PRIMARY: the active endpoint the search path reads is now the REAL
            // onboarded server (host:port + key), NOT the unreachable orphan — so
            // search targets the right host instead of failing to resolve.
            assertEquals(real, settings.getSettings().endpoint)
        }

    // CHALLENGE — graceful degradation: a non-GoApi active endpoint is a no-op,
    // bundled providers survive (never a blank list).
    @Test
    fun `cold start with a non-GoApi endpoint is a no-op and keeps the bundled providers`() =
        runTest {
            // No GoApi configured (e.g. a fresh install whose default endpoint is
            // not a lava-api-go instance). Settings default endpoint is not GoApi.
            val repopulated = buildUseCase().invoke()

            assertFalse(
                "with no GoApi endpoint configured the use case MUST decline to " +
                    "repopulate (degrade to bundled)",
                repopulated,
            )
            val after = sdk.listAvailableTrackers().map { it.trackerId }.toSet()
            assertEquals(
                "the bundled providers MUST remain intact (never a blank list, §6.AB) " +
                    "— was $after",
                bundledIds.toSet(),
                after,
            )
        }

    // CHALLENGE — graceful degradation: an unreachable / erroring API keeps the
    // bundled providers (never wipes the list on fetch failure).
    @Test
    fun `cold start when the API errors keeps the bundled providers and does not blank the list`() =
        runTest {
            settings.setEndpoint(Endpoint.GoApi(host = server.hostName, port = server.port, key = "k"))
            // The api-app returns 500 — the real FetchProvidersUseCase maps this
            // to Result.failure (it does NOT throw); the use case must NOT clear
            // the registry.
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

            val repopulated = buildUseCase().invoke()

            assertFalse("a fetch failure MUST report no-repopulate", repopulated)
            val after = sdk.listAvailableTrackers().map { it.trackerId }.toSet()
            assertFalse(
                "on fetch failure the provider list MUST NOT be blank (§6.AB) — was $after",
                after.isEmpty(),
            )
            assertEquals(
                "the bundled providers MUST remain intact after a fetch failure — was $after",
                bundledIds.toSet(),
                after,
            )
        }
}
