package lava.testing.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.auth.AuthResult
import lava.models.auth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.1 Task 1.1.6 — equivalence audit of [TestAuthService] persistence semantics.
 *
 * Real counterpart: [lava.auth.impl.AuthServiceImpl] +
 * [lava.securestorage.PreferencesStorageImpl] pair. The production pair
 * writes the signaled-auth state to a separate SharedPreferences file
 * (`signaled_auth.xml`) on every [AuthService.signalAuthorized] call;
 * a fresh process consults that file in the cold-start path of
 * [AuthServiceImpl.getAuthState] and emits Authorized into the
 * SharedFlow on first observation.
 *
 * Anti-Bluff Pact Third Law: every branch of the real implementation
 * MUST have a matching branch in the fake. This test pins the fake's
 * persistence semantics so a future agent or contributor cannot
 * silently regress them — e.g. by removing [TestAuthService.persistedSignaledState]
 * or by making [TestAuthService.signalAuthorized] write only to the
 * in-memory observable and not to the persisted field.
 *
 * Bluff-Audit: TestAuthServicePersistenceEquivalenceTest
 *   Mutation: in TestAuthService.signalAuthorized, drop the line
 *             `persistedSignaledState = newState` (write only to
 *             authState.value).
 *   Observed-Failure: `simulateProcessRestart_after_signalAuthorized_restores_authorized_state`
 *             expected `Authorized(name=archive.org user, …)` but got
 *             `Unauthorized` — the fake has diverged from production
 *             where the persisted file would still hold the signal.
 *   Reverted: yes
 */
class TestAuthServicePersistenceEquivalenceTest {

    @Test
    fun `signalAuthorized writes to BOTH in-memory observable AND persisted field`() = runTest {
        val service = TestAuthService()

        service.signalAuthorized(name = "archive.org user", avatarUrl = null)

        // In-memory observable updated
        assertEquals(
            AuthState.Authorized(name = "archive.org user", avatarUrl = null),
            service.observeAuthState().first(),
        )
        // Persisted-equivalent field updated — anti-bluff: a fake that
        // wrote only to authState would diverge from production where
        // PreferencesStorageImpl.saveSignaledAuthState is also called.
        assertEquals(
            AuthState.Authorized(name = "archive.org user", avatarUrl = null),
            service.persistedSignaledState,
        )
        // Recorded for assert-on-side-effects use cases (Sixth Law clause 3
        // permits this as a SECONDARY assertion alongside the primary
        // user-visible-state assertion above).
        assertEquals(listOf("archive.org user"), service.signaledNames)
    }

    @Test
    fun `simulateProcessRestart_after_signalAuthorized_restores_authorized_state`() = runTest {
        val service = TestAuthService()
        service.signalAuthorized(name = "archive.org user", avatarUrl = "http://example.test/a.png")
        // Confirm pre-restart state is Authorized
        assertEquals(
            AuthState.Authorized(name = "archive.org user", avatarUrl = "http://example.test/a.png"),
            service.observeAuthState().first(),
        )

        // Act: simulate process death + relaunch. In-memory observable is
        // reset; persisted field is the only survivor — the cold-start
        // path then restores Authorized from it.
        service.simulateProcessRestart()

        // Primary assertion: the user-visible authorized state survives
        assertEquals(
            AuthState.Authorized(name = "archive.org user", avatarUrl = "http://example.test/a.png"),
            service.observeAuthState().first(),
        )
        assertTrue(service.isAuthorized())
    }

    @Test
    fun `simulateProcessRestart_with_no_prior_signal_emits_Unauthorized`() = runTest {
        val service = TestAuthService()

        service.simulateProcessRestart()

        assertEquals(AuthState.Unauthorized, service.observeAuthState().first())
        assertNull(service.persistedSignaledState)
    }

    @Test
    fun `logout_clears_persisted_signal_and_subsequent_restart_emits_Unauthorized`() = runTest {
        val service = TestAuthService()
        service.signalAuthorized(name = "archive.org user", avatarUrl = null)
        assertEquals(
            AuthState.Authorized(name = "archive.org user", avatarUrl = null),
            service.persistedSignaledState,
        )

        service.logout()

        // Logout clears both surfaces — equivalent to the production
        // logout's clearAccount + clearSignaledAuthState pair.
        assertEquals(AuthState.Unauthorized, service.observeAuthState().first())
        assertNull(service.persistedSignaledState)

        // After a logout, a process restart MUST NOT restore the prior
        // authorized state — otherwise logout would be reversible by
        // force-quitting the app, which is the regression Phase 1.1
        // Task 1.1.5 was written to prevent.
        service.simulateProcessRestart()
        assertEquals(AuthState.Unauthorized, service.observeAuthState().first())
    }

    @Test
    fun `login_response_field_is_unaffected_by_persistence_changes`() = runTest {
        val service = TestAuthService()
        service.response = AuthResult.Success
        // Persistence operations must not interfere with the legacy
        // login-response harness — guards against an over-eager refactor
        // collapsing the two fields.
        service.signalAuthorized(name = "x", avatarUrl = null)

        assertEquals(AuthResult.Success, service.login("u", "p", null, null, null))
    }
}
