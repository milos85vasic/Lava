package lava.tracker.rutor.parser

import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses rutor.info `/login.php` HTML responses.
 *
 * Real-world structural notes (verified against `login-form-2026-04-30.html`,
 * `success-target-home-2026-04-30.html`, and the hand-crafted failure fixture):
 *
 *  - On a fresh GET of /login.php, the page renders `<form action="/login.php"
 *    method="post">` with `<input name="nick">` and `<input name="password">`,
 *    and the document `<title>` is "rutor.info :: Авторизация".
 *  - On a successful POST, rutor sets a `userid` cookie and returns the home
 *    page HTML (or a 30x redirect to it). The home-page document `<title>` is
 *    "rutor.info :: Свободный торрент трекер" and there is no
 *    `<input name="nick">` form on the page. The parser uses the absence of the
 *    login form together with the home-page title as the primary signal.
 *  - On an authentication failure, rutor re-renders the login form and inserts
 *    an error message. The hand-crafted `failure-wrong-password-2026-04-30.html`
 *    fixture mirrors this with a `<div class="error">Неверный логин или пароль</div>`
 *    block — a plausible reproduction of the real shape per adaptation-D.
 *
 * The classification therefore uses three signals:
 *   1. Is the login form still present? (indicates "still on the auth surface")
 *   2. If present, is there an error block in the body?
 *   3. If absent, is the page title the home-page title?
 *
 * The parser returns a [LoginResult] with the appropriate [AuthState]:
 *   - success → AuthState.Authenticated
 *   - failure → AuthState.Unauthenticated
 *   - locked  → AuthState.Unauthenticated (locked is a flavour of failure;
 *               the parser does not currently distinguish account-locked from
 *               wrong-password in the AuthState shape, but exposes the failure
 *               reason via [Outcome] for callers that want the error string.)
 *
 * Sixth Law clause 1: detection logic was calibrated against actual rutor HTML;
 * the failure fixture is hand-crafted but follows the same DOM idioms.
 */
class RuTorLoginParser {

    enum class Outcome {
        Success,
        WrongPassword,
        AccountLocked,
        FormDisplayed,
        Malformed,
    }

    data class Classification(
        val outcome: Outcome,
        val errorMessage: String? = null,
    )

    fun classify(html: String): Classification {
        val doc = Jsoup.parse(html)
        val hasLoginForm = doc.selectFirst("form input[name=nick]") != null &&
            doc.selectFirst("form input[name=password]") != null
        val title = doc.title().trim()
        val errorBlock = doc.selectFirst(".error, #error, div#warning, .warning")
        val errorText = errorBlock?.text()?.trim()?.takeIf { it.isNotEmpty() }

        return when {
            !hasLoginForm && title.contains("Свободный торрент трекер", ignoreCase = true) ->
                Classification(Outcome.Success)
            !hasLoginForm && doc.body() == null ->
                Classification(Outcome.Malformed)
            hasLoginForm && isAccountLocked(errorText, doc) ->
                Classification(Outcome.AccountLocked, errorText)
            hasLoginForm && isWrongPassword(errorText, doc) ->
                Classification(Outcome.WrongPassword, errorText)
            hasLoginForm ->
                Classification(Outcome.FormDisplayed)
            else ->
                // No form, no recognizable home title — could be a redirect intermediate
                // or a malformed response.
                Classification(Outcome.Malformed)
        }
    }

    /** Convenience wrapper that maps outcome → SDK AuthState. */
    fun parse(html: String): LoginResult {
        val classification = classify(html)
        val state: AuthState = when (classification.outcome) {
            Outcome.Success -> AuthState.Authenticated
            else -> AuthState.Unauthenticated
        }
        return LoginResult(state = state, sessionToken = null, captchaChallenge = null)
    }

    private fun isAccountLocked(errorText: String?, doc: Document): Boolean {
        val hay = (errorText.orEmpty() + " " + doc.body()?.text().orEmpty()).lowercase()
        return ACCOUNT_LOCKED_MARKERS.any { hay.contains(it) }
    }

    private fun isWrongPassword(errorText: String?, doc: Document): Boolean {
        val hay = (errorText.orEmpty() + " " + doc.body()?.text().orEmpty()).lowercase()
        return WRONG_PASSWORD_MARKERS.any { hay.contains(it) }
    }

    companion object {
        private val WRONG_PASSWORD_MARKERS = listOf(
            "неверный логин",
            "неверный пароль",
            "неправильный пароль",
            "неправильный логин",
            "incorrect password",
        )
        private val ACCOUNT_LOCKED_MARKERS = listOf(
            "учётная запись заблокирована",
            "учетная запись заблокирована",
            "account locked",
            "аккаунт заблокирован",
            "ваш аккаунт заблокирован",
        )
    }
}
