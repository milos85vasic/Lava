package lava.tracker.nnmclub.parser

import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginResult
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Parses NNM-Club `/forum/login.php` HTML responses.
 *
 * Detection logic:
 *   - Presence of a logout link (`a[href*=logout]`) → Authenticated.
 *   - Presence of login form inputs (`input[name=username]`) → Unauthenticated.
 */
class NnmclubLoginParser @Inject constructor() {

    fun parse(html: String): LoginResult {
        val doc = Jsoup.parse(html)
        val hasLogout = doc.selectFirst("a[href*=logout]") != null
        val hasLoginForm = doc.selectFirst("input[name=username]") != null &&
            doc.selectFirst("input[name=password]") != null

        val state: AuthState = when {
            hasLogout -> AuthState.Authenticated
            else -> AuthState.Unauthenticated
        }
        return LoginResult(state = state, sessionToken = null, captchaChallenge = null)
    }
}
