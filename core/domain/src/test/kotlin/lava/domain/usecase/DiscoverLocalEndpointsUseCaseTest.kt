package lava.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import lava.data.api.service.DiscoveredEndpoint
import lava.models.settings.Endpoint
import lava.testing.TestDispatchers
import lava.testing.repository.TestEndpointsRepository
import lava.testing.repository.TestSettingsRepository
import lava.testing.service.TestLocalNetworkDiscoveryService
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

    private fun createUseCase(): DiscoverLocalEndpointsUseCase = DiscoverLocalEndpointsUseCaseImpl(
        discoveryService = discoveryService,
        endpointsRepository = endpointsRepository,
        settingsRepository = settingsRepository,
        dispatchers = TestDispatchers(),
    )

    @Test
    fun `returns NotFound when no endpoint is discovered`() = runTest {
        val useCase = createUseCase()
        launch { discoveryService.complete() }

        val result = useCase()

        assertEquals(DiscoverLocalEndpointsResult.NotFound, result)
    }

    @Test
    fun `returns AlreadyConfigured when same endpoint already exists in repository`() = runTest {
        val useCase = createUseCase()
        endpointsRepository.add(Endpoint.Mirror("192.168.1.100:8080"))
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

        assertEquals(DiscoverLocalEndpointsResult.AlreadyConfigured, result)
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
        assertEquals("192.168.1.100:8080", discoveredResult.endpoint.host)
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
            endpointsRepository.observeAll().first().any {
                it is Endpoint.Mirror && it.host == "192.168.1.100:8080"
            },
        )
        assertEquals(
            Endpoint.Mirror("192.168.1.50"),
            settingsRepository.getSettings().endpoint,
        )
    }

    @Test
    fun `allows discovery when current endpoint is Proxy`() = runTest {
        val useCase = createUseCase()
        settingsRepository.setEndpoint(Endpoint.Proxy)

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
        assertTrue("Proxy should be seeded", all.any { it == Endpoint.Proxy })
        assertTrue("Rutracker should be seeded", all.any { it == Endpoint.Rutracker })
        assertTrue(
            "Discovered mirror should be added",
            all.any { it is Endpoint.Mirror && it.host == "192.168.1.100:8080" },
        )
    }
}
