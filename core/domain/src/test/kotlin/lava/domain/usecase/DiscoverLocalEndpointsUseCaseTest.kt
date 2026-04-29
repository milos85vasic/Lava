package lava.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lava.data.api.service.DiscoveredEndpoint
import lava.models.settings.Endpoint
import lava.testing.repository.TestEndpointsRepository
import lava.testing.repository.TestSettingsRepository
import lava.testing.service.TestLocalNetworkDiscoveryService
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration Challenge Tests for [DiscoverLocalEndpointsUseCaseImpl].
 *
 * These tests exercise the REAL use-case implementation wired to behaviorally
 * equivalent fakes. They verify end-to-end behavior: discovery → repository
 * mutation → settings update. If any layer contains a bug, these tests fail.
 */
class DiscoverLocalEndpointsUseCaseTest {

    private lateinit var discoveryService: TestLocalNetworkDiscoveryService
    private lateinit var endpointsRepository: TestEndpointsRepository
    private lateinit var settingsRepository: TestSettingsRepository

    @Before
    fun setup() {
        discoveryService = TestLocalNetworkDiscoveryService()
        endpointsRepository = TestEndpointsRepository()
        settingsRepository = TestSettingsRepository()
    }

    /**
     * SP-3 fix (2026-04-29): make `dispatchers` share the surrounding
     * `runTest` scheduler. The previous `TestDispatchers()` no-arg form
     * allocated a fresh `TestCoroutineScheduler` per call, which the
     * use case's `withContext(dispatchers.io)` switched to — leaving
     * its work invisible to `runTest`'s auto-advance loop and
     * intermittently hanging the test. See `TestDispatchers.kt` KDoc
     * forensic anchor.
     */
    private fun TestScope.createUseCase(): DiscoverLocalEndpointsUseCase = DiscoverLocalEndpointsUseCaseImpl(
        discoveryService = discoveryService,
        endpointsRepository = endpointsRepository,
        settingsRepository = settingsRepository,
        dispatchers = testDispatchers(),
    )

    @Test
    fun `returns NotFound when no endpoint is discovered`() = runTest {
        val useCase = createUseCase()
        launch { discoveryService.complete() }

        val result = useCase()

        assertEquals(DiscoverLocalEndpointsResult.NotFound, result)
    }

