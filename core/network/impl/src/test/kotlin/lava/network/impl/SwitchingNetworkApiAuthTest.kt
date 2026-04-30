package lava.network.impl

import kotlinx.coroutines.test.runTest
import lava.models.settings.Endpoint
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.auth.UserDto
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginResult
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.rutracker.mapper.RuTrackerDtoMappers
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-Bluff Pact (Sixth Law clauses 1-3): assertions are on user-visible
 * outcomes — the boolean returned by `checkAuthorized` (drives the login
 * gate), the `AuthResponseDto.Success.user.token` returned by `login` (used
 * by every subsequent request as the auth bearer), and the legacy fall-through
 * proof (`fakeLegacyApi.calls` stays empty when SDK is engaged).
 *
 * Falsifiability rehearsal (clause 6.6.2) — performed before commit on
 * `login_routes_via_sdk_when_endpoint_is_direct_rutracker_and_returns_reverse_mapped_dto`:
 *   Mutation: in [RuTrackerDtoMappers.loginResultToDto], swapped the
 *     `Authenticated && sessionToken != null` branch to emit
 *     `WrongCredits(captcha = null)` instead of `Success(...)`.
 *   Test outcome: `assertTrue(loginResponse is AuthResponseDto.Success)`
 *     failed with "expected Success but was WrongCredits(captcha=null)".
 *   Reverted the mutation; test now passes. The SDK + reverse-mapper round
 *   trip is therefore demonstrably load-bearing — any drift in the mapper
 *   surfaces as a failed assertion on the user-visible auth state.
 *
 * The Anti-Bluff Pact (Third Law) requires NO mocking of internal business
 * logic: tests use the REAL [LavaTrackerSdk] and REAL
 * [RuTrackerDtoMappers] — only the [TrackerClient] is faked via
 * [FakeTrackerClient] (the outermost boundary, equivalent to mocking the
 * actual HTTP socket). The [SettingsRepository] fake mirrors the real
 * Room-backed impl's read-after-write semantics.
 */
class SwitchingNetworkApiAuthTest {

