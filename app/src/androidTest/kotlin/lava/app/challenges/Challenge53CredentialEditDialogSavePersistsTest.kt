/*
 * Challenge Test C53 — Credentials edit dialog: type username + password,
 * tap Save, the credential PERSISTS and the screen re-renders the provider
 * as authenticated with the saved username.
 *
 * GAP CLOSED (UI coverage audit `docs/qa/2026-06-25-ui-coverage-audit.md`, R5 /
 * W5): `feature/credentials`'s `CredentialEditDialog` (the bottom-sheet that
 * enters username/password/api-key for a provider, Save → persist) had NO
 * end-to-end rendered-UI Challenge. The ViewModel test
 * (`CredentialsViewModelTest`) drives the persist path at the VM layer; this
 * Challenge drives the SAME persist path through the REAL rendered screen — the
 * user taps a TextField, types, taps the rendered "Save" button, and the
 * provider card re-renders as authenticated. That is the surface a real user
 * touches; the VM test alone cannot prove the screen reacts.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   Every layer below the test is REAL production code, identical to what runs
 *   on a user's device:
 *     CredentialsScreen (real Composable)
 *       → CredentialEditDialog (real bottom-sheet, real TextFields, real Save)
 *       → CredentialsViewModel (real Orbit VM)
 *       → ProviderCredentialManager (real)
 *       → CredentialsRepository (real)
 *       → Room providerCredentialsDao (real, in-memory)
 *       → CredentialEncryptor (real AES encryption).
 *   The ONLY faked boundary is the outermost SDK tracker source
 *   (`FakeTrackerClient` supplies the descriptor list `listAvailableTrackers()`
 *   reads), exactly as the existing `CredentialsViewModelTest` does and exactly
 *   as the Anti-Bluff Pact permits (outermost boundary only). The persist path —
 *   the thing this test claims to verify — is 100% real: a real AES-encrypted
 *   row is written to a real Room DB and read back through the real observe()
 *   Flow that drives the rendered card.
 *
 *   PRIMARY assertion on user-visible state (§6.AB.1 rendering-correctness +
 *   Sixth Law clause 3): after Save, the rendered provider card shows the
 *   "Authenticated" badge AND the saved username ("User: vasya"). Before Save the
 *   card shows "Anonymous". The chief failure signal is rendered UI text, not a
 *   mock-call count.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing defect → assertion fails):
 *
 *   MUTATION — make the dialog Save a persist no-op (the screen still renders,
 *   the dialog still dismisses, nothing crashes):
 *     1. In `CredentialsViewModel.onAction`, the `SubmitDialog` PASSWORD branch,
 *        delete the `credentialManager.setPassword(dialog.providerId,
 *        dialog.username, dialog.password)` call (leave the toast + dialog
 *        dismiss). This is the §6.AB non-crashing class: Save "works" visually
 *        (sheet closes) but persists nothing.
 *     2. Re-run on the gating emulator/device:
 *          ./gradlew :app:connectedDebugAndroidTest \
 *            --tests "lava.app.challenges.Challenge53CredentialEditDialogSavePersistsTest"
 *     3. Expected failure: the provider card never flips to "Authenticated" —
 *        `waitUntil` for the "User: vasya" / "Authenticated" node times out and
 *        the assertion fails with
 *          "after tapping Save the provider MUST render as Authenticated with the
 *           saved username — the credential was not persisted (the
 *           SubmitDialog→setPassword path is broken)".
 *     4. Revert the mutation; re-run; the card re-renders authenticated and the
 *        assertion passes.
 *
 * Operator command (device run via the §6.AE Containers matrix, §6.AH container/VM):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge53CredentialEditDialogSavePersistsTest"
 *
 * Honest scope statement (per §6.J / §6.X-debt / §6.AH): this Challenge is
 * SOURCE-WRITTEN here and COMPILE + DEVICE-EXEC are PENDING — the coordinated
 * 1076 gate compiles `:app:assembleDebugAndroidTest`, and actual emulator
 * execution is deferred to the §6.AE Containers/VM gate-host per §6.AH (no
 * host-direct fallback on this darwin/arm64 host). The §6.AE.5 attestation row
 * is produced when the operator runs the matrix.
 *
 * // covers-feature: credentials
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderCredentialManager
import lava.database.AppDatabase
import lava.designsystem.theme.LavaTheme
import lava.feature.credentials.CredentialsScreen
import lava.feature.credentials.CredentialsViewModel
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
class Challenge53CredentialEditDialogSavePersistsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: CredentialsViewModel

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // REAL Room DB + REAL encryptor + REAL repository + REAL manager — the
        // exact production persist stack. In-memory so the test is hermetic.
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = CredentialsRepository(
            dao = db.providerCredentialsDao(),
            encryptor = CredentialEncryptor(),
        )
        val manager = ProviderCredentialManager(repository)

        // The ONLY faked boundary: the SDK tracker source. FakeTrackerClient
        // supplies the verified descriptor `listAvailableTrackers()` reads.
        val registry = DefaultTrackerRegistry()
        val rutrackerDesc = descriptor(
            "rutracker",
            "RuTracker",
            setOf(TrackerCapability.SEARCH, TrackerCapability.AUTH_REQUIRED),
        )
        registry.register(object : TrackerClientFactory {
            override val descriptor = rutrackerDesc
            override fun create(config: PluginConfig) = FakeTrackerClient(rutrackerDesc)
        })

        viewModel = CredentialsViewModel(manager, LavaTrackerSdk(registry))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // CHALLENGE — open the edit dialog, type a username + password into the REAL
    // dialog TextFields, tap the REAL "Save" button, and assert the provider card
    // re-renders as Authenticated with the saved username (a real persisted +
    // re-observed Room row). Primary assertion on rendered UI text.
    @Test
    fun typeUsernamePassword_tapSave_providerRendersAuthenticatedWithUsername() {
        composeRule.setContent {
            LavaTheme {
                // Real production screen + the real VM we constructed above.
                CredentialsScreen(onBack = {}, viewModel = viewModel)
            }
        }

        // Wait for the provider card to render (load() completes).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("RuTracker").fetchSemanticsNodes().isNotEmpty()
        }

        // BEFORE: the provider is Anonymous (no stored credential yet).
        composeRule.onNodeWithText("Anonymous").assertIsDisplayed()

        // Open the edit dialog via the row's Edit icon (content description
        // "Edit Credential" — see R.string.credentials_edit). onFirst() guards
        // against the FAB also carrying an add-style description.
        composeRule.onAllNodesWithContentDescription("Edit Credential")
            .onFirst()
            .performClick()

        // The dialog renders its Username + Password TextFields (PASSWORD type is
        // the default). Type into them by label.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty()
        }
        // Address the FIELDS (set-text action) specifically — the "Password"
        // type-selector button also carries the text "Password".
        composeRule.onNode(hasSetTextAction() and hasText("Username")).performTextInput("vasya")
        composeRule.onNode(hasSetTextAction() and hasText("Password")).performTextInput("hunter2")

        // Tap the REAL "Save" button — drives SubmitDialog → setPassword → Room.
        composeRule.onNode(hasText("Save")).performClick()

        // PRIMARY ASSERTION on user-visible state: the card re-renders as
        // Authenticated with the saved username. Without a real persist this
        // never appears.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Authenticated").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Authenticated").assertIsDisplayed()
        composeRule.onNodeWithText("User: vasya").assertIsDisplayed()
    }

    private fun descriptor(
        id: String,
        name: String,
        caps: Set<TrackerCapability>,
    ) = object : TrackerDescriptor {
        override val trackerId = id
        override val displayName = name
        override val baseUrls =
            listOf(MirrorUrl("https://$id.example", isPrimary = true, protocol = Protocol.HTTPS))
        override val capabilities = caps
        override val authType =
            if (TrackerCapability.AUTH_REQUIRED in caps) AuthType.FORM_LOGIN else AuthType.NONE
        override val encoding = "UTF-8"
        override val expectedHealthMarker = id

        // Verified-by-construction so the §6.G clause-4 UI filter does not hide it.
        override val verified = true
    }
}
