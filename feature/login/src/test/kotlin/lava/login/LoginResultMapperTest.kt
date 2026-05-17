package lava.login

import lava.models.auth.AuthResult
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.CaptchaChallenge
import lava.tracker.api.model.LoginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [toAuthResult] (LoginResultMapper).
 *
 * Bug 1 (2026-05-17, §6.L 57th invocation): the load-bearing assertion is
 * that ServiceUnavailable propagates as a distinct AuthResult variant
 * carrying the reason string — NOT silently collapses to WrongCredits,
 * which would re-introduce the §6.J anti-bluff failure mode the variant
 * was added to evict.
 *
 * Falsifiability rehearsal (recorded in commit body):
 *   Mutation: replace `AuthResult.ServiceUnavailable(reason = s.reason)`
 *             with `AuthResult.WrongCredits(captcha = null)` in
 *             LoginResultMapper's ServiceUnavailable branch.
 *   Observed: `service unavailable propagates as ServiceUnavailable
 *             with reason` fails with "Expected AuthResult.
 *             ServiceUnavailable carrying reason; got AuthResult.
 *             WrongCredits(captcha=null)".
 *   Reverted: yes.
 */
class LoginResultMapperTest {

    @Test
    fun `authenticated maps to AuthResult Success`() {
        val result = LoginResult(state = AuthState.Authenticated, sessionToken = "token-1")

        val mapped = result.toAuthResult()

        assertEquals(AuthResult.Success, mapped)
    }

    @Test
    fun `unauthenticated maps to AuthResult WrongCredits`() {
        val result = LoginResult(state = AuthState.Unauthenticated)

        val mapped = result.toAuthResult()

        assertTrue("Expected WrongCredits; got $mapped", mapped is AuthResult.WrongCredits)
        assertNull((mapped as AuthResult.WrongCredits).captcha)
    }

    @Test
    fun `unauthenticated with captcha forwards captcha to WrongCredits`() {
        val challenge = CaptchaChallenge(sid = "s1", code = "c1", imageUrl = "u1")
        val result = LoginResult(
            state = AuthState.Unauthenticated,
            captchaChallenge = challenge,
        )

        val mapped = result.toAuthResult()

        assertTrue(mapped is AuthResult.WrongCredits)
        val wc = mapped as AuthResult.WrongCredits
        assertEquals("s1", wc.captcha?.id)
        assertEquals("c1", wc.captcha?.code)
        assertEquals("u1", wc.captcha?.url)
    }

    @Test
    fun `captcha required maps to AuthResult CaptchaRequired`() {
        val challenge = CaptchaChallenge(sid = "s2", code = "c2", imageUrl = "u2")
        val result = LoginResult(
            state = AuthState.CaptchaRequired(challenge),
            captchaChallenge = challenge,
        )

        val mapped = result.toAuthResult()

        assertTrue("Expected CaptchaRequired; got $mapped", mapped is AuthResult.CaptchaRequired)
        val cr = mapped as AuthResult.CaptchaRequired
        assertEquals("s2", cr.captcha.id)
    }

    /**
     * Bug 1 load-bearing assertion. The mapper MUST propagate
     * ServiceUnavailable as its own variant so the ProviderLoginViewModel
     * can render the user-visible "Service unavailable" banner instead
     * of marking creds as Invalid (the §6.J bluff).
     */
    @Test
    fun `service unavailable propagates as ServiceUnavailable with reason`() {
        val result = LoginResult(
            state = AuthState.ServiceUnavailable(
                reason = "Unknown: parser found no expected markers",
            ),
        )

        val mapped = result.toAuthResult()

        assertTrue(
            "Expected AuthResult.ServiceUnavailable carrying reason; got $mapped",
            mapped is AuthResult.ServiceUnavailable,
        )
        assertEquals(
            "Unknown: parser found no expected markers",
            (mapped as AuthResult.ServiceUnavailable).reason,
        )
    }

    /**
     * Explicit anti-bluff guard: a ServiceUnavailable AuthState MUST NOT
     * be silently mapped to WrongCredits. The whole point of the new
     * variant is to prevent the UI from telling the user "your password
     * is wrong" when the system has no idea.
     */
    @Test
    fun `service unavailable does NOT silently collapse to WrongCredits`() {
        val result = LoginResult(
            state = AuthState.ServiceUnavailable(reason = "CloudflareBlocked: 1020"),
        )

        val mapped = result.toAuthResult()

        assertTrue(
            "MUST NOT be WrongCredits — that is the §6.J bluff this " +
                "variant exists to evict. Got $mapped.",
            mapped !is AuthResult.WrongCredits,
        )
    }
}
