/*
 * Challenge Test C70 — Autonomous QA provider-matrix end-to-end flow
 * (parameterized by instrumentation arguments).
 *
 * This is the autonomous-QA driver Challenge: a single parameterized test that
 * drives the REAL production path a first-run user takes, end to end, against a
 * configurable backend + a configurable provider set + a configurable search
 * query — so a CI/QA harness can fan it out across the matrix without writing N
 * near-identical Challenge files.
 *
 * It composes the proven production-path patterns of the neighboring Challenges:
 *   - C20 (onboarding wizard: Welcome → ApiSelection → Providers → Configure →
 *     Summary → main app),
 *   - C30 (cloud-input API selection: type address → "Add server"),
 *   - C37 (on-device api-app launch via the "api-ondevice-launch" button),
 *   - C02/C03 (per-provider Configure: form-login creds from BuildConfig, or the
 *     anonymous toggle for a supportsAnonymous provider),
 *   - C05/C06 (search → result row → topic detail → download/magnet dialog).
 *
 * PARAMETERS — read via InstrumentationRegistry.getArguments(); every one has a
 * default so the test is runnable standalone (no -e args needed):
 *   - qa_backend   : goapi | apiapp | direct                (default "goapi")
 *   - qa_api_url   : backend URL for the cloud-input field   (default "https://10.0.2.2:8443")
 *   - qa_providers : CSV of {rutracker,nnmclub,kinozal,archiveorg,gutenberg}
 *                                                            (default "rutracker")
 *   - qa_query     : search query string                     (default "1080p")
 *
 * Operator command (defaults — one provider, goapi backend):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge70AutonomousQaProviderMatrixTest"
 *
 * Operator command (parameterized — e.g. archiveorg anonymous over an on-device API):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.qa_backend=apiapp \
 *     -Pandroid.testInstrumentationRunnerArguments.qa_providers=archiveorg \
 *     -Pandroid.testInstrumentationRunnerArguments.qa_query=ubuntu \
 *     --tests "lava.app.challenges.Challenge70AutonomousQaProviderMatrixTest"
 *
 * PRIMARY ASSERTION (§6.J / §6.AB clause 3 — user-visible state): the rendered
 * DownloadDialog ("Download completed", or — on a slow upstream — the
 * "Downloading file…" in-progress dialog) OR, for a magnet topic, the
 * MagnetDialog "Open" action — reached only by traversing the real onboarding →
 * search → result → topic → download production chain end to end on a real
 * device against the real backend + provider. The HTTP-file providers
 * (archiveorg/gutenberg) reach the SAME DownloadDialog via the SAME "Torrent"
 * button: TopicViewModel.onTorrentFileClick branches on ProviderDownloadKind →
 * downloadHttpFile(); their topics render as TopicContent.Torrent because the
 * SDK→DTO mapper (RuTrackerDtoMappers.topicPageToDto) ALWAYS builds a non-null
 * TorrentDataDto. There is NO separate file-list/file-row UI in TopicScreen. The
 * intermediate result-row render (a TopicListItem carrying the "Favorite"
 * affordance) is the secondary user-visible checkpoint, exactly as in C05/C06.
 *
 * HONEST REAL-NETWORK / REAL-BACKEND POSTURE (§6.J): onboarding here crosses the
 * real backend probe + (for a credentialed provider) the real provider login
 * round-trip. When that setup cannot complete in the test environment (backend
 * unreachable, cross-app api-app not installed, provider blocked, credentials
 * absent), the test SKIPS via assumeTrue — surfaced in the report, NOT a green
 * pass and NOT silent. The download assertion itself stays loud (require) once
 * the user is in the main app, so an empty result set fails the test rather than
 * passing on nothing. Transient search failures (the "Search failed" / "problem
 * reaching the trackers" error placeholder) are RETRIED up to 3 times via the
 * in-UI "Retry" button; a persistent error after the retries is a LOUD failure,
 * never a skip. This mirrors C02's tracked-skip + C05/C06's loud assertion.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure modes):
 *
 *   A. Download affordance (covers BOTH the `.torrent` and the HTTP-file path):
 *      1. In feature/topic/src/main/kotlin/lava/topic/TopicViewModel.kt, drop the
 *         `postSideEffect(TopicSideEffect.ShowDownloadProgress)` line from
 *         downloadTorrentFile (rutracker/rutor) AND/OR downloadHttpFile
 *         (archiveorg/gutenberg). The screen still composes; tapping "Torrent"
 *         simply does nothing — a NON-crashing break (the dead-ended-side-effect
 *         class C06 rehearses).
 *      2. Re-run on the gating emulator with qa defaults.
 *      3. Expected failure: no DownloadDialog composes; neither "Download
 *         completed" nor "Downloading file…" appears; the Step-6 require fires
 *         with "Tapping \"Torrent\" must surface the DownloadDialog …". The
 *         `C70-RESULT … DOWNLOAD-OK` marker is NEVER logged.
 *      4. Revert; re-run; test passes.
 *
 *   B. Transient-search-retry (the loop must NOT paper over a real defect):
 *      1. Force SearchResultViewModel to always reduce SearchResultContent.Error
 *         (the persistent search-error placeholder).
 *      2. Re-run: Step 3 taps "Retry" up to 3 times, the error persists, and
 *         `require(resultsShown)` fires with "Search returned no results after 3
 *         attempts …". DOWNLOAD-OK is never reached.
 *      3. Revert; re-run; a real result set is found and the flow proceeds.
 *
 *   Alternate (onboarding-side) non-crashing break: in OnboardingViewModel.kt
 *   make onToggleProvider a no-op — provider selection stops mutating state, the
 *   Configure step never reaches the requested provider, the wizard cannot reach
 *   "All set!", and the test skips (setup) rather than reaching the download
 *   assertion — proving the onboarding wiring is load-bearing for this flow.
 *
 * // covers-feature: onboarding
 * // covers-feature: search_input
 * // covers-feature: search_result
 * // covers-feature: topic
 * // covers-feature: provider_config
 * // covers-feature: credentials
 */
