/*
 * Challenge Test C39 — Dynamic provider discovery from the chosen API
 * (Dynamic Provider Discovery feature, spec
 * docs/superpowers/specs/2026-06-11-dynamic-provider-discovery-design.md §6,
 * plan Phase 6 Task 6.1).
 *
 * The user picks an API instance during onboarding; the client fetches that
 * API's provider catalogue (`GET /v1/providers`), maps each entry to a
 * RemoteTrackerDescriptor, and renders the provider list DYNAMICALLY — so the
 * list reflects what THAT API supports, not the 7 compiled-in providers. This
 * Challenge proves a real user can complete the discover → select → search flow
 * end-to-end and that the rendered list is genuinely API-sourced.
 *
 * The load-bearing anti-bluff signal is step 3: it asserts a provider the
 * BUNDLED/compiled-in set does NOT contain (e.g. a Jackett indexer like
 * "1337x") appears in the rendered onboarding provider list. A static/bundled
 * list physically cannot show that provider; only a list populated FROM the
 * API can. That single assertion discriminates "dynamic discovery works" from
 * "we fell back to the bundled 7".
 *
 * Flow (mirrors C20/C38 onboarding shape, plus the C26 ApiSelection step):
 *   1. Onboarding Welcome → Get Started
 *   2. ApiSelection "Choose your API" → wait for a discovered API → select it →
 *      connectivity probe succeeds → advance (the catalogue fetch fires here)
 *   3. Provider list "Pick your providers" → assert the API-ONLY provider row
 *      renders (the bundled set lacks it) — the discrimination assertion
 *   4. Deselect bundled defaults, select the API-only provider, Next
 *   5. Configure <provider> → Continue (the discovered Jackett indexer is
 *      AuthType.NONE — no app-side credentials)
 *   6. Summary "All set!" → Start Exploring
 *   7. Main app Search tab → Search action → type query → IME submit
 *   8. SearchResultScreen renders a REAL result row (TopicListItem's
 *      FavoriteButton, content-desc "Favorite") served via the API-backed
 *      client routing /v1/<provider>/search — never the "Nothing found" state.
 *
 * Primary assertion (Sixth Law clause 3 / §6.AB clause 1): the rendered
 * user-visible state at TWO points — (a) the API-only provider row in the
 * dynamic onboarding list (step 3), and (b) the real result ROW (step 8) —
 * reached by traversing the real onboarding → ApiSelection → dynamic provider
 * list → Search → SearchInput → SearchResult production chain against the
 * running lava-api-go serving GET /v1/providers + /v1/<provider>/search. Not a
 * synthetic VM call; not a bundled-list assertion.
 *
 * Gating (clause 6.G clause 5 / §6.J clause 5 — no bluffed pass): the dynamic
 * discovery gate host MUST be configured (a running api-app exposing
 * /v1/providers with at least one API-only provider, discoverable in
 * ApiSelection). Supplied via -DlavaDynamicDiscoveryGate / the
 * LAVA_DYNAMIC_DISCOVERY_GATE env var (§6.R — never hardcoded). Without it the
 * test cleanly SKIPS (visible in the report, NOT counted as PASS — identical to
 * C38's verified/sidecar gates). A misconfigured-but-present gate fails LOUDLY
 * (the step-3 API-only provider node, or the step-8 result row, times out),
 * never a false green.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In the catalogue path make the discovery fetch return an empty
 *      catalogue (e.g. ProviderCatalogRepository.fetchProviders returns
 *      Result.success(emptyList()), OR DynamicTrackerRegistry.populateFrom is
 *      given an empty list so it falls back to the bundled 7). The onboarding
 *      provider list still composes — it just shows the bundled providers (a
 *      NON-crashing break, exactly the §6.AB / §5 fallback path).
 *   2. Re-run on the gating emulator with the api-app up.
 *   3. Expected failure: the API-only provider never renders; the step-3
 *      waitUntil for the expected provider name times out and the final
 *      require(...) fails with "Dynamic provider discovery must surface the
 *      API-only provider '<name>' in the onboarding list (it is absent from
 *      the bundled set)".
 *   4. Revert; re-run; the API-only provider renders, search returns a real
 *      row, and the test passes.
 *
 *   Secondary (search-layer) falsifiability: have the API-backed client's
 *   search mapper return emptyList() → step 8 shows "Nothing found", the
 *   "Favorite" per-row wait times out, the result-row require(...) fails.
 *
 * // covers-feature: onboarding
 * // covers-feature: provider_config
 *
 * INTEGRATION NOTES (reconciled 2026-06-11):
 *  - apiSelectionEnabled: TestOnboardingHiltModule binds the flag from
 *    [lava.app.di.ApiSelectionTestFlag] (defaults FALSE for the legacy-flow
 *    Challenges). This test flips it TRUE via [apiSelectionRule] BEFORE
 *    MainActivity composes, so it reaches the production Welcome → ApiSelection
 *    → Providers flow and "Choose your API". The flag is reset after the test.
 *  - ApiSelection endpoint-row selection (step 2): C26 renders discovered
 *    endpoints addressed by exact "host:port" text — the test cannot know the
 *    mDNS-discovered host:port a priori. ASSUMPTION: a discovered API row is
 *    selectable; this test selects the first discovered endpoint by a planned
 *    content-description "api-endpoint-row". If the production rows expose a
 *    different selector, reconcile here. (No host/port is hardcoded, §6.R.)
 *  - Provider-list step label "Pick your providers" + per-provider row text =
 *    the provider displayName from the RemoteTrackerDescriptor mapping. Verified
 *    label for the bundled flow (C20/C22); ASSUMED unchanged for the dynamic
 *    list. The API-only provider displayName (default "1337x") comes from the
 *    served catalogue; supply the real one via -DlavaDynamicProviderName if the
 *    gate host configures a different indexer.
 *  - AuthType.NONE Continue copy ("Continue") + Summary "All set!" + main
 *    "Search history" + result-row "Favorite" content-desc are VERIFIED against
 *    C20/C22/C38; assumed stable for API-backed providers.
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
import lava.app.di.ApiSelectionTestFlag
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge39DynamicProviderDiscoveryTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    // Per-class override (task §4): this test REQUIRES the ApiSelection step
    // (apiSelectionEnabled = true) to reach "Choose your API". The
    // TestOnboardingHiltModule binds the flag from ApiSelectionTestFlag, which
    // defaults false for the legacy-flow Challenges; flip it true BEFORE the
    // composeRule (order 2) launches MainActivity so the onboarding graph reads
    // it. An order-1.x rule wrapping the flag mutation guarantees it runs after
    // Hilt setup but before the activity composes.
    @get:Rule(order = 2)
    val apiSelectionRule: org.junit.rules.ExternalResource =
        object : org.junit.rules.ExternalResource() {
            override fun before() { ApiSelectionTestFlag.enabled = true }
            override fun after() { ApiSelectionTestFlag.reset() }
        }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun ensureApiSelectionEnabled() {
        // Belt-and-suspenders: the rule above sets this, but keep the explicit
        // intent visible at the test-method seam (no-op if already true).
        ApiSelectionTestFlag.enabled = true
    }

    @After
    fun restoreApiSelectionFlag() {
        ApiSelectionTestFlag.reset()
    }

    @Test
    fun chooseApi_dynamicListShowsApiOnlyProvider_thenSearchRendersRealRow() {
        hiltRule.inject()

        // Gate: the dynamic-discovery gate host (running api-app exposing
        // /v1/providers with at least one API-only provider) MUST be configured
        // via -DlavaDynamicDiscoveryGate / LAVA_DYNAMIC_DISCOVERY_GATE (§6.R —
        // never hardcoded). Clean skip until then; never a green-with-broken bluff.
        assumeTrue(
            "Dynamic discovery gate host must be configured " +
                "(-D$SYS_GATE / $ENV_GATE=true) for this test to apply (clause 6.G)",
            gateEnabled(),
        )

        // The API-only provider expected in the dynamic list — a provider the
        // BUNDLED/compiled-in set does NOT contain. Default "1337x" (spec §4.1
        // example Jackett indexer); override via -DlavaDynamicProviderName /
        // LAVA_DYNAMIC_PROVIDER_NAME to match the gate host's configured indexer.
        val apiOnlyProvider = expectedApiOnlyProvider()

        // Step 1: Welcome screen.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: ApiSelection — "Choose your API". Wait for a discovered API to
        // appear, select it, and let the connectivity probe advance us to the
        // (now dynamic) provider list. The catalogue fetch fires on probe success.
        // apiSelectionEnabled=true is supplied per-class via ApiSelectionTestFlag
        // (set by apiSelectionRule before MainActivity composes); the discovered
        // API rows are selected by the "api-endpoint-row" content-description.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Choose your API").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("api-endpoint-row").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("api-endpoint-row").onFirst().performClick()

        // Step 3 (discrimination assertion): the dynamic provider list renders
        // the API-only provider. A bundled list cannot show this — only a list
        // populated FROM the API can.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(apiOnlyProvider).fetchSemanticsNodes().isNotEmpty()
        }
        require(
            composeRule.onAllNodesWithText(apiOnlyProvider).fetchSemanticsNodes().isNotEmpty(),
        ) {
            "Dynamic provider discovery must surface the API-only provider " +
                "'$apiOnlyProvider' in the onboarding list (it is absent from the bundled set)"
        }

        // Step 4: deselect the bundled defaults, keep only the API-only provider.
        arrayOf(
            "RuTracker.org", "RuTor.info", "Kinozal.tv", "NNM-Club",
            "Project Gutenberg", "Internet Archive",
        ).forEach { name ->
            try { composeRule.onNodeWithText(name).performClick() } catch (_: AssertionError) { }
        }
        composeRule.onNodeWithText(apiOnlyProvider).performClick()
        composeRule.onNodeWithText("Next").performClick()

        // Step 5: configure — the discovered Jackett indexer is AuthType.NONE,
        // so the user just taps Continue (no app-side credentials).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Configure $apiOnlyProvider").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // Step 6: Summary.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Step 7: main app — Search tab renders the authorized empty state.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Step 8: SearchResultScreen — assert a REAL result ROW renders, served
        // through the API-backed client (/v1/<provider>/search). The FavoriteButton
        // (content-desc "Favorite") appears ONLY when a row composes — never on
        // the "Nothing found" empty state. An empty catalogue or dead API makes
        // this time out (no silent green).
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty()
        }
        require(
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty(),
        ) { "API-backed search on '$apiOnlyProvider' must render at least one result row (FavoriteButton)" }
    }

    private companion object {
        // §6.R-compliant seams — no hardcoded host/port/credential. The gate
        // host supplies these at runtime via JVM system property or env var.
        const val SYS_GATE = "lavaDynamicDiscoveryGate"
        const val ENV_GATE = "LAVA_DYNAMIC_DISCOVERY_GATE"
        const val SYS_PROVIDER = "lavaDynamicProviderName"
        const val ENV_PROVIDER = "LAVA_DYNAMIC_PROVIDER_NAME"

        // Default API-only provider display label (spec §4.1 example indexer).
        // Not a host/port/secret — a provider name, like the displayNames C20/C38
        // assert. Overridable so the gate host can configure a different indexer.
        const val DEFAULT_API_ONLY_PROVIDER = "1337x"

        fun gateEnabled(): Boolean {
            val v = System.getProperty(SYS_GATE)?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv(ENV_GATE)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return false
            return v.equals("true", ignoreCase = true) || v == "1"
        }

        fun expectedApiOnlyProvider(): String =
            System.getProperty(SYS_PROVIDER)?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv(ENV_PROVIDER)?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_API_ONLY_PROVIDER
    }
}
