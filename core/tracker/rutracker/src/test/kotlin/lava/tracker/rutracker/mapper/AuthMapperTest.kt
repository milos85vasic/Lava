package lava.tracker.rutracker.mapper

import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.auth.CaptchaDto
import lava.network.dto.auth.UserDto
import lava.tracker.api.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AuthMapper].
 *
 * Falsifiability rehearsal (Sixth Law clause 2): the load-bearing assertion
 * is that Success carries the session token forward to LoginResult.sessionToken.
 * Replacing `dto.user.token` with `null` would fail
 * "expected:<token-abc-123> but was:<null>" — the session token is the
 * single user-visible signal that says "you are logged in" (the app uses it
 * as the auth bearer for every subsequent rutracker call).
 */
class AuthMapperTest {

    private val mapper = AuthMapper()

    @Test
    fun `Success maps to Authenticated with session token`() {
        val dto = AuthResponseDto.Success(
            user = UserDto(
                id = "user-1",
                token = "token-abc-123",
                avatarUrl = "https://rutracker.org/avatars/1.png",
            ),
        )

        val result = mapper.toLoginResult(dto)

        assertEquals(AuthState.Authenticated, result.state)
        assertEquals("token-abc-123", result.sessionToken)
        assertNull(result.captchaChallenge)
    }

    @Test
    fun `WrongCredits with captcha maps to Unauthenticated with challenge`() {
        val dto = AuthResponseDto.WrongCredits(
            captcha = CaptchaDto(
                id = "sid-7",
                code = "cap_code_xxxxx",
                url = "https://rutracker.org/captcha/7.png",
            ),
        )

        val result = mapper.toLoginResult(dto)

        assertEquals(AuthState.Unauthenticated, result.state)
        assertNull(result.sessionToken)
        assertNotNull(result.captchaChallenge)
        assertEquals("sid-7", result.captchaChallenge!!.sid)
        assertEquals("cap_code_xxxxx", result.captchaChallenge!!.code)
        assertEquals("https://rutracker.org/captcha/7.png", result.captchaChallenge!!.imageUrl)
    }

    @Test
    fun `WrongCredits without captcha maps to Unauthenticated null challenge`() {
        val dto = AuthResponseDto.WrongCredits(captcha = null)

        val result = mapper.toLoginResult(dto)

        assertEquals(AuthState.Unauthenticated, result.state)
        assertNull(result.sessionToken)
        assertNull(result.captchaChallenge)
    }

    @Test
    fun `CaptchaRequired with captcha maps to CaptchaRequired state`() {
        val captcha = CaptchaDto(
            id = "sid-9",
            code = "cap_code_yyyy",
            url = "https://rutracker.org/captcha/9.png",
        )
        val dto = AuthResponseDto.CaptchaRequired(captcha = captcha)

        val result = mapper.toLoginResult(dto)

        assertTrue(
            "state should be CaptchaRequired wrapping the challenge",
            result.state is AuthState.CaptchaRequired,
        )
        val state = result.state as AuthState.CaptchaRequired
        assertEquals("sid-9", state.challenge.sid)
        assertEquals("cap_code_yyyy", state.challenge.code)
        assertEquals("https://rutracker.org/captcha/9.png", state.challenge.imageUrl)
        assertNotNull(result.captchaChallenge)
        assertEquals(state.challenge, result.captchaChallenge)
    }

    @Test
    fun `CaptchaRequired with null captcha degrades to Unauthenticated`() {
        // Pathological: rutracker shouldn't emit CaptchaRequired without the
        // actual captcha. We refuse to fabricate a synthetic challenge that
        // would put a blank image in the UI. Degrade to Unauthenticated.
        val dto = AuthResponseDto.CaptchaRequired(captcha = null)

        val result = mapper.toLoginResult(dto)

        assertEquals(AuthState.Unauthenticated, result.state)
        assertNull(result.captchaChallenge)
    }
}
