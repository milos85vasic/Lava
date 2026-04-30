package lava.tracker.rutor.parser

import lava.tracker.api.model.AuthState
import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [RuTorLoginParser] through the five outcomes the classifier supports.
 *
 * Sixth Law clause 2 (falsifiability): each test asserts on the user-visible
 * Outcome / AuthState — the chief failure signal. A parser that returns
 * AuthState.Authenticated for the wrong-password fixture would fail loudly.
 */
class RuTorLoginParserTest {

    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorLoginParser()

    @Test
    fun `success target home page is classified as Success and AuthState_Authenticated`() {
        val html = loader.load("login", "success-target-home-2026-04-30.html")
        val classification = parser.classify(html)
        assertEquals(RuTorLoginParser.Outcome.Success, classification.outcome)
        // Sixth Law clause 3 anchor: AuthState is the user-visible decision the
        // calling feature renders ("you are signed in" vs "try again").
        assertEquals(AuthState.Authenticated, parser.parse(html).state)
    }

    @Test
    fun `wrong-password failure fixture is classified as WrongPassword and AuthState_Unauthenticated`() {
        val html = loader.load("login", "failure-wrong-password-2026-04-30.html")
        val classification = parser.classify(html)
        assertEquals(RuTorLoginParser.Outcome.WrongPassword, classification.outcome)
        assertNotNull("wrong-password classifier must surface an error message", classification.errorMessage)
        assertTrue(
            "error message should contain the Russian failure phrase, got '${classification.errorMessage}'",
            classification.errorMessage!!.contains("Неверный логин или пароль"),
        )
        assertEquals(AuthState.Unauthenticated, parser.parse(html).state)
    }

    @Test
    fun `account-locked re-rendered form is classified as AccountLocked`() {
        // Account-locked is the same shape as wrong-password but with a different error
        // string. We synthesize it by replacing the marker text in the wrong-password
        // fixture — staying in the same DOM idiom rutor uses.
        val baseHtml = loader.load("login", "failure-wrong-password-2026-04-30.html")
        val lockedHtml = baseHtml.replace(
            "Неверный логин или пароль",
            "Учётная запись заблокирована",
        )
        val classification = parser.classify(lockedHtml)
        assertEquals(RuTorLoginParser.Outcome.AccountLocked, classification.outcome)
        assertEquals(AuthState.Unauthenticated, parser.parse(lockedHtml).state)
    }

    @Test
    fun `bare login form GET response is classified as FormDisplayed`() {
        val html = loader.load("login", "login-form-2026-04-30.html")
        val classification = parser.classify(html)
        assertEquals(RuTorLoginParser.Outcome.FormDisplayed, classification.outcome)
        assertEquals(AuthState.Unauthenticated, parser.parse(html).state)
    }

    @Test
    fun `malformed truncated HTML does not throw and produces Malformed-or-FormDisplayed`() {
        // Truncate the login form fixture mid-form to simulate a connection drop.
        val full = loader.load("login", "login-form-2026-04-30.html")
        val truncated = full.substringBefore("<input type=\"password\"") +
            // Drop closing tags too — Jsoup will tolerate this but the form may now be
            // missing the password input, which the classifier reads as not-a-form.
            ""
        val classification = parser.classify(truncated)
        // The classifier never throws and the result must be a non-Success outcome.
        assertNotNull("classifier must return a Classification, never null", classification)
        assertTrue(
            "malformed input must NOT classify as Success — got ${classification.outcome}",
            classification.outcome != RuTorLoginParser.Outcome.Success,
        )
        assertEquals(AuthState.Unauthenticated, parser.parse(truncated).state)
    }
}
