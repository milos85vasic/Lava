/*
 * Challenge Test C40 — Jackett indexer as a first-class discoverable provider
 * (Dynamic Provider Discovery feature, spec
 * docs/superpowers/specs/2026-06-11-dynamic-provider-discovery-design.md §4.1 +
 * §6, plan Phase 6 Task 6.2).
 *
 * The API enumerates Jackett's configured indexers and exposes EACH as its own
 * discoverable provider (spec operator decision #2: "each indexer = a
 * provider"). The client renders them in the onboarding provider list like any
 * native provider and routes every operation through the generic API-backed
 * client (/v1/<indexer>/{search,download}). This Challenge proves a real user
 * can pick a Jackett indexer, search it, and reach a download — end-to-end, on
 * a real device, against the running api-app + Jackett sidecar.
 *
 * This differs from C38 (IPTorrents): C38 used the legacy /jackett/search
 * back-compat route with a hand-wired IPTorrents tracker module. C40 exercises
 * the NEW uniform path — the indexer arrives via GET /v1/providers as a
 * RemoteTrackerDescriptor and flows through ApiBackedTrackerClient, with NO
 * per-indexer Kotlin parser. The discrimination signal is that the indexer
 * appears as a provider AT ALL (the bundled set has no such indexer).
 *
 * Flow (mirrors C39 onboarding + C06 download tail):
 *   1. Welcome → Get Started
 *   2. ApiSelection "Choose your API" → select discovered API → probe advances
 *      → catalogue fetch (incl. Jackett indexers) fires
 *   3. Provider list → assert the Jackett indexer row renders (the
 *      discrimination assertion — bundled set lacks it)
 *   4. Deselect bundled defaults, select the indexer, Next
 *   5. Configure <indexer> → Continue (Jackett indexers are AuthType.NONE;
 *      indexer credentials live server-side in Jackett, §6.H)
 *   6. Summary "All set!" → Start Exploring
 *   7. Main app Search → type query → IME submit
 *   8. SearchResultScreen renders a REAL result row (content-desc "Favorite")
 *   9. Tap the row → TopicScreen → tap Torrent (or Magnet) → DownloadDialog
 *      (or MagnetDialog "Open") renders — DOWNLOAD REACHABLE for the indexer.
 *
 * Primary assertion (Sixth Law clause 3 / §6.AB clause 1): the rendered
 * user-visible state at THREE points — (a) the Jackett-indexer provider row in
 * the dynamic onboarding list (step 3), (b) the real result ROW (step 8), and
 * (c) the download affordance (step 9, DownloadDialog/MagnetDialog) — reached
 * by traversing the real onboarding → dynamic list → Search → SearchResult →
 * Topic → Download production chain against the running api-app that delegates
 * /v1/<indexer>/{search,download} to the Jackett sidecar.
 *
 * Gating (clause 6.G clause 5 / §6.J clause 5 — no bluffed pass): the gate host
 * MUST run an api-app with Jackett enabled exposing at least one configured
 * indexer through /v1/providers. Supplied via -DlavaDynamicDiscoveryGate /
 * LAVA_DYNAMIC_DISCOVERY_GATE (§6.R). The indexer name defaults to "1337x" and
 * is overridable via -DlavaJackettIndexerName / LAVA_JACKETT_INDEXER_NAME to
 * match the gate host's configured indexer. Clean SKIP when unset (NOT a PASS);
 * a misconfigured-but-present gate fails LOUDLY (the indexer row, the result
 * row, or the download dialog times out), never a false green.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. At api-app startup wiring (router registration of Jackett indexers as
 *      providers), skip the per-indexer registration loop so the indexers are
 *      NOT added to the registry (or have ListIndexers return an empty list).
 *      The api-app still serves native providers; GET /v1/providers still
 *      returns 200 (a NON-crashing break — the catalogue just omits indexers).
 *   2. Re-run on the gating emulator with the api-app up.
 *   3. Expected failure: the Jackett indexer never renders in the onboarding
 *      list; the step-3 waitUntil for the indexer name times out and the
 *      require(...) fails with "Jackett indexer '<name>' must appear as a
 *      discoverable provider (it is absent from the bundled set)".
 *   4. Revert; re-run; the indexer renders, search returns a real row, the
 *      download dialog appears, and the test passes.
 *
 *   Secondary (download-layer) falsifiability: drop the TorrentFileClick
 *   side-effect in TopicViewModel (cf. C06) → step 9 shows no DownloadDialog,
 *   the wait times out, the download require(...) fails.
 *
 * // covers-feature: provider_config
 * // covers-feature: topic
 *
 * INTEGRATION NOTES (reconciled 2026-06-11):
 *  - apiSelectionEnabled: TestOnboardingHiltModule binds the flag from
 *    [lava.app.di.ApiSelectionTestFlag] (defaults FALSE). This test flips it
 *    TRUE via [apiSelectionRule] before MainActivity composes, reaching the
 *    production Welcome → ApiSelection → Providers flow; reset after the test.
 *  - "api-endpoint-row" content-description for the discovered API endpoint row
 *    in ApiSelection (step 2): ASSUMED selector — C26 addresses rows by exact
 *    "host:port" text which the test cannot know a priori (§6.R, no hardcoding).
 *    Reconcile if production exposes a different selector.
 *  - The Jackett indexer displayName in the rendered list = the RemoteTracker-
 *    Descriptor.displayName mapped from ProviderDescriptorDto.displayName (spec
 *    §4.1: jackett entry has displayName == indexer name, default "1337x").
 *  - Step 9 download surface strings ("Torrent"/"Magnet" actions, "Download
 *    completed"/"Download in progress" dialog, "Open") are VERIFIED against C06
 *    for native providers; ASSUMED the API-backed client surfaces the same
 *    TopicScreen download UI for a Jackett-sourced topic. If a Jackett indexer
 *    yields magnet-only topics, the magnet branch (mirrored from C06) covers it.
 */