    @Test
    fun `returns AlreadyConfigured when same endpoint already exists and is selected`() = runTest {
        val useCase = createUseCase()
        // SP-3.3 (2026-04-29): Mirror persisted form is the BARE host
        // — discovery strips the embedded port at conversion time so
        // routing stays well-formed (forensic anchor in
        // DiscoverLocalEndpointsUseCaseImpl.toEndpoint KDoc).
        val mirror = Endpoint.Mirror("192.168.1.100")
        endpointsRepository.add(mirror)
        settingsRepository.setEndpoint(mirror)
        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.AlreadyConfigured)
        assertEquals(mirror, (result as DiscoverLocalEndpointsResult.AlreadyConfigured).endpoint)
    }

    @Test
    fun `selects existing endpoint when discovered but not currently selected`() = runTest {
        val useCase = createUseCase()
        // SP-3.3 (2026-04-29): Mirror persisted form is the BARE host.
        val mirror = Endpoint.Mirror("192.168.1.100")
        endpointsRepository.add(mirror)
        // SP-3.2: was Endpoint.Proxy; that endpoint was removed.
        // Rutracker is the new "non-mirror" default that the use case
        // should override when a discovered LAN endpoint shows up.
        settingsRepository.setEndpoint(Endpoint.Rutracker)
        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
        assertEquals(mirror, settingsRepository.getSettings().endpoint)
    }

    @Test
    fun `discovers endpoint, adds it to repository and sets it active`() = runTest {
        val useCase = createUseCase()
        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
        val discoveredResult = result as DiscoverLocalEndpointsResult.Discovered
        // SP-3.3: Mirror persisted form is the BARE host so routing
        // can default the port to 8080 cleanly.
        assertEquals("192.168.1.100", discoveredResult.endpoint.host)
        assertTrue(
            endpointsRepository.observeAll().first().contains(discoveredResult.endpoint),
        )
        assertEquals(discoveredResult.endpoint, settingsRepository.getSettings().endpoint)
    }

    @Test
    fun `discovers endpoint but does not override existing custom mirror selection`() = runTest {
        val useCase = createUseCase()
        settingsRepository.setEndpoint(Endpoint.Mirror("192.168.1.50"))

        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
        assertTrue(
            // SP-3.3: bare host (port stripped at conversion).
            endpointsRepository.observeAll().first().any {
                it is Endpoint.Mirror && it.host == "192.168.1.100"
            },
        )
        assertEquals(
            Endpoint.Mirror("192.168.1.50"),
            settingsRepository.getSettings().endpoint,
        )
    }

    // SP-3.2 (2026-04-29): the prior `allows discovery when current
    // endpoint is Proxy` test was redundant with the Rutracker test below
    // — both exercised the "current is non-mirror, so override on
    // discovery" branch. With Endpoint.Proxy removed, the Rutracker
    // case is the load-bearing one. Test deleted; the Rutracker case
    // covers the remaining branch.

    @Test
    fun `allows discovery when current endpoint is Rutracker`() = runTest {
        val useCase = createUseCase()
        settingsRepository.setEndpoint(Endpoint.Rutracker)

        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
    }

    @Test
    fun `default endpoints are seeded and discovery adds a new mirror`() = runTest {
        val useCase = createUseCase()
        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        useCase()

        val all = endpointsRepository.observeAll().first()
        // SP-3.2: Endpoint.Proxy is GONE — assert it does NOT appear
        // in the seeded set, plus assert what should be there.
        assertTrue("Rutracker should be seeded", all.any { it == Endpoint.Rutracker })
        assertTrue(
            "Discovered mirror should be added (bare host per SP-3.3)",
            all.any { it is Endpoint.Mirror && it.host == "192.168.1.100" },
        )
        // Sixth-Law clause 3: primary user-visible state assertion that
        // Proxy is gone from the seeded list. Reverting the SP-3.2
        // EndpointsRepositoryImpl.defaultEndpoints to include Proxy
        // would make this fail with a clear message.
        assertTrue(
            "Endpoint.Proxy must NOT be present in the seeded list (SP-3.2)",
            all.none { it::class.simpleName == "Proxy" },
        )
    }

    // -------------------------------------------------------------------
    // SP-3 (2026-04-29) Challenge tests for the Go API discovery path.
    //
    // Sixth-Law alignment:
    //   - clause 1 (same surfaces): exercises the SAME use case the
    //     Connections screen invokes; the only fake is the discovery
    //     service (an external boundary).
    //   - clause 3 (primary assertion on user-visible state): the
    //     primary signal is what's persisted to the repository and
    //     what's set as the active endpoint — both observed by every
    //     other screen the user touches.
    //   - clause 2 (falsifiability): rehearsals recorded in the SP-3
    //     commit body (e.g. mutate the engine map to always return
    //     Engine.Ktor → these CHALLENGE tests fail because GoApi is
    //     never created).
    // -------------------------------------------------------------------

    // CHALLENGE — the load-bearing test: an engine=Go discovery on
    // _lava-api._tcp produces an Endpoint.GoApi (NOT a Mirror) with
    // the spec-default port preserved.
    @Test
    fun `engine Go discovery creates Endpoint GoApi with port preserved`() = runTest {
        val useCase = createUseCase()
        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8443",
            port = 8443,
            name = "Lava API",
            engine = DiscoveredEndpoint.Engine.Go,
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
        val endpoint = (result as DiscoverLocalEndpointsResult.Discovered).endpoint
        assertTrue("expected Endpoint.GoApi, got ${endpoint::class.simpleName}", endpoint is Endpoint.GoApi)
        endpoint as Endpoint.GoApi
        assertEquals("192.168.1.100", endpoint.host)
        assertEquals(8443, endpoint.port)

        // Repository now holds the GoApi endpoint AND it is active.
        val all = endpointsRepository.observeAll().first()
        assertTrue(
            "GoApi endpoint must persist in repository",
            all.any { it is Endpoint.GoApi && it.host == "192.168.1.100" && it.port == 8443 },
        )
        assertEquals(
            "GoApi endpoint must be selected after discovery",
            Endpoint.GoApi("192.168.1.100", 8443),
            settingsRepository.getSettings().endpoint,
        )
    }

    // CHALLENGE — non-default port survives end to end. If the use
    // case dropped the discovered port and used the spec-default
    // 8443, this test fails.
    @Test
    fun `engine Go discovery on non-default port preserves the port`() = runTest {
        val useCase = createUseCase()
        val discovered = DiscoveredEndpoint(
            host = "10.0.0.42:9443",
            port = 9443,
            name = "Lava API",
            engine = DiscoveredEndpoint.Engine.Go,
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase() as DiscoverLocalEndpointsResult.Discovered
        val endpoint = result.endpoint as Endpoint.GoApi
        assertEquals("10.0.0.42", endpoint.host)
        assertEquals(9443, endpoint.port)
    }

    // CHALLENGE — engine=Ktor discovery still produces an Endpoint.Mirror
    // (back-compat with the legacy LAN proxy path). If the use case
    // accidentally promotes Ktor hits to GoApi, this test fails.
    @Test
    fun `engine Ktor discovery creates Endpoint Mirror not GoApi`() = runTest {
        val useCase = createUseCase()
        val discovered = DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "Lava Proxy",
            engine = DiscoveredEndpoint.Engine.Ktor,
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase() as DiscoverLocalEndpointsResult.Discovered
        val endpoint = result.endpoint
        assertTrue("expected Endpoint.Mirror, got ${endpoint::class.simpleName}", endpoint is Endpoint.Mirror)
        endpoint as Endpoint.Mirror
        // SP-3.3: bare host — the legacy proxy port (8080) is filled in
        // by NetworkApiRepositoryImpl at routing time, not stored.
        assertEquals("192.168.1.100", endpoint.host)
    }

    // CHALLENGE — when the user has already chosen a GoApi endpoint
    // and a different engine=Ktor host shows up on the LAN, the use
    // case must NOT override the user's selection.
    @Test
    fun `existing GoApi selection is preserved when a Ktor endpoint is later discovered`() = runTest {
        val useCase = createUseCase()
        val originalGoApi = Endpoint.GoApi("192.168.1.100", 8443)
        endpointsRepository.add(originalGoApi)
        settingsRepository.setEndpoint(originalGoApi)

        val ktorDiscovery = DiscoveredEndpoint(
            host = "192.168.1.50:8080",
            port = 8080,
            name = "Lava Proxy",
            engine = DiscoveredEndpoint.Engine.Ktor,
        )
        launch {
            discoveryService.emit(ktorDiscovery)
            discoveryService.complete()
        }

        useCase()

        // The user's existing GoApi selection must still be active.
        assertEquals(
            originalGoApi,
            settingsRepository.getSettings().endpoint,
        )
    }
}