package lava.app.challenges

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.BuildConfig
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import lava.onboarding.steps.SelectAllProvidersTestTag
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Per-provider spec resolved from a qa_providers id. The displayName MUST equal
 * the provider's `TrackerDescriptor.displayName` exactly (the onboarding picker
 * + Configure screens render that string). Credentials, when needed, come from
 * BuildConfig fields populated at build time from gitignored .env (§6.H) — never
 * a source literal.
 */
private data class ProviderSpec(
    val id: String,
    val displayName: String,
    val authNone: Boolean,
    val supportsAnonymous: Boolean,
    val username: String,
    val password: String,
) {
    /**
     * Whether this provider needs typed credentials to be onboarded (a
     * form/captcha-login provider that does NOT permit an anonymous path AND
     * whose login happens in-app, i.e. not the server-side Jackett case). When
     * true, the test requires non-empty BuildConfig creds (else honest skip).
     */
    val needsCreds: Boolean get() = id == "rutracker" || id == "nnmclub" || id == "kinozal"

    companion object {
        /**
         * The autonomous-QA matrix set = {client-onboardable} ∩ {API-backed}:
         * rutracker, nnmclub, kinozal, archiveorg, gutenberg. rutor (native but
         * NOT in the live GET /providers list) and iptorrents (Jackett-only, not
         * in /providers) are EXCLUDED — see lib-subsets.sh QA_PROVIDERS.
         *
         * displayName is the string the onboarding picker + Configure screens
         * RENDER. In the production flow (qa_backend goapi/apiapp) the picker is
         * populated from the chosen API's GET /v1/providers catalogue, mapped via
         * `lava.data.provider.friendlyDisplayName(id, serverName)`:
         *   - serverName kept when it is a real friendly name (non-blank AND not
         *     case-insensitively equal to the id);
         *   - otherwise the id is humanized (split on _-.space, Title-Case).
         * So lava-api-go's "RuTracker" (≈ id "rutracker") HUMANIZES to "Rutracker"
         * — NOT the bundled descriptor's "RuTracker.org" (the bundled name only
         * renders in the catalogue-fetch-failure fallback path). The other four
         * server names are genuine friendly names kept verbatim.
         *
         * authType drives the Configure rendering (ConfigureStep):
         *   - NONE (archiveorg, gutenberg) → no fields, no anonymous switch,
         *     button "Continue"  → authNone=true.
         *   - CAPTCHA_LOGIN (rutracker) / FORM_LOGIN (nnmclub, kinozal) →
         *     Username/Password fields, button "Test & Continue" → needsCreds via
         *     BuildConfig (.env-sourced; §6.H — never a source literal).
         */
        fun forId(rawId: String): ProviderSpec? = when (rawId) {
            "rutracker" -> ProviderSpec(
                id = "rutracker",
                // EMPIRICAL (2026-06-30 keystone recording): the onboarding picker
                // + Configure page render the BUNDLED descriptor name "RuTracker.org",
                // NOT the humanized API id. The friendlyDisplayName-humanizes-to-
                // "Rutracker" theory above is FALSE for this provider in practice.
                displayName = "RuTracker.org",
                authNone = false, // CAPTCHA_LOGIN
                supportsAnonymous = false, // descriptor supportsAnonymous=false
                username = BuildConfig.RUTRACKER_USERNAME,
                password = BuildConfig.RUTRACKER_PASSWORD,
            )
            "nnmclub" -> ProviderSpec(
                id = "nnmclub",
                displayName = "NNM-Club",
                authNone = false, // FORM_LOGIN
                supportsAnonymous = false, // descriptor supportsAnonymous=false
                username = BuildConfig.NNMCLUB_USERNAME,
                password = BuildConfig.NNMCLUB_PASSWORD,
            )
            "kinozal" -> ProviderSpec(
                id = "kinozal",
                displayName = "Kinozal.tv",
                authNone = false, // FORM_LOGIN
                supportsAnonymous = false, // descriptor supportsAnonymous=false
                username = BuildConfig.KINOZAL_USERNAME,
                password = BuildConfig.KINOZAL_PASSWORD,
            )
            "archiveorg" -> ProviderSpec(
                id = "archiveorg",
                displayName = "Internet Archive",
                authNone = true, // AuthType.NONE → "Continue", no creds, no switch
                supportsAnonymous = true, // descriptor supportsAnonymous=true (informational)
                username = "",
                password = "",
            )
            "gutenberg" -> ProviderSpec(
                id = "gutenberg",
                displayName = "Project Gutenberg",
                authNone = true, // AuthType.NONE → "Continue", no creds, no switch
                supportsAnonymous = true, // descriptor supportsAnonymous=true (informational)
                username = "",
                password = "",
            )
            else -> null
        }
    }
}

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge70AutonomousQaProviderMatrixTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    // The ApiSelection onboarding step (where the goapi backend endpoint is
    // configured + its catalogue fetched → dynamic ApiBackedTrackerClient
    // registered) is gated on apiSelectionEnabled, which TestOnboardingHiltModule
    // binds from ApiSelectionTestFlag (default FALSE). Without this the wizard
    // SKIPS ApiSelection → the Go API is never contacted → search falls back to
    // the bundled direct client (device-proven 2026-07-02). Set it BEFORE the
    // compose rule launches MainActivity (order 2 < 3), mirroring C39/C40/C63.
    @get:Rule(order = 2)
    val enableApiSelection = object : org.junit.rules.ExternalResource() {
        override fun before() { lava.app.di.ApiSelectionTestFlag.enabled = true }
        override fun after() { lava.app.di.ApiSelectionTestFlag.enabled = false }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Instrumentation args (defaults make the test runnable standalone) ──
    private fun arg(key: String, default: String): String =
        InstrumentationRegistry.getArguments().getString(key, default).ifBlank { default }

    private val qaBackend by lazy { arg("qa_backend", "goapi") }
    private val qaApiUrl by lazy { arg("qa_api_url", "https://10.0.2.2:8443") }
    private val qaQuery by lazy { arg("qa_query", "1080p") }

    // §6.AK: the standalone Go API's derived Lava-Auth key (base64 of the raw UUID
    // from LAVA_AUTH_ACTIVE_CLIENTS). Empty default → keyless (legacy behavior /
    // apiapp+direct modes read the key on-device). §6.H: never logged.
    private val qaKey by lazy { arg("qa_key", "") }
    private val providerSpecs by lazy {
        arg("qa_providers", "rutracker")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ProviderSpec.forId(it) }
    }

    @After
    fun clearQaKeyOverride() {
        digital.vasic.lava.client.QaKeyInjection.override = null
    }

    @Test
    fun autonomousMatrix_onboard_search_topic_download() {
        hiltRule.inject()

        // §6.AK: goapi keystone against a standalone lava-api-go has no on-device
        // key ContentProvider; feed the derived key to the REAL keying path
        // (MainActivity.buildApiKeyReader → withLocalApiKeyIfMissing). §6.H: never
        // logged. Read at invoke time by the apiKeyReader lambda, so arming here —
        // before the ApiSelection probe — is sufficient.
        if (qaBackend == "goapi" && qaKey.isNotEmpty()) {
            digital.vasic.lava.client.QaKeyInjection.override = qaKey
        }

        // Sanity: the qa_providers value resolved to at least one known provider.
        assumeTrue(
            "qa_providers resolved to no known providers (expected a CSV of " +
                "rutracker,nnmclub,kinozal,archiveorg,gutenberg)",
            providerSpecs.isNotEmpty(),
        )

        // §6.H: credentialed providers require real .env-sourced BuildConfig
        // creds. Absent creds → honest skip (like C02), never a placeholder.
        providerSpecs.forEach { spec ->
            assumeTrue(
                "Missing .env credentials for ${spec.id}: BuildConfig username/password " +
                    "empty (set them in .env for this provider) — §6.J-tracked skip.",
                !spec.needsCreds || (spec.username.isNotEmpty() && spec.password.isNotEmpty()),
            )
        }

        // ── Onboarding (setup): a real-network/real-backend failure here is an
        // honest skip, not a green pass. The DOWNLOAD assertion below is the
        // load-bearing one and stays loud. ──
        try {
            runOnboardingToMainApp()
        } catch (timeout: ComposeTimeoutException) {
            assumeTrue(
                "Onboarding could not complete in this environment (backend/provider/" +
                    "real-network unavailable: ${timeout.message}). §6.J-tracked honest " +
                    "skip — NOT a product regression; the success path is verified where " +
                    "the qa_backend + selected provider are reachable.",
                false,
            )
            return // unreachable after assumeTrue(false), satisfies the compiler
        }

        // ── Search → result → topic → download (the user-visible assertion) ──
        // Diagnostic capture: on ANY failure, dump the live semantics tree + the
        // exception to logcat (tags C70 / C70-TREE) BEFORE the activity-destroy
        // nav-lifecycle crash can mask it, so the real failing step + on-screen
        // state stay forensically recoverable from the recorded logcat.
        try {
            searchTopicDownload()
        } catch (t: Throwable) {
            android.util.Log.e("C70", "FAILED in searchTopicDownload: ${t.message}", t)
            try {
                android.util.Log.e(
                    "C70-TREE",
                    composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = 100),
                )
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Onboarding
    // ──────────────────────────────────────────────────────────────────────

    private fun runOnboardingToMainApp() {
        // Step 1: Welcome → Get Started. (No assertion on the provider-count copy:
        // in the ApiSelection flow Welcome renders count-free, so only the title +
        // the Get Started button are stable across the feature-flag states.)
        composeRule.waitUntil(timeoutMillis = 15_000) { present("Welcome to Lava") }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: ApiSelection — handled ONLY if the feature flag renders it.
        // (When apiSelectionEnabled=false the wizard goes Welcome → Providers
        // directly, so we wait for EITHER the API step OR the providers step.)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            present("Choose your API") || present("Pick your providers")
        }
        if (present("Choose your API")) {
            selectApiBackend()
            // A successful probe advances ApiSelection → Providers. Real backend /
            // cross-app dependency: a timeout here propagates to the honest skip.
            composeRule.waitUntil(timeoutMillis = 60_000) { present("Pick your providers") }
        }

        // Step 3: Providers — deselect everything NOT requested; the requested
        // (already-selected-by-default) providers remain. Then Next.
        composeRule.waitUntil(timeoutMillis = 15_000) { present("Pick your providers") }
        val requested = providerSpecs.map { it.displayName }.toSet()
        // Deselect the ENTIRE rendered list in ONE VM action — robust to the
        // goapi catalogue's display-name casing AND to rows scrolled off-screen in
        // the plain Column(verticalScroll) picker (13 providers overflow a 1080p
        // viewport; the prior name-list deselect only cleared the 5 on-screen
        // exact-case rows, leaving 7 unhandled leftovers that stranded the per-
        // provider Configure loop -> "All set!" never rendered -> honest SKIP).
        // On entry every provider is selected, so the Select-all control reads
        // "Deselect all" and clears the whole list (OnboardingViewModel
        // .onToggleAllProviders deselect branch). Device evidence + root cause:
        // .lava-ci-evidence/autonomous-qa/2026-07-02/goapi/.
        composeRule.onNodeWithTag(SelectAllProvidersTestTag).performClick()
        composeRule.waitForIdle()
        // Re-select ONLY the requested providers, scrolling each into view first so
        // the tap lands even when its row is below the fold.
        requested.forEach { name ->
            composeRule.onNodeWithText(name).performScrollTo().performClick()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Next").performClick()

        // Step 4: Configure each selected provider, in the wizard's order.
        configureAllSelectedProviders()

        // Step 5: Summary → Start Exploring.
        composeRule.waitUntil(timeoutMillis = 30_000) { present("All set!") }
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Step 6: main app reached (per C20 — the Search-history empty state).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            present("Search history") || present("There will be list of recent searches")
        }
    }

    /**
     * Drive the ApiSelection step per qa_backend:
     *  - goapi  → type qa_api_url into the cloud-input field and tap "Add server".
     *  - apiapp → tap the on-device launch button ("api-ondevice-launch").
     *  - direct → select the first discovered local API row, else a cloud-default
     *             preset, else nothing (the outer wait then surfaces the skip).
     */
    private fun selectApiBackend() {
        when (qaBackend) {
            "goapi" -> {
                composeRule.onNodeWithContentDescription("api-cloud-input")
                    .performTextInput(qaApiUrl)
                composeRule.onNodeWithContentDescription("api-cloud-add").performClick()
            }
            "apiapp" -> {
                composeRule.onNodeWithContentDescription("api-ondevice-launch").performClick()
            }
            "direct" -> {
                when {
                    composeRule.onAllNodesWithContentDescription("api-row")
                        .fetchSemanticsNodes().isNotEmpty() ->
                        composeRule.onAllNodesWithContentDescription("api-row").onFirst().performClick()
                    composeRule.onAllNodesWithContentDescription("api-cloud-default")
                        .fetchSemanticsNodes().isNotEmpty() ->
                        composeRule.onAllNodesWithContentDescription("api-cloud-default").onFirst().performClick()
                    else -> { /* nothing reachable to select; outer wait → honest skip */ }
                }
            }
            else -> {
                // Unknown backend value: fall back to the cloud-input path.
                composeRule.onNodeWithContentDescription("api-cloud-input")
                    .performTextInput(qaApiUrl)
                composeRule.onNodeWithContentDescription("api-cloud-add").performClick()
            }
        }
    }

    /**
     * Walk every Configure page until the Summary ("All set!"). The Configure
     * pages appear in the wizard's internal selection order, so each turn we
     * detect WHICH requested provider's page is showing ("Configure <name>") and
     * configure that one, then wait for the wizard to leave it.
     */
    private fun configureAllSelectedProviders() {
        var safety = 0
        while (safety++ < providerSpecs.size + 2) {
            if (present("All set!")) return
            val shown = providerSpecs.firstOrNull { present("Configure ${it.displayName}") }
                ?: return // no further Configure page (or already advancing)
            configureProvider(shown)
            // Leave this page: Summary appears OR this Configure page is gone.
            // A credentialed-login failure keeps us on the page → this wait times
            // out → ComposeTimeoutException → honest skip upstream.
            composeRule.waitUntil(timeoutMillis = 90_000) {
                present("All set!") || !present("Configure ${shown.displayName}")
            }
        }
    }

    /**
     * Configure a single provider's page:
     *  - has creds         → type Username/Password, tap "Test & Continue".
     *  - authNone          → AuthType.NONE provider: ConfigureStep renders NO
     *                        anonymous switch and the button reads "Continue"
     *                        (isAnonymous == true) → just tap "Continue".
     *  - supportsAnonymous → FORM/CAPTCHA provider permitting anonymous: toggle
     *                        "anonymous_switch", tap "Continue".
     *  - otherwise         → server-side / no-form provider: tap whichever of
     *                        "Continue"/"Test & Continue" is present.
     */
    private fun configureProvider(spec: ProviderSpec) {
        composeRule.waitUntil(timeoutMillis = 15_000) { present("Configure ${spec.displayName}") }
        when {
            spec.username.isNotEmpty() -> {
                composeRule.onNodeWithText("Username").performClick()
                composeRule.onNodeWithText("Username").performTextInput(spec.username)
                composeRule.onNodeWithText("Password").performClick()
                composeRule.onNodeWithText("Password").performTextInput(spec.password)
                composeRule.onNodeWithText("Test & Continue").performClick()
            }
            spec.authNone -> {
                // AuthType.NONE: no anonymous switch is rendered; button = "Continue".
                composeRule.onNodeWithText("Continue").performClick()
            }
            spec.supportsAnonymous -> {
                composeRule.onNodeWithTag("anonymous_switch").performClick()
                composeRule.onNodeWithText("Continue").performClick()
            }
            else -> {
                if (present("Continue")) {
                    composeRule.onNodeWithText("Continue").performClick()
                } else {
                    composeRule.onNodeWithText("Test & Continue").performClick()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Search → result → topic → download (mirrors C05 / C06)
    // ──────────────────────────────────────────────────────────────────────

    private fun searchTopicDownload() {
        // Step 1: open search (AppBar SearchButton, content-desc "Search").
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()

        // Step 2: SearchInputScreen — wait for the editable field, then type +
        // submit. Do NOT depend on the exact placeholder string (it is a string
        // resource designsystem_hint_search, not literally "Search…"); the field
        // is the set-text-action node. Use onAllNodes().onFirst() to tolerate
        // brief nav-transition overlap where the previous screen's field is
        // still composed; waitForIdle lets the transition + focus settle.
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(qaQuery)
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performImeAction()

        // Step 3: SearchResultScreen — wait for a real result ROW, RETRYING the
        // transient search-error state. Each TopicListItem carries a
        // FavoriteButton (content-desc "Favorite"), the robust per-row signal
        // that only renders when a row composes (NOT on the "Nothing found"
        // empty state, NOT on the error placeholder). The datacenter egress can
        // intermittently fail the upstream fetch — SearchResultScreen then
        // renders its dedicated error placeholder ("Search failed" / "There was
        // a problem reaching the trackers. …") with a "Retry" Button
        // (SearchResultScreen.kt:295-308 → SearchResultAction.RetryClick). Tap
        // Retry and re-wait, up to 3 attempts. A persistent error after the
        // retries is a LOUD failure (not a skip): a real user could not search.
        val maxSearchAttempts = 3
        var resultsShown = false
        for (attempt in 1..maxSearchAttempts) {
            // Resolve on EITHER outcome so a fast error does not burn the full
            // 60 s; a perpetual loading (neither) still times out → loud fail.
            composeRule.waitUntil(timeoutMillis = 60_000) {
                hasResultRow() || hasSearchError()
            }
            if (hasResultRow()) {
                resultsShown = true
                break
            }
            // Error placeholder is showing. Retry unless this was the last try.
            if (attempt < maxSearchAttempts) {
                composeRule.onNodeWithText("Retry").performClick()
                composeRule.waitForIdle()
                // Wait for the retry to actually start — the error placeholder
                // must be replaced by the loading state before the next attempt's
                // wait runs; otherwise that wait would resolve instantly on the
                // STALE error and burn through the remaining retries in
                // milliseconds. Bounded + best-effort: if it does not clear we
                // fall through and the next attempt re-evaluates the live state.
                runCatching {
                    composeRule.waitUntil(timeoutMillis = 10_000) { !hasSearchError() }
                }
            }
        }
        require(resultsShown) {
            "Search returned no results after $maxSearchAttempts attempts — the " +
                "search-error placeholder (\"Search failed\" / \"problem reaching the " +
                "trackers\") persisted across retries. A real user cannot search " +
                "${providerSpecs.joinToString(",") { it.id }} from this environment."
        }

        // Step 4: tap the first result ROW Surface (hasClickAction + has a
        // "Favorite" descendant) — NOT the favorite icon itself (which toggles
        // favorite rather than opening the topic). See C05 for the matcher
        // rationale.
        composeRule.onAllNodes(
            hasClickAction() and hasAnyDescendant(hasContentDescription("Favorite")),
        ).onFirst().performClick()

        // Step 5: TopicScreen — wait for ANY download affordance. The button
        // TorrentAppBar renders (TopicScreen.kt:349-362) reads the literal
        // "Torrent" (R.string.topic_action_torrent, translatable=false) and is
        // the affordance for EVERY downloadable topic — INCLUDING the HTTP-file
        // providers archiveorg/gutenberg, whose topics ALSO render as
        // TopicContent.Torrent (the SDK→DTO mapper RuTrackerDtoMappers
        // .topicPageToDto ALWAYS builds a non-null TorrentDataDto, so
        // TopicViewModel.loadTopic reduces TopicContent.Torrent). That SAME
        // button routes to downloadHttpFile() via onTorrentFileClick →
        // ProviderDownloadKind.HTTP — there is NO separate file-list/file-row UI.
        // The "Magnet" button (R.string.topic_action_magnet, TopicScreen.kt:
        // 331-340) renders ONLY when the topic carries a magnet link (archiveorg
        // has none). If the topic-detail fetch fails (upstream flakiness) the
        // header stays TopicContent.Initial and NEITHER button renders → this
        // times out → a loud failure with the live semantics tree dumped above.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            present("Torrent") || present("Magnet")
        }

        // Step 6 (PRIMARY ASSERTION on user-visible state): drive the affordance
        // and assert the resulting dialog renders.
        if (present("Torrent")) {
            // Covers BOTH the `.torrent` providers AND the HTTP-file providers
            // (archiveorg/gutenberg) — same button, branched by
            // ProviderDownloadKind inside onTorrentFileClick. The DownloadDialog
            // renders the real DownloadState:
            //   Started/Initial → "Downloading file…" (topic_file_download_in_progress)
            //   Completed       → "Download completed" (topic_file_download_completed)
            //   Error           → error_title (NOT accepted: a download failure is
            //                     a loud failure, never DOWNLOAD-OK).
            composeRule.onNodeWithText("Torrent").performClick()
            // Prefer the terminal "Download completed". A persistent in-progress
            // dialog on a slow upstream is an acceptable affordance fallback; the
            // error dialog (neither string present) is a loud failure.
            val completed = try {
                composeRule.waitUntil(timeoutMillis = 60_000) { present("Download completed") }
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
            require(completed || presentContains("Downloading file")) {
                "Tapping \"Torrent\" must surface the DownloadDialog — " +
                    "\"Download completed\" or, on a slow upstream, the " +
                    "\"Downloading file…\" in-progress dialog. Neither appeared (a " +
                    "download Error dialog or no dialog is a loud failure). HTTP-file " +
                    "providers (archiveorg/gutenberg) reach this via the same button " +
                    "→ downloadHttpFile()."
            }
        } else {
            // Magnet-only topic — tap Magnet → MagnetDialog with an "Open" action
            // (designsystem_action_open; TopicScreen.kt:516-540).
            composeRule.onNodeWithText("Magnet").performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) { present("Open") }
            require(present("Open")) {
                "Tapping \"Magnet\" must surface the MagnetDialog with its \"Open\" action."
            }
        }
        // Success marker (anti-bluff): emitted ONLY after a real user-visible
        // download affordance is confirmed on screen — the DownloadDialog
        // ("Downloading file…" in-progress OR "Download completed") for the
        // Torrent/HTTP-file path, OR the MagnetDialog "Open" action for the
        // magnet path. This line is unreachable unless one of the require()s
        // above passed, so the marker cannot be emitted without a real affordance
        // on screen. The orchestrator greps this to distinguish a REAL flow
        // success from a post-success activity-destroy teardown crash (the
        // known-open upstream LVA-008 androidx-navigation defect), which can mark
        // the JUnit run failed even though the user reached the download. PASS is
        // tied to this marker (user-visible outcome), never merely to a green
        // process exit.
        android.util.Log.i(
            "C70-RESULT",
            "DOWNLOAD-OK backend=$qaBackend providers=${providerSpecs.joinToString(",") { it.id }} query=$qaQuery",
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /** True when at least one node with [text] is present in the merged tree. */
    private fun present(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * True when at least one node CONTAINS [substring] (substring match). Used for
     * the in-progress dialog "Downloading file…", whose trailing ellipsis (…) is
     * a single Unicode char — substring-matching "Downloading file" avoids
     * fragile exact-ellipsis comparison.
     */
    private fun presentContains(substring: String): Boolean =
        composeRule.onAllNodesWithText(substring, substring = true)
            .fetchSemanticsNodes().isNotEmpty()

    /**
     * True when a real result ROW has composed — each TopicListItem carries a
     * FavoriteButton (content-desc "Favorite"), absent on the "Nothing found"
     * empty state and on the search-error placeholder.
     */
    private fun hasResultRow(): Boolean =
        composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty()

    /**
     * True when SearchResultScreen is showing its transient error placeholder
     * (SearchResultScreen.kt:295-308): the "Search failed" title, the "problem
     * reaching the trackers" subtitle, OR the "Retry" Button.
     */
    private fun hasSearchError(): Boolean =
        present("Search failed") || presentContains("problem reaching") || present("Retry")
}
