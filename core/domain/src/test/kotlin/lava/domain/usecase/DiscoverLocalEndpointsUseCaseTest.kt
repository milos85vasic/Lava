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
import org.junit.Test

class DiscoverLocalEndpointsUseCaseTest {

    private val discoveryService = TestLocalNetworkDiscoveryService()
    private val endpointsRepository = TestEndpointsRepository()
    private val settingsRepository = TestSettingsRepository()

    private val useCase = DiscoverLocalEndpointsUseCaseImpl(
        discoveryService = discoveryService,
        endpointsRepository = endpointsRepository,
        settingsRepository = settingsRepository,
        dispatchers = TestDispatchers(),
    )

    @Test
    fun `returns NotFound when no endpoint is discovered`() = runTest {
        launch { discoveryService.complete() }
        val result = useCase()
        assertEquals(DiscoverLocalEndpointsResult.NotFound, result)
    }

    @Test
    fun `returns AlreadyConfigured when same endpoint already exists`() = runTest {
        endpointsRepository.add(Endpoint.Mirror("192.168.1.100:8080"))
        val discovered = DiscoveredEndpoint(host = "192.168.1.100:8080", port = 8080, name = "lava-proxy")
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }
        val result = useCase()
        assertEquals(DiscoverLocalEndpointsResult.AlreadyConfigured, result)
    }

    @Test
    fun `discovers endpoint, adds it and sets it active`() = runTest {
        val discovered = DiscoveredEndpoint(host = "192.168.1.100:8080", port = 8080, name = "lava-proxy")
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
        val discoveredResult = result as DiscoverLocalEndpointsResult.Discovered
        assertEquals("192.168.1.100:8080", discoveredResult.endpoint.host)
        assertTrue(endpointsRepository.observeAll().first().contains(discoveredResult.endpoint))
        assertEquals(discoveredResult.endpoint, settingsRepository.getSettings().endpoint)
    }

    @Test
    fun `discovers endpoint but does not override existing custom mirror`() = runTest {
        settingsRepository.setEndpoint(Endpoint.Mirror("192.168.1.50"))

        val discovered = DiscoveredEndpoint(host = "192.168.1.100:8080", port = 8080, name = "lava-proxy")
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()

        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
        assertTrue(endpointsRepository.observeAll().first().any { it is Endpoint.Mirror && it.host == "192.168.1.100:8080" })
        assertEquals(Endpoint.Mirror("192.168.1.50"), settingsRepository.getSettings().endpoint)
    }

    @Test
    fun `allows discovery when current endpoint is Proxy`() = runTest {
        settingsRepository.setEndpoint(Endpoint.Proxy)

        val discovered = DiscoveredEndpoint(host = "192.168.1.100:8080", port = 8080, name = "lava-proxy")
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()
        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
    }

    @Test
    fun `allows discovery when current endpoint is Rutracker`() = runTest {
        settingsRepository.setEndpoint(Endpoint.Rutracker)

        val discovered = DiscoveredEndpoint(host = "192.168.1.100:8080", port = 8080, name = "lava-proxy")
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val result = useCase()
        assertTrue(result is DiscoverLocalEndpointsResult.Discovered)
    }
}
