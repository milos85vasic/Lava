package lava.network.dto.auth

import lava.network.serialization.JsonFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real-stack deserialization tests for the [AuthResponseDto] login-outcome
 * discriminated union — the wire contract between the proxy / Go API and the
 * Android client's [lava.auth.impl.AuthServiceImpl].
 *
 * Each branch maps to a user-visible login outcome (logged in / wrong password
 * / captcha challenge / service blocked). A drifted `@SerialName` discriminator
 * here surfaces as a `SerializationException` at login, i.e. the user can never
 * sign in. These tests pin the discriminator strings and the optional-captcha
 * shapes.
 *
 * Anti-Bluff posture (§6.J): the SUT is the production polymorphic serializer
 * from [JsonFactory.create] applied to the production `@Serializable` DTOs.
 * Nothing mocked. Primary assertions are on the decoded domain value the auth
 * service branches on.
 *
 * Bluff-Audit recorded in the commit body.
 */
class AuthResponseDtoSerializationTest {

    private val json = JsonFactory.create()

    @Test
    fun `Success decodes with its user payload`() {
        val decoded = json.decodeFromString<AuthResponseDto>(
            """{"type":"Success","user":{"id":"7","token":"tok","avatarUrl":"http://a.test/x.png"}}""",
        )
        assertEquals(
            AuthResponseDto.Success(UserDto(id = "7", token = "tok", avatarUrl = "http://a.test/x.png")),
            decoded,
        )
    }

    @Test
    fun `WrongCredits decodes with a present captcha`() {
        val decoded = json.decodeFromString<AuthResponseDto>(
            """{"type":"WrongCredits","captcha":{"id":"sid","code":"cc","url":"http://c.test/c.png"}}""",
        )
        assertEquals(
            AuthResponseDto.WrongCredits(CaptchaDto(id = "sid", code = "cc", url = "http://c.test/c.png")),
            decoded,
        )
    }

    @Test
    fun `WrongCredits decodes with a null captcha`() {
        val decoded = json.decodeFromString<AuthResponseDto>(
            """{"type":"WrongCredits","captcha":null}""",
        )
        decoded as AuthResponseDto.WrongCredits
        assertNull(decoded.captcha)
    }

    @Test
    fun `CaptchaRequired decodes with its captcha`() {
        val decoded = json.decodeFromString<AuthResponseDto>(
            """{"type":"CaptchaRequired","captcha":{"id":"s2","code":"x","url":"http://c.test/2.png"}}""",
        )
        assertEquals(
            AuthResponseDto.CaptchaRequired(CaptchaDto(id = "s2", code = "x", url = "http://c.test/2.png")),
            decoded,
        )
    }

    @Test
    fun `ServiceUnavailable decodes with its reason and defaults captcha to null`() {
        val decoded = json.decodeFromString<AuthResponseDto>(
            """{"type":"ServiceUnavailable","reason":"HttpException 503"}""",
        )
        decoded as AuthResponseDto.ServiceUnavailable
        // The reason is the verbatim string the UI renders (§6.J anti-bluff).
        assertEquals("HttpException 503", decoded.reason)
        // captcha has a default value of null so a server omitting it still decodes.
        assertNull(decoded.captcha)
    }

    @Test
    fun `unknown extra keys are ignored per JsonFactory ignoreUnknownKeys`() {
        // The proxy may add fields the client predates. JsonFactory enables
        // ignoreUnknownKeys; a Success response with an extra field must still
        // decode rather than crash the login flow.
        val decoded = json.decodeFromString<AuthResponseDto>(
            """{"type":"Success","user":{"id":"7","token":"tok","avatarUrl":"http://a.test/x.png"},"serverTime":123}""",
        )
        assertEquals(
            AuthResponseDto.Success(UserDto(id = "7", token = "tok", avatarUrl = "http://a.test/x.png")),
            decoded,
        )
    }
}
