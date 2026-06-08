/*
 * Challenge Test C38 — IPTorrents Jackett-delegating search (clause 6.G clause 4).
 *
 * IPTorrents is the 7th provider and the ONLY Jackett-delegating one: it gates
 * every request behind Cloudflare, so the app NEVER talks to iptorrents.com.
 * Instead [IPTorrentsSearch] delegates to the local lava-api-go sidecar's
 * `GET /jackett/search?indexer=iptorrents&q=<query>` route, which drives Jackett's
 * IPTorrents Cardigann definition + FlareSolverr (Cloudflare bypass) server-side.
 * Per §6.G clause 4 every provider shown in onboarding MUST have a Compose UI
 * Challenge that traverses the real production stack from the user's first tap to
 * a rendered result row — the JVM capability-honesty + real-stack + delegation
 * unit tests cover the wiring, but only THIS device Challenge proves a real user
 * can complete the flow on a real device. IPTorrents had no such Challenge until
 * now (the gap §6.G clause 4 exists to close).
 *
 * Auth posture: from the APP's perspective IPTorrents is anonymous — there is NO
 * [AuthenticatableTracker] impl and NO app-side FORM_LOGIN. IPTorrents
 * credentials live SERVER-SIDE inside Jackett's gitignored config volume (§6.H);
 * the app sends only the search query. So the onboarding flow is the same
 * "pick provider → Continue (no credentials) → main app → search" shape as C11
 * (Internet Archive, AuthType.NONE) — this test mirrors C11 deliberately.
 *
 * Flow (mirrors C11 end-to-end):
 *   1. Onboarding Welcome → Get Started
 *   2. Provider list → deselect the others, pick "IPTorrents"
 *   3. Configure IPTorrents → Continue (no app-side credentials)
 *   4. Summary "All set!" → Start Exploring
 *   5. Main app Search tab renders the authorized "Search history" empty state
 *   6. Tap the AppBar Search action → SearchInput → type a query → IME submit
 *   7. SearchResultScreen renders a REAL result row from the live IPTorrents
 *      indexer (a TopicListItem's FavoriteButton, content-desc "Favorite" — the
 *      robust per-row signal that appears ONLY when a result row composes, never
 *      on the "Nothing found" empty state nor on the provider filter chip).
 *
 * Primary assertion (Sixth Law clause 3 / §6.AB): the rendered IPTorrents RESULT
 * ROW, reached by traversing the real onboarding + the real
 * Search → SearchInput → SearchResult production chain end-to-end against the
 * running lava-api-go /jackett/search sidecar (Jackett + FlareSolverr + the live
 * IPTorrents indexer) — not merely the authorized empty state.
 *
 * Gating (clause 6.G clause 5 / §6.J clause 5 — no bluffed pass):
 *   - assumeTrue(IPTorrentsDescriptor.verified): IPTorrents ships verified=false
 *     (fail-closed), so it is HIDDEN from onboarding and this test cleanly SKIPS
 *     until the operator flips verified=true alongside a real sidecar run.
 *     Skipped is visible in the report and does NOT count as PASS — identical to
 *     C09/C10/C11's verified gate.
 *   - assumeTrue(IPTorrentsConfig.resolve() != null): the Jackett+FlareSolverr
 *     sidecar base URL MUST be configured (via -DiptorrentsJackettBaseUrl or the
 *     IPTORRENTS_JACKETT_BASE_URL env var, §6.R — never hardcoded). Without a
 *     reachable sidecar the search has no route to hit; skipping here is honest
 *     (it does NOT silently green a broken provider), and a misconfigured-but-
 *     present sidecar fails LOUDLY at step 7 (the result-row waitUntil times out),
 *     never a false green.
 *
 * Network reality: step 7 crosses the real local sidecar → Jackett → FlareSolverr
 * → IPTorrents path. On the gate-host that whole chain MUST be up (real-stack,
 * Seventh Law clause 2); the test fails loudly (timeout) on an empty result list
 * — no silent green.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In core/tracker/iptorrents/.../model/JackettResultMapper.kt make `map`
 *      return `SearchResult(items = emptyList(), ...)` (or have the sidecar route
 *      return zero results). The SearchResultScreen still composes — it just
 *      shows the "Nothing found" empty state (a NON-crashing break).
 *   2. Re-run on the gating emulator with the sidecar up.
 *   3. Expected failure: no result row renders; the step-7 waitUntil for the
 *      "Favorite" per-row content-description times out and the final require(...)
 *      fails with "IPTorrents search must render at least one result row".
 *   4. Revert; re-run; the result row renders and the test passes.
 *
 *   The onboarding-layer falsifiability (revert the AuthType.NONE-equivalent
 *   no-credentials short-circuit in ProviderLoginViewModel → Continue spinner
 *   persists, step 5 "Search history" times out) is preserved from C11.
 *
 * // covers-feature: iptorrents
 */