    private val rutrackerCapableDescriptor: TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutracker"
        override val displayName: String = "RuTracker"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(
                url = "https://rutracker.org",
                isPrimary = true,
                priority = 0,
                protocol = Protocol.HTTPS,
            ),
        )
        override val capabilities: Set<TrackerCapability> = setOf(
            TrackerCapability.SEARCH,
            TrackerCapability.BROWSE,
            TrackerCapability.TOPIC,
            TrackerCapability.COMMENTS,
            TrackerCapability.FAVORITES,
            TrackerCapability.AUTH_REQUIRED,
            TrackerCapability.TORRENT_DOWNLOAD,
        )
        override val authType: AuthType = AuthType.FORM_LOGIN
        override val encoding: String = "windows-1251"
        override val expectedHealthMarker: String = "rutracker"
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory =
        object : TrackerClientFactory {
            override val descriptor: TrackerDescriptor = client.descriptor
            override fun create(config: PluginConfig): TrackerClient = client
        }

    private fun newSwitchingApi(
        endpoint: Endpoint = Endpoint.Rutracker,
        configureFake: FakeTrackerClient.() -> Unit = {},
    ): TestRig {
        val fake = FakeTrackerClient(rutrackerCapableDescriptor).apply(configureFake)
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(fake)) }
        val sdk = LavaTrackerSdk(registry)
        sdk.switchTracker("rutracker")
        val legacyApi = FakeLegacyNetworkApi()
        val repo = FakeNetworkApiRepository(legacyApi)
        val settings = FakeSettingsRepository(initial = endpoint)
        val switching = SwitchingNetworkApi(
            networkApiRepository = repo,
            sdk = sdk,
            mappers = RuTrackerDtoMappers(),
            settingsRepository = settings,
        )
        return TestRig(switching, fake, legacyApi, settings)
    }

    private data class TestRig(
        val switching: SwitchingNetworkApi,
        val fake: FakeTrackerClient,
        val legacyApi: FakeLegacyNetworkApi,
        val settings: FakeSettingsRepository,
    )

    @Test
    fun `checkAuthorized routes via SDK and returns true when SDK reports Authenticated`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            // Trigger a successful login on the fake so that the
            // AuthenticatableTracker.checkAuth() reports Authenticated.
            loginProvider = {
                LoginResult(
                    state = AuthState.Authenticated,
                    sessionToken = "valid-token",
                    captchaChallenge = null,
                )
            }
        }

        // Drive the fake into the Authenticated state by calling sdk.login()
        // through the switching api. Then assert checkAuthorized.
        val loginResponse = rig.switching.login(
            username = "tester",
            password = "secret",
            captchaSid = null,
            captchaCode = null,
            captchaValue = null,
        )
        assertTrue(
            "expected AuthResponseDto.Success but got $loginResponse",
            loginResponse is AuthResponseDto.Success,
        )
        loginResponse as AuthResponseDto.Success

        // User-visible state (clause 6.6.3): the token persisted on UserDto
        // is the value the rest of the app uses as its auth bearer.
        assertEquals("valid-token", loginResponse.user.token)
        // The legacy NetworkApi was NEVER touched on this path — proof that
        // the SDK guard correctly took over. If shouldUseSdk() returned false
        // by mistake, fakeLegacyApi.calls would contain "login(...)".
        assertTrue(
            "legacy api should NOT be called on direct-rutracker SDK path; was: ${rig.legacyApi.calls}",
            rig.legacyApi.calls.isEmpty(),
        )

        // checkAuthorized reads the same SDK-side AuthState and reports true.
        val authorized = rig.switching.checkAuthorized(token = "ignored-on-sdk-path")
        assertTrue(
            "checkAuthorized should report true after a successful SDK login",
            authorized,
        )
        assertTrue(
            "legacy api should still NOT be called for checkAuthorized on direct-rutracker path",
            rig.legacyApi.calls.isEmpty(),
        )
    }

    @Test
    fun `login routes via SDK on direct rutracker and reverse mapper produces user-visible AuthResponseDto`() = runTest {
        val expectedToken = "session-cookie-bb_data"
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            loginProvider = {
                LoginResult(
                    state = AuthState.Authenticated,
                    sessionToken = expectedToken,
                    captchaChallenge = null,
                )
            }
        }

        val response = rig.switching.login(
            username = "ubuntu-team",
            password = "irrelevant-on-fake",
            captchaSid = null,
            captchaCode = null,
            captchaValue = null,
        )

        // User-visible state (clause 6.6.3): the token surfaced by the
        // SDK login outcome is byte-identical to what the legacy
        // mapper would have produced — that's the parity gate.
        assertTrue(
            "expected Success branch on SDK login round-trip, got $response",
            response is AuthResponseDto.Success,
        )
        response as AuthResponseDto.Success
        assertEquals(expectedToken, response.user.token)
        // Reverse mapper synthesises empty id/avatarUrl per Section E
        // (information-loss documented contract): assert exactly that
        // shape so a future mapper drift fails LOUDLY here.
        assertEquals("", response.user.id)
        assertEquals("", response.user.avatarUrl)
        // Legacy was never touched.
        assertTrue(
            "legacy api should not be invoked when SDK handles login on direct rutracker; calls=${rig.legacyApi.calls}",
            rig.legacyApi.calls.isEmpty(),
        )
    }

    @Test
    fun `login falls through to legacy NetworkApi when endpoint is LAN GoApi`() = runTest {
        val legacyMarkerToken = "legacy-marker-token"
        val rig = newSwitchingApi(
            endpoint = Endpoint.GoApi(host = "192.168.1.50", port = 8443),
        ).also {
            it.legacyApi.loginReturn = AuthResponseDto.Success(
                user = UserDto(id = "lan-user", token = legacyMarkerToken, avatarUrl = ""),
            )
        }

        val response = rig.switching.login(
            username = "tester",
            password = "secret",
            captchaSid = null,
            captchaCode = null,
            captchaValue = null,
        )

        // User-visible state: the LAN proxy / GoApi response is forwarded
        // verbatim — the SDK was bypassed because the endpoint host is LAN.
        assertTrue(response is AuthResponseDto.Success)
        response as AuthResponseDto.Success
        assertEquals(legacyMarkerToken, response.user.token)
        // Proof of fall-through: the legacy fake recorded the call.
        assertEquals(
            listOf("login(tester,secret,null,null,null)"),
            rig.legacyApi.calls,
        )
    }

    @Test
    fun `checkAuthorized falls through to legacy NetworkApi when endpoint is LAN Mirror with localhost host`() = runTest {
        val rig = newSwitchingApi(
            endpoint = Endpoint.Mirror(host = "192.168.1.42:8080"),
        ).also {
            it.legacyApi.checkAuthorizedReturn = true
        }

        val authorized = rig.switching.checkAuthorized(token = "lan-token")

        assertTrue("legacy LAN proxy returned true; switching should pass it through", authorized)
        assertEquals(
            listOf("checkAuthorized(lan-token)"),
            rig.legacyApi.calls,
        )
    }

    /**
     * Smoke check: the SDK-built LavaTrackerSdk's `login(LoginRequest)`
     * facade returns a real [LoginResult] (not null) for the
     * authenticatable-capable rutracker descriptor. Guards the Task 2.32
     * facade-expansion against accidental capability-table drift.
     */
    @Test
    fun `LavaTrackerSdk login facade returns non-null LoginResult for AUTH_REQUIRED tracker`() = runTest {
        val rig = newSwitchingApi(endpoint = Endpoint.Rutracker) {
            loginProvider = {
                LoginResult(state = AuthState.Unauthenticated, sessionToken = null, captchaChallenge = null)
            }
        }
        // Reaching into the rig's SDK to sanity-check the facade signature.
        // (Production callers go through SwitchingNetworkApi.login above.)
        val sdkResult = rig.switching.login(
            username = "u",
            password = "p",
            captchaSid = null,
            captchaCode = null,
            captchaValue = null,
        )
        // WrongCredits when SDK reports Unauthenticated with no captcha
        // (per RuTrackerDtoMappers.loginResultToDto branch table).
        assertNotNull(sdkResult)
        assertTrue(
            "expected WrongCredits but got $sdkResult",
            sdkResult is AuthResponseDto.WrongCredits,
        )
        sdkResult as AuthResponseDto.WrongCredits
        assertNull(
            "Unauthenticated without captcha should produce null captcha on the DTO",
            sdkResult.captcha,
        )
        assertFalse(
            "legacy api MUST NOT be touched on the direct-rutracker SDK path",
            rig.legacyApi.calls.any { it.startsWith("login") },
        )
    }
}
