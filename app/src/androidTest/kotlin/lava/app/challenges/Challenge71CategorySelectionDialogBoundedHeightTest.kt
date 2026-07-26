/*
 * Challenge Test C71 — Category-selection dialog renders BOUNDED (no
 * nested-scroll FATAL) when opened from the real search-result filter bar.
 *
 * Crashlytics issue c7c8cccad09f72bd7bb95455226109b8 (FATAL,
 * `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints`, firstSeen 1.2.3 → lastSeen 1.3.12,
 * fixed 2026-07-04, fix commit f1a2c362). Root cause:
 * `CategorySelectionDialog.kt` wrapped `PagesScreen` in a plain
 * `Column(modifier = Modifier.clip(...))` with NO height bound and no weight
 * on the PagesScreen child; the Column measured the pager with
 * maxHeight = Infinity, which the CategorySelectionList/CategorySelectionScreen
 * LazyLists (`fillMaxHeight()`/`fillMaxSize()`) rejected at measure time. Fix:
 * `Modifier.fillMaxHeight(0.9f)` on the Column + `Modifier.weight(1f)` on
 * PagesScreen. Structural guard:
 * `CategorySelectionDialogBoundedHeightRegressionTest` (JVM) + §6.Q scanner
 * CHECK 3 (`tests/compose-layout/test_no_nested_scroll_antipattern.sh`).
 *
 * WHAT THE USER DOES (the surface this Challenge traverses verbatim):
 *   MainActivity → bottom-tab nav → Search action → SearchInputScreen → type a
 *   query → IME submit → SearchResultScreen → tap the "Expand filters" AppBar
 *   action → FilterBar expands → tap the Categories row ("Any") →
 *   CategorySelectionDialog OPENS AND RENDERS: page tabs ("Current" / "All")
 *   plus the bottom-bar action row (Cancel / Apply). On the pre-fix code this
 *   exact tap path is what crashed the app.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → Nav graph →
 *     SearchInputScreen → SearchInputViewModel → SearchResultScreen →
 *     SearchAppBar (ExpandableAppBar) → FilterBar → FilterCategoryItem →
 *     CategorySelectionDialog → PagesScreen → CategorySelectionList LazyList.
 *     The ONLY faked boundary is the network socket (MockWebServer), which
 *     sits BELOW the production SDK — identical to C58's seam.
 *   - PRIMARY assertion is on USER-VISIBLE rendered state: the dialog's
 *     bottom-bar "Apply" button and the "Current" page tab compose. On the
 *     pre-fix code the instrumentation process DIES with the production
 *     IllegalStateException the moment the dialog is tapped open — the test
 *     cannot pass while the defect exists.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL:
 *
 *   MUTATION A — CRASH class (the actual production defect):
 *     1. In `feature/search_result/.../categories/CategorySelectionDialog.kt`,
 *        revert the content Column to `Column(modifier = Modifier.clip(AppTheme.shapes.large))`
 *        and delete the `modifier = Modifier.weight(1f),` line from the
 *        PagesScreen call (the exact pre-fix shape).
 *     2. Re-run on the gating emulator/device:
 *          ./gradlew :app:connectedDebugAndroidTest \
 *            --tests "lava.app.challenges.Challenge71CategorySelectionDialogBoundedHeightTest"
 *     3. Expected failure: after the "Any" tap, the instrumentation process
 *        crashes with `IllegalStateException: Vertically scrollable component
 *        was measured with an infinity maximum height constraints` — the test
 *        reports FAILED (process death), never a false pass.
 *     4. Restore the fix; re-run; the dialog renders and assertions pass.
 *
 *   MUTATION B — NON-CRASHING class (§6.AB.3 discrimination proof for the
 *   primary assertion):
 *     1. In the same file, delete the `bottomBar = { ... }` slot from the
 *        PagesScreen call (the dialog still renders its tabs; nothing crashes).
 *     2. Re-run.
 *     3. Expected failure: `onNodeWithText("Apply").assertIsDisplayed()` fails
 *        with "Node not found" — the dialog MUST render its bottom-bar Apply
 *        action (the mutation removed the user-visible control the test
 *        asserts on).
 *     4. Restore; re-run; passes.
 *
 * Operator command (device run via the §6.AE Containers matrix, §6.AH container/VM):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge71CategorySelectionDialogBoundedHeightTest"
 *
 * Honest scope statement (per §6.J / §6.X-debt / §6.AH): this Challenge is
 * SOURCE-WRITTEN here and DEVICE-EXEC is PENDING — the §6.AE Containers/VM
 * gate-host executes it per §6.AH (no live-device fallback per §6.AG). The
 * reproduce-first RED→GREEN for this defect class WAS executed against the
 * two structural gates (JVM regression test + §6.Q scanner CHECK 3) with the
 * production fix temporarily reverted; both outputs are recorded verbatim in
 * the closure log
 * `.lava-ci-evidence/crashlytics-resolved/2026-07-26-nested-scroll-category-selection-dialog.md`.
 *
 * // covers-feature: search_result
 * // covers-changelog: Crashlytics c7c8ccc — nested-scroll FATAL in the categories filter dialog
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import digital.vasic.lava.client.MainActivity
import kotlinx.coroutines.runBlocking
import lava.app.LenientTeardownRule
import lava.app.OnboardingBypassRule
import lava.credentials.ProviderConfigRepository
import lava.tracker.client.ApiBaseUrlHolder
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge71CategorySelectionDialogBoundedHeightTest {

    // ── Rules (identical harness to C58 — same user path up to the rendered
    // search-result screen) ──────────────────────────────────────────────────
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    // LVA-008: swallow the activity-destroy nav-teardown ISE (not a user-path
    // crash) so the dialog assertions decide pass/fail. Outer of composeRule.
    @get:Rule(order = 2)
    val lenientTeardown = LenientTeardownRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Fields ───────────────────────────────────────────────────────────────
    private lateinit var server: MockWebServer

    // Non-real API key (§6.R — never a production credential; MockWebServer
    // ignores auth headers entirely).
    private val TEST_AUTH_KEY = "C71-TEST-KEY"

    private val SEEDED_PROVIDER = "rutracker"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderSeedEntryPoint {
        fun providerConfigRepository(): ProviderConfigRepository
    }

    private fun providerRepo(): ProviderConfigRepository {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(app, ProviderSeedEntryPoint::class.java)
            .providerConfigRepository()
    }

    @Before
    fun setUp() {
        hiltRule.inject()

        // Seed one onboarded, search-enabled provider so the search submit
        // resolves a NON-EMPTY providerIds set and reaches SearchResultScreen.
        runBlocking { providerRepo().ensureDefault(SEEDED_PROVIDER) }

        // One result row for every request (search; the forum/categories call
        // simply fails to parse against this shape → empty category list, which
        // the dialog still renders).
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(NON_EMPTY_SEARCH_RESULT_JSON)
        }
        server.start()
        ApiBaseUrlHolder.set(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            key = TEST_AUTH_KEY,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiBaseUrlHolder.reset()
        runCatching { runBlocking { providerRepo().keepOnlySearchEnabled(emptySet()) } }
    }

    // CHALLENGE — drive the real user path to the search-result screen, expand
    // the filter bar, tap the Categories row, and assert the
    // CategorySelectionDialog RENDERS its tabs + bottom-bar actions (on the
    // pre-fix code this tap is the exact crash trigger: the instrumentation
    // process dies with the infinity-max-height IllegalStateException).
    @Test
    fun openCategoriesFilter_dialogRendersBoundedWithoutCrash() {
        // Bottom-tab nav is up (OnboardingBypassRule).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the AppBar Search action → SearchInputScreen.
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Type a query and submit via the IME Search action.
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // SearchResultScreen renders at least one result row (FavoriteButton
        // content-description "Favorite" — the C11/C38/C58 per-row signal).
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Expand the filter bar via the AppBar filter action
        // (R.string.search_screen_content_description_filter = "Expand filters").
        composeRule.onNode(
            hasContentDescription("Expand filters") and hasClickAction(),
        ).performClick()

        // The FilterBar renders its Categories row
        // (R.string.search_screen_filter_category_label = "Categories").
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Categories").fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the Categories row's value surface — it shows "Any"
        // (R.string.search_screen_filter_any) when no category is selected.
        // THE CRASH TRIGGER on the pre-fix code: this opens
        // CategorySelectionDialog, whose unbounded Column fed infinite height
        // into the nested LazyLists.
        composeRule.onNode(hasText("Any") and hasClickAction()).performClick()

        // PRIMARY ASSERTION on user-visible state: the dialog rendered — its
        // "Current" page tab and its bottom-bar Apply/Cancel actions compose.
        // On the pre-fix code the process is already dead before any of these
        // nodes exist.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Apply").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Apply").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Current").assertIsDisplayed()
    }

    companion object {
        // Minimal valid lava-api-go SearchResult JSON with ONE result item —
        // same non-empty shape as C58.
        private val NON_EMPTY_SEARCH_RESULT_JSON =
            """{"provider":"rutracker","page":0,"totalPages":1,""" +
                """"results":[{"id":"1","title":"Ubuntu 24.04 LTS Desktop",""" +
                """"sizeBytes":1,"seeders":7,"leechers":0,""" +
                """"magnetLink":"magnet:?xt=urn:btih:AB",""" +
                """"downloadUrl":"https://x/1.torrent","infoHash":"AB","category":"OS"}]}"""
    }
}
