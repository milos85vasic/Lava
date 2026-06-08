package lava.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.settings.Endpoint
import lava.testing.repository.TestEndpointsRepository
import lava.testing.repository.TestSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack unit tests for the SETTINGS / DISCOVERY endpoint use-cases:
 * [AddEndpointUseCaseImpl], [SetEndpointUseCaseImpl], [RemoveEndpointUseCaseImpl].
 *
 * Each SUT is the production `*Impl`. The boundaries are the behavioral fakes
 * [TestEndpointsRepository] (mirrors `EndpointsRepositoryImpl` — duplicate-add
 * rejection per Room PRIMARY KEY) and [TestSettingsRepository] (mirrors
 * `SettingsRepositoryImpl` — copy + emit the active endpoint). No SUT is mocked.
 *
 * Primary assertions are on user-visible repository side-effects: which endpoints
 * appear in the discovered-endpoints list a user manages on the Connections
 * screen, and which endpoint the app is actively talking to (Settings.endpoint).
 *
 * FALSIFIABILITY REHEARSALS (each performed, observed RED, reverted — see report):
 *  - RemoveEndpointUseCaseImpl: deleted the active-endpoint fallback `if` block →
 *    `removing the active endpoint falls back to Rutracker` FAILED (active stayed
 *    on the removed Mirror instead of resetting to Rutracker).
 *  - AddEndpointUseCaseImpl: changed `add(Endpoint.Mirror(endpoint))` to a no-op →
 *    `add persists the mirror to the endpoints list` FAILED (list empty).
 *  - SetEndpointUseCaseImpl: changed the delegate to `setEndpoint(Endpoint.Rutracker)`
 *    → `set updates the active endpoint a user is connected to` FAILED (wrong host).
 */
class EndpointSettingsUseCasesTest {

    // -------- AddEndpointUseCase --------

    @Test
    fun `add persists the mirror to the endpoints list`() = runTest {
        val endpoints = TestEndpointsRepository()
        val useCase: AddEndpointUseCase = AddEndpointUseCaseImpl(endpoints)

        useCase("mirror.example.org")

        // User-visible: the manually-added mirror shows up in the managed list.
        assertEquals(
            listOf<Endpoint>(Endpoint.Mirror("mirror.example.org")),
            endpoints.currentEndpoints(),
        )
    }

    @Test
    fun `adding the same mirror twice is rejected like a Room primary-key conflict`() = runTest {
        val endpoints = TestEndpointsRepository()
        val useCase: AddEndpointUseCase = AddEndpointUseCaseImpl(endpoints)

        useCase("dup.example.org")

        // Second add of the identical endpoint must be rejected — the real repo's
        // Room PRIMARY KEY constraint surfaces as an exception, so a duplicate row
        // never silently appears in the user's list.
        assertThrows(IllegalStateException::class.java) {
            runTest { useCase("dup.example.org") }
        }
        assertEquals(1, endpoints.currentEndpoints().size)
    }

    // -------- SetEndpointUseCase --------

    @Test
    fun `set updates the active endpoint a user is connected to`() = runTest {
        val settings = TestSettingsRepository()
        val useCase: SetEndpointUseCase = SetEndpointUseCaseImpl(settings)
        val target = Endpoint.GoApi(host = "10.0.0.5", port = 8443)

        useCase(target)

        // User-visible: the app is now talking to the selected GoApi instance.
        assertEquals(target, settings.getSettings().endpoint)
        assertEquals(target, settings.observeSettings().first().endpoint)
    }

    // -------- RemoveEndpointUseCase (branching) --------

    @Test
    fun `removing the active endpoint falls back to Rutracker`() = runTest {
        val active = Endpoint.GoApi(host = "10.0.0.9", port = 8443)
        val endpoints = TestEndpointsRepository().apply { add(active) }
        val settings = TestSettingsRepository().apply { setEndpoint(active) }
        val useCase: RemoveEndpointUseCase = RemoveEndpointUseCaseImpl(endpoints, settings)

        useCase(active)

        // Side-effect 1: the endpoint is gone from the managed list.
        assertFalse(endpoints.currentEndpoints().contains(active))
        // Side-effect 2 (the branch): because it was the ACTIVE endpoint, the app
        // must not be left pointing at a deleted endpoint — it resets to Rutracker.
        assertEquals(Endpoint.Rutracker, settings.getSettings().endpoint)
    }

    @Test
    fun `removing a non-active endpoint leaves the active selection untouched`() = runTest {
        val active = Endpoint.GoApi(host = "10.0.0.1", port = 8443)
        val other = Endpoint.Mirror("spare.example.org")
        val endpoints = TestEndpointsRepository().apply {
            add(active)
            add(other)
        }
        val settings = TestSettingsRepository().apply { setEndpoint(active) }
        val useCase: RemoveEndpointUseCase = RemoveEndpointUseCaseImpl(endpoints, settings)

        useCase(other)

        // The non-active endpoint is removed...
        assertFalse(endpoints.currentEndpoints().contains(other))
        assertTrue(endpoints.currentEndpoints().contains(active))
        // ...and the user's active connection is NOT disturbed (no spurious fallback).
        assertEquals(active, settings.getSettings().endpoint)
    }
}