package lava.app.challenges

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
class Challenge40JackettIndexerProviderTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    // Per-class override (task §4): this test REQUIRES the ApiSelection step
    // (apiSelectionEnabled = true). Flip the TestOnboardingHiltModule-read flag
    // true BEFORE the composeRule (order 3) launches MainActivity.
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
        ApiSelectionTestFlag.enabled = true
    }

    @After
    fun restoreApiSelectionFlag() {
        ApiSelectionTestFlag.reset()
    }

    @Test
    fun jackettIndexer_appearsAsProvider_search_thenDownloadReachable() {
        hiltRule.inject()

        // Gate: api-app with Jackett enabled + at least one configured indexer
        // in /v1/providers MUST be reachable (§6.R seam, never hardcoded). Clean
        // skip until then; never a green-with-broken bluff.
        assumeTrue(
            "Dynamic discovery gate host with Jackett indexers must be configured " +
                "(-D$SYS_GATE / $ENV_GATE=true) for this test to apply (clause 6.G)",
            gateEnabled(),
        )

        // The Jackett indexer expected as a discoverable provider (default
        // "1337x"; override via -DlavaJackettIndexerName to match the gate host).
        val indexer = expectedJackettIndexer()

        // Step 1: Welcome.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: ApiSelection → select discovered API → probe advances.
        // apiSelectionEnabled=true is supplied per-class via ApiSelectionTestFlag
        // (apiSelectionRule); rows are selected by the "api-endpoint-row" desc.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Choose your API").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("api-endpoint-row").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("api-endpoint-row").onFirst().performClick()

        // Step 3 (discrimination assertion): the dynamic provider list renders
        // the Jackett indexer as a provider — the bundled set has no such indexer.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(indexer).fetchSemanticsNodes().isNotEmpty()
        }
        require(
            composeRule.onAllNodesWithText(indexer).fetchSemanticsNodes().isNotEmpty(),
        ) {
            "Jackett indexer '$indexer' must appear as a discoverable provider " +
                "(it is absent from the bundled set)"
        }

        // Step 4: keep only the indexer.
        arrayOf(
            "RuTracker.org",
            "RuTor.info",
            "Kinozal.tv",
            "NNM-Club",
            "Project Gutenberg",
            "Internet Archive",
        ).forEach { name ->
            try { composeRule.onNodeWithText(name).performClick() } catch (_: AssertionError) { }
        }
        composeRule.onNodeWithText(indexer).performClick()
        composeRule.onNodeWithText("Next").performClick()

        // Step 5: configure — Jackett indexers are AuthType.NONE (creds live
        // server-side in Jackett, §6.H); the user just taps Continue.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Configure $indexer").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // Step 6: Summary → Start Exploring.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Step 7: main app → Search → type → IME submit.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Step 8: a REAL result ROW (FavoriteButton) renders via the API-backed
        // client routing /v1/<indexer>/search → Jackett sidecar. Times out on
        // an empty result list — no silent green.
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty()
        }
        require(
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty(),
        ) { "Jackett indexer '$indexer' search must render at least one result row (FavoriteButton)" }

        // Step 9: tap the row → TopicScreen → reach a download. Tap the ROW
        // Surface (clickable + has a "Favorite" descendant), not the icon (cf. C06).
        composeRule.onAllNodes(
            hasClickAction() and hasAnyDescendant(hasContentDescription("Favorite")),
        ).onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Torrent").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Magnet").fetchSemanticsNodes().isNotEmpty()
        }

        val hasTorrent = composeRule.onAllNodesWithText("Torrent").fetchSemanticsNodes().isNotEmpty()
        if (hasTorrent) {
            composeRule.onNodeWithText("Torrent").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule.onAllNodesWithText("Download completed").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithText("Download in progress").fetchSemanticsNodes().isNotEmpty()
            }
            require(
                composeRule.onAllNodesWithText("Download completed").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithText("Download in progress").fetchSemanticsNodes().isNotEmpty(),
            ) { "Tapping Torrent on the Jackett-indexer topic must surface the DownloadDialog" }
        } else {
            // Jackett indexers commonly yield magnet-only topics — the magnet
            // affordance is an equally real download path (mirrored from C06).
            composeRule.onNodeWithText("Magnet").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty()
            }
            require(
                composeRule.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty(),
            ) { "Tapping Magnet on the Jackett-indexer topic must surface the MagnetDialog with an Open action" }
        }
    }

    private companion object {
        // §6.R-compliant seams — no hardcoded host/port/credential.
        const val SYS_GATE = "lavaDynamicDiscoveryGate"
        const val ENV_GATE = "LAVA_DYNAMIC_DISCOVERY_GATE"
        const val SYS_INDEXER = "lavaJackettIndexerName"
        const val ENV_INDEXER = "LAVA_JACKETT_INDEXER_NAME"

        // Default Jackett indexer display label (spec §4.1 example). Not a
        // host/port/secret — a provider name. Overridable for the gate host.
        const val DEFAULT_INDEXER = "1337x"

        fun gateEnabled(): Boolean {
            val v = System.getProperty(SYS_GATE)?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv(ENV_GATE)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return false
            return v.equals("true", ignoreCase = true) || v == "1"
        }

        fun expectedJackettIndexer(): String =
            System.getProperty(SYS_INDEXER)?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv(ENV_INDEXER)?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_INDEXER
    }
}