package lava.app.challenges

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import lava.tracker.iptorrents.IPTorrentsConfig
import lava.tracker.iptorrents.IPTorrentsDescriptor
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge38IPTorrentsSearchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickIPTorrents_continue_thenSearch_rendersRealResultRow() {
        hiltRule.inject()

        // Clause 6.G gate: IPTorrents ships verified=false (fail-closed), so it is
        // hidden from onboarding until the operator flips it alongside a real
        // sidecar run. Clean skip until then — never a green-with-broken bluff.
        //
        // KNOWN BLOCKER once enabled: this flow navigates search → search_input →
        // search_result, which currently triggers the OPEN androidx navigation
        // teardown crash that also keeps C11 RED (search_input NavBackStackEntry
        // INITIALIZED at activity-destroy). See
        // .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json
        // — C38 will pass once BOTH the sidecar is configured AND that nav bug is fixed.
        assumeTrue(
            "IPTorrentsDescriptor.verified must be true for this test to apply (clause 6.G)",
            IPTorrentsDescriptor.verified,
        )
        // Sidecar gate: the Jackett+FlareSolverr lava-api-go sidecar base URL MUST
        // be configured (§6.R — supplied via -DiptorrentsJackettBaseUrl or the
        // IPTORRENTS_JACKETT_BASE_URL env var). Without it the search has no route
        // to hit; skipping here is honest.
        assumeTrue(
            "IPTorrents sidecar base URL must be configured " +
                "(-D${IPTorrentsConfig.SYSTEM_PROPERTY} / ${IPTorrentsConfig.ENV_VAR}) for this test",
            IPTorrentsConfig.resolve() != null,
        )

        // Step 1: Welcome screen.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: provider list — deselect the defaults, pick IPTorrents.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        arrayOf("RuTracker.org", "RuTor.info", "Project Gutenberg", "Internet Archive").forEach { name ->
            try { composeRule.onNodeWithText(name).performClick() } catch (_: AssertionError) { }
        }
        composeRule.onNodeWithText(IPTorrentsDescriptor.displayName).performClick()
        composeRule.onNodeWithText("Next").performClick()

        // Step 3: configure — IPTorrents is anonymous from the app's perspective
        // (Jackett authenticates server-side), so the user just taps Continue.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Configure ${IPTorrentsDescriptor.displayName}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // Step 4: Summary screen.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Step 5: main app mounts and the Search tab renders the AUTHORIZED empty
        // state ("Search history") — proves the no-credentials Continue unblocked
        // search (not the "Authorization required to search" state).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }

        // Step 6: drive a real IPTorrents search via the sidecar.
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Step 7: SearchResultScreen — assert a real IPTorrents RESULT ROW renders.
        // Each TopicListItem result row carries a FavoriteButton (content-desc
        // "Favorite"). It appears ONLY when a result row composes — NOT on the
        // "Nothing found" empty state and NOT on the provider filter chip. An
        // empty-results mutation (or a dead sidecar) therefore makes this time out.
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty()
        }
        require(
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty(),
        ) { "IPTorrents search must render at least one result row (FavoriteButton)" }
    }
}
