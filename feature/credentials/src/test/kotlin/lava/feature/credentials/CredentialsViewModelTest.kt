package lava.feature.credentials

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderCredentialManager
import lava.database.AppDatabase
import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.testing.rule.MainDispatcherRule
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.orbitmvi.orbit.test.test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Anti-bluff test for [CredentialsViewModel].
 *
 * Uses real ProviderCredentialManager + real CredentialsRepository + real Room DAO
 * + real CredentialEncryptor. LavaTrackerSdk is wired with FakeTrackerClients.
 * No mocks of internal business logic (Second Law compliance).
 *
 * Constitutional compliance:
 * - Sixth Law: assertions on user-visible state (loading, credentials list, toasts)
 * - Bluff-Audit rehearsal: mutate clear() in Manager to no-op → test expecting
 *   cleared state fails. Reverted.
 *
 * Bluff-Audit: CredentialsViewModelTest
 *   Deliberate break: commented out `credentialManager.clear()` in ViewModel
 *   Failure: `assertEquals(false, item.isAuthenticated)` after ClearCredentials → expected false but was true
 *   Reverted: yes
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CredentialsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: CredentialsViewModel
    private lateinit var manager: ProviderCredentialManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val encryptor = CredentialEncryptor()
        val repository = CredentialsRepository(
            dao = db.providerCredentialsDao(),
            encryptor = encryptor,
        )
        manager = ProviderCredentialManager(repository)

        val registry = DefaultTrackerRegistry()
        val rutrackerDesc = descriptor("rutracker", "RuTracker", setOf(TrackerCapability.SEARCH, TrackerCapability.AUTH_REQUIRED))
        val rutorDesc = descriptor("rutor", "RuTor", setOf(TrackerCapability.SEARCH))
        val rutracker = FakeTrackerClient(rutrackerDesc)
        val rutor = FakeTrackerClient(rutorDesc)
        registry.register(object : TrackerClientFactory {
            override val descriptor = rutrackerDesc
            override fun create(config: lava.sdk.api.PluginConfig) = rutracker
        })
        registry.register(object : TrackerClientFactory {
            override val descriptor = rutorDesc
            override fun create(config: lava.sdk.api.PluginConfig) = rutor
        })

        val sdk = LavaTrackerSdk(registry)
        viewModel = CredentialsViewModel(manager, sdk)
    }

    @Test
    fun `initial state shows loading then lists all providers`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            var state = awaitState()
            assertTrue(state.loading)
            state = awaitState()
            assertFalse(state.loading)
            assertEquals(2, state.credentials.size)
            assertTrue(state.credentials.any { it.providerId == "rutracker" })
            assertTrue(state.credentials.any { it.providerId == "rutor" })
        }
    }

    @Test
    fun `save password updates credential state`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(
                CredentialsAction.SavePassword("rutracker", "vasya", "secret"),
            )

            val toast = awaitSideEffect()
            assertTrue(toast is CredentialsSideEffect.ShowToast)
            awaitState() // loading=true
            val state = awaitState() // loaded with updated credentials
            val rutrackerItem = state.credentials.first { it.providerId == "rutracker" }
            assertTrue(rutrackerItem.isAuthenticated)
            assertEquals("vasya", rutrackerItem.username)
        }
    }

    @Test
    fun `clear credentials removes authentication`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(
                CredentialsAction.SavePassword("rutracker", "u", "p"),
            )
            awaitSideEffect()
            awaitState() // loading after save
            awaitState() // loaded after save

            viewModel.onAction(CredentialsAction.ClearCredentials("rutracker"))
            awaitSideEffect()
            awaitState() // loading after clear
            val state = awaitState() // loaded after clear
            val rutrackerItem = state.credentials.first { it.providerId == "rutracker" }
            assertFalse(rutrackerItem.isAuthenticated)
            assertNull(rutrackerItem.username)
        }
    }

    @Test
    fun `select provider updates selectedProvider`() = runTest(mainDispatcherRule.testDispatcher) {
        // Root cause of the prior flake (a real DISPATCHER LEAK at the Room
        // boundary, NOT virtual-time nondeterminism): CredentialsViewModel.load()
        // collects credentialManager.observeAll() — a Room Flow whose emissions
        // are delivered on Room's InvalidationTracker executor (a real background
        // thread), NOT this test's UnconfinedTestDispatcher. That executor is
        // below the ViewModel and outside our injection control. The onCreate ->
        // load() collection it starts therefore lingers as a live coroutine that
        // settles on WALL-CLOCK time, and orbit-test's default end-of-block
        // "wait for remaining intents to complete" wall-clock timeout (~ a few
        // seconds) trips under the loaded full-suite multi-module run when that
        // executor thread is CPU-starved — producing the
        // TurbineTimeoutCancellationException / TurbineAssertionError the test was
        // failing with (passed isolated, failed under load).
        //
        // Robust deterministic fix, eliminating wall-clock dependence entirely:
        //   1. await-until the user-visible outcome (selectedProvider ==
        //      "rutracker") rather than a fixed emission count, so emission
        //      ordering between load()'s reduce and the SelectProvider reduce is
        //      irrelevant (the bounded guard is NOT wall-clock — it cannot flake);
        //   2. cancelAndIgnoreRemainingItems() AFTER the assertion, so orbit-test
        //      does NOT wall-clock-wait for the lingering Room-executor-backed
        //      load() collection to drain at teardown. The generous 10s .test
        //      timeout is a belt-and-braces upper bound that the cancel makes
        //      unreachable under normal load.
        // selectedProvider null -> "rutracker" is a real StateFlow change
        // guaranteed to be emitted; a genuine SelectProvider regression never
        // reaches the outcome and fails the assertion below with a clear message
        // (proven by the §6.J rehearsal in the commit body). Flake recorded at
        // .lava-ci-evidence/sixth-law-incidents/2026-05-20-flaky-credentialsviewmodeltest.json.
        viewModel.test(this, timeout = 10.seconds) {
            runOnCreate()

            viewModel.onAction(CredentialsAction.SelectProvider("rutracker"))

            // Bounded await-until-condition. The bound (12) is generous relative
            // to the at-most-3 states this flow can emit (initial loading, loaded,
            // selected) plus any interleaving the Room executor introduces; it
            // only prevents an unbounded await on a genuine regression — it is NOT
            // a wall-clock bound, so it cannot flake under host load.
            //
            // A genuine SelectProvider regression (the reduce never writes
            // action.providerId) never reaches the outcome, so awaitState() would
            // eventually drain the channel and surface a Turbine timeout. We catch
            // that here and re-raise as a PRIMARY assertEquals on the user-visible
            // selectedProvider so the §6.J failure signal is a clear assertion on
            // user-visible state, not an opaque timeout.
            var state = awaitState()
            var guard = 0
            try {
                while (state.selectedProvider != "rutracker" && guard < 12) {
                    state = awaitState()
                    guard++
                }
            } catch (awaitFailure: Throwable) {
                // awaitState() drained / wall-clock-timed-out before the outcome
                // appeared. Both Turbine's TurbineAssertionError ("No value
                // produced") and orbit's internal timeout are non-public types, so
                // we catch broadly and re-raise as a clear assertion on the
                // user-visible state — the §6.J failure signal is then an explicit
                // "expected rutracker" message, never an opaque timeout stack.
                throw AssertionError(
                    "SelectProvider did not update selectedProvider: " +
                        "expected \"rutracker\" but the screen state never " +
                        "carried it (last observed=${state.selectedProvider}). " +
                        "Underlying await failed: ${awaitFailure.message}",
                )
            }
            // PRIMARY assertion on user-visible state (§6.J): the selected
            // provider the screen renders the active-highlight for.
            assertEquals("rutracker", state.selectedProvider)

            // Stop the wall-clock teardown wait on the lingering Room-Flow
            // collection (see root-cause note above).
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `show and dismiss add dialog`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(CredentialsAction.ShowEditDialog("rutracker", "RuTracker"))
            val stateWithDialog = awaitState()
            assertNotNull(stateWithDialog.dialogState)
            assertEquals("rutracker", stateWithDialog.dialogState?.providerId)

            viewModel.onAction(CredentialsAction.DismissDialog)
            assertNull(awaitState().dialogState)
        }
    }

    @Test
    fun `save api key updates credential state`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(
                CredentialsAction.SaveApiKey("rutracker", "api-key-123"),
            )

            awaitSideEffect()
            awaitState() // loading after save
            val state = awaitState() // loaded after save
            val item = state.credentials.first { it.providerId == "rutracker" }
            assertTrue(item.isAuthenticated)
        }
    }

    // CHALLENGE — the dialog SubmitDialog PASSWORD path persists username+password
    // so the screen renders the provider as authenticated with the typed username.
    // This is the real "Add credentials" dialog flow (ShowEditDialog -> type fields
    // -> SubmitDialog), distinct from the direct SavePassword action.
    //
    // Falsifiability rehearsal (PERFORMED 2026-06-09):
    //   Mutation: in CredentialsViewModel SubmitDialog PASSWORD branch, drop the
    //             `credentialManager.setPassword(...)` call.
    //   Observed: this test FAILS at `assertTrue(item.isAuthenticated)` — the
    //             provider stays unauthenticated because nothing was persisted.
    //   Reverted: yes.
    @Test
    fun `submit dialog with password persists credentials and authenticates`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(CredentialsAction.ShowEditDialog("rutracker", "RuTracker"))
            viewModel.onAction(CredentialsAction.SetUsername("vasya"))
            viewModel.onAction(CredentialsAction.SetPassword("secret"))
            viewModel.onAction(CredentialsAction.SubmitDialog("rutracker"))

            // Drain interleaved state/side-effect items until the user-visible
            // outcome (rutracker authenticated, no dialog) appears. Robust against
            // StateFlow distinct-until-changed conflation of the load() loading flip.
            val loaded = awaitLoadedStateMatching {
                val item = it.credentials.firstOrNull { c -> c.providerId == "rutracker" }
                it.dialogState == null && item?.isAuthenticated == true
            }
            val item = loaded.credentials.first { it.providerId == "rutracker" }
            assertTrue(
                "SubmitDialog with a username+password MUST authenticate the provider",
                item.isAuthenticated,
            )
            assertEquals("vasya", item.username)
            cancelAndIgnoreRemainingItems()
        }
    }

    // CHALLENGE — the dialog SubmitDialog TOKEN path persists a bearer token via
    // credentialManager.setToken (authType="token"), which the load() mapper reads
    // as authenticated (authType != "none"). The direct actions never exercise the
    // TOKEN branch, so this is the only coverage of setToken from the dialog.
    //
    // Falsifiability rehearsal (PERFORMED 2026-06-09):
    //   Mutation: in CredentialsViewModel SubmitDialog TOKEN branch, change
    //             `credentialManager.setToken(...)` to a no-op.
    //   Observed: this test FAILS at `assertTrue(item.isAuthenticated)` — no token
    //             row is written so the provider remains unauthenticated.
    //   Reverted: yes.
    @Test
    fun `submit dialog with token persists token credential and authenticates`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(CredentialsAction.ShowEditDialog("rutor", "RuTor"))
            viewModel.onAction(CredentialsAction.SetCredentialType(CredentialType.TOKEN))
            viewModel.onAction(CredentialsAction.SetToken("tok-abc"))
            viewModel.onAction(CredentialsAction.SubmitDialog("rutor"))

            val loaded = awaitLoadedStateMatching {
                val item = it.credentials.firstOrNull { c -> c.providerId == "rutor" }
                it.dialogState == null && item?.isAuthenticated == true
            }
            val item = loaded.credentials.first { it.providerId == "rutor" }
            assertTrue(
                "SubmitDialog with a TOKEN MUST authenticate the provider",
                item.isAuthenticated,
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    // CHALLENGE — SubmitDialog with blank required fields MUST NOT persist anything:
    // the provider stays unauthenticated. Guards the `isNotBlank()` gate that
    // protects the user from saving an empty (broken) credential.
    //
    // Falsifiability rehearsal (PERFORMED 2026-06-09):
    //   Mutation: in CredentialsViewModel SubmitDialog PASSWORD branch, remove the
    //             `if (dialog.username.isNotBlank() && dialog.password.isNotBlank())`
    //             guard so setPassword runs with blank values.
    //   Observed: this test FAILS at `assertFalse(item.isAuthenticated)` — a blank
    //             credential is persisted and the provider becomes authenticated.
    //   Reverted: yes.
    @Test
    fun `submit dialog with blank password does not persist`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(CredentialsAction.ShowEditDialog("rutracker", "RuTracker"))
            // Leave username/password blank.
            viewModel.onAction(CredentialsAction.SubmitDialog("rutracker"))

            // The blank submit posts a toast but persists nothing, so the
            // settled (dialog dismissed, load complete) state still shows the
            // provider as unauthenticated.
            val settled = awaitLoadedStateMatching { it.dialogState == null && !it.loading }
            val item = settled.credentials.first { it.providerId == "rutracker" }
            assertFalse(
                "a blank-field SubmitDialog MUST NOT authenticate the provider",
                item.isAuthenticated,
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    // CHALLENGE — ShowEditDialog on a provider that ALREADY has a stored password
    // pre-fills the dialog with the saved username and marks it as editing, so the
    // user edits rather than re-enters. Asserts on the rendered dialog state fields.
    //
    // Falsifiability rehearsal (PERFORMED 2026-06-09):
    //   Mutation: in CredentialsViewModel ShowEditDialog, hardcode
    //             `username = ""` in the CredentialDialogState.
    //   Observed: this test FAILS at the username assertion — the dialog opens
    //             blank instead of pre-filled with the saved "stored-user".
    //   Reverted: yes.
    @Test
    fun `show edit dialog prefills existing username and marks editing`() = runTest(mainDispatcherRule.testDispatcher) {
        // Persist a credential first so the load() mapper marks it isAuthenticated
        // with a username, which ShowEditDialog then reads for prefill.
        manager.setPassword("rutracker", "stored-user", "stored-pass")

        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded (rutracker has stored-user)

            viewModel.onAction(CredentialsAction.ShowEditDialog("rutracker", "RuTracker"))
            val dialog = awaitState().dialogState
            assertNotNull(dialog)
            assertEquals(
                "the edit dialog MUST pre-fill the saved username",
                "stored-user",
                dialog?.username,
            )
            assertTrue(
                "an existing authenticated credential MUST open the dialog in editing mode",
                dialog?.isEditing == true,
            )
        }
    }

    // CHALLENGE — the load() mapper applies the §6.G clause-4 verified filter:
    // an UNVERIFIED descriptor MUST NOT appear in the credentials list the user
    // sees. Guards against showing a non-shippable provider.
    //
    // Falsifiability rehearsal (PERFORMED 2026-06-09):
    //   Mutation: in CredentialsViewModel.load(), drop the `.filter { it.verified }`.
    //   Observed: this test FAILS — the unverified "hidden" provider appears in
    //             state.credentials, so the size/absence assertions fail.
    //   Reverted: yes.
    @Test
    fun `load hides unverified providers from the credentials list`() = runTest(mainDispatcherRule.testDispatcher) {
        // Build a fresh SDK whose registry includes an UNVERIFIED descriptor.
        val registry = DefaultTrackerRegistry()
        val verifiedDesc = descriptor("rutracker", "RuTracker", setOf(TrackerCapability.SEARCH))
        val hiddenDesc = unverifiedDescriptor("hidden", "Hidden Provider")
        registry.register(object : TrackerClientFactory {
            override val descriptor = verifiedDesc
            override fun create(config: lava.sdk.api.PluginConfig) = FakeTrackerClient(verifiedDesc)
        })
        registry.register(object : TrackerClientFactory {
            override val descriptor = hiddenDesc
            override fun create(config: lava.sdk.api.PluginConfig) = FakeTrackerClient(hiddenDesc)
        })
        val filteringViewModel = CredentialsViewModel(manager, LavaTrackerSdk(registry))

        filteringViewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            val loaded = awaitState() // loaded
            assertTrue(
                "the verified provider MUST be listed",
                loaded.credentials.any { it.providerId == "rutracker" },
            )
            assertFalse(
                "an unverified provider MUST NOT be shown to the user (§6.G clause 4)",
                loaded.credentials.any { it.providerId == "hidden" },
            )
        }
    }

    /**
     * Drains interleaved state/side-effect items until a [CredentialsState]
     * matches [predicate], returning it. Robust against StateFlow
     * distinct-until-changed conflation of the transient load() loading flip
     * and against side-effect interleaving — the assertion is on the
     * user-visible settled state, not on a fixed emission count.
     */
    private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<
        CredentialsState,
        CredentialsSideEffect,
        CredentialsViewModel,
        >.awaitLoadedStateMatching(
        predicate: (CredentialsState) -> Boolean,
    ): CredentialsState {
        while (true) {
            when (val item = awaitItem()) {
                is org.orbitmvi.orbit.test.Item.StateItem ->
                    if (predicate(item.value)) return item.value
                else -> Unit
            }
        }
    }

    private fun unverifiedDescriptor(
        id: String,
        name: String,
    ) = object : TrackerDescriptor {
        override val trackerId = id
        override val displayName = name
        override val baseUrls = listOf(MirrorUrl("https://$id.example", isPrimary = true, protocol = Protocol.HTTPS))
        override val capabilities = setOf(TrackerCapability.SEARCH)
        override val authType = AuthType.NONE
        override val encoding = "UTF-8"
        override val expectedHealthMarker = id
        override val verified = false
    }

    private fun descriptor(
        id: String,
        name: String,
        caps: Set<TrackerCapability>,
    ) = object : TrackerDescriptor {
        override val trackerId = id
        override val displayName = name
        override val baseUrls = listOf(MirrorUrl("https://$id.example", isPrimary = true, protocol = Protocol.HTTPS))
        override val capabilities = caps
        override val authType = if (TrackerCapability.AUTH_REQUIRED in caps) AuthType.FORM_LOGIN else AuthType.NONE
        override val encoding = "UTF-8"
        override val expectedHealthMarker = id

        // Test descriptors are verified-by-construction so the UI filter (clause 6.G)
        // does not hide them from the assertion.
        override val verified = true
    }
}
