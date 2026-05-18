package lava.onboarding.util

import lava.tracker.api.AuthType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for [displayLabel] — the helper that produces the subtitle
 * rendered under each provider in `ProvidersStep`.
 *
 * Forensic anchor: operator's 60th §6.L invocation (2026-05-18) flagged
 * that subtitles were rendering raw `FORM_LOGIN` / `API_KEY` enum names
 * with underscores instead of human-readable text. The helper exists
 * specifically to evict that class of bug.
 *
 * §6.J Bluff-Audit (run prior to commit):
 *   Mutation: revert displayLabel to `name` (the raw enum name).
 *   Observed: every `expected` ... `replace('_', ' ')` assertion below
 *             failed with "expected:<Form Login> but was:<FORM_LOGIN>".
 *   Reverted: yes.
 *
 * §6.AB primary-assertion compliance: assertions are on the user-visible
 * string content, not on whether a transformation function was called.
 */
class AuthTypeDisplayTest {

    @Test
    fun none_renders_as_None() {
        assertEquals("None", AuthType.NONE.displayLabel())
    }

    @Test
    fun form_login_renders_with_space_not_underscore() {
        val out = AuthType.FORM_LOGIN.displayLabel()
        assertEquals("Form Login", out)
        // §6.J belt-and-suspenders: explicit no-underscore assertion
        // so a future refactor that introduces a different
        // transformation pipeline still gets caught.
        assert(!out.contains('_')) { "subtitle MUST NOT contain underscore but was: $out" }
    }

    @Test
    fun captcha_login_renders_two_words() {
        assertEquals("Captcha Login", AuthType.CAPTCHA_LOGIN.displayLabel())
    }

    @Test
    fun oauth_renders_as_Oauth() {
        // Pragmatic choice: a generic word-split + titlecase pipeline
        // produces "Oauth" rather than "OAuth". Acceptable for a
        // subtitle; if a future copy-review wants "OAuth" specifically
        // it goes via a per-value override.
        assertEquals("Oauth", AuthType.OAUTH.displayLabel())
    }

    @Test
    fun api_key_renders_with_space() {
        val out = AuthType.API_KEY.displayLabel()
        assertEquals("Api Key", out)
        assert(!out.contains('_')) { "subtitle MUST NOT contain underscore but was: $out" }
    }

    @Test
    fun every_enum_value_renders_underscore_free() {
        // Forward-compat: any future AuthType value added MUST also
        // pass through this transform without underscores leaking.
        AuthType.entries.forEach { v ->
            val out = v.displayLabel()
            assert(!out.contains('_')) {
                "AuthType.$v rendered with underscore: $out"
            }
            assert(out.isNotBlank()) {
                "AuthType.$v rendered blank"
            }
        }
    }
}
