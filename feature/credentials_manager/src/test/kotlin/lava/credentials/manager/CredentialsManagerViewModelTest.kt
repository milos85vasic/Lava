package lava.credentials.manager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import lava.credentials.CredentialsEntryRepository
import lava.credentials.PassphraseManager
import lava.credentials.model.CredentialSecret
import lava.credentials.model.CredentialsEntry
import lava.credentials.session.CredentialsKeyHolder
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [CredentialsManagerViewModel] — the credential-vault
 * unlock gate (previously ZERO tests). This module guards every stored tracker
 * credential, so a regression in the unlock branch silently grants or denies
 * access to the whole vault.
 *
 * Constitution (Second Law / feature `CLAUDE.md` "ViewModel Tests"): the SUT is
 * a REAL [CredentialsManagerViewModel] wired to:
 *  - the REAL [PassphraseManager] (so the wrong-passphrase branch goes through
 *    real PBKDF2 key derivation + the real HMAC verifier check in
 *    `CredentialsCrypto`, NOT a stub that always returns true/false),
 *  - the REAL [CredentialsKeyHolder] session lock,
 *  - a behaviorally-equivalent in-memory [CredentialsEntryRepository] fake whose
 *    observe()/get()/upsert()/delete() match `CredentialsEntryRepositoryImpl`:
 *    observe() is a live Flow, upsert() replaces by id, delete() removes the row.
 * Only the outermost boundaries are faked (the SharedPreferences-backed
 * passphrase storage and the Room-backed entry DAO) — per the Anti-Bluff Pact
 * Third Law these fakes enforce the same observable contract as production.
 * The SUT is NEVER mocked.
 *
 * Primary assertions are on user-visible state: the rendered unlocked/error
 * flags the screen reads, and the persisted entry list the screen renders.
 * §6.H: synthetic passphrases only — no real credentials are logged or echoed.
 *
 * The orbit-test `viewModel.test(this) { ... }` block gives the lazily-started
 * Orbit container an active stateFlow subscriber so `intent { }` actually
 * dispatches; the final assertion reads `container.stateFlow.value` directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialsManagerViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var repo: FakeCredentialsEntryRepository
    private lateinit var storage: InMemoryPassphraseStorage
    private lateinit var keyHolder: CredentialsKeyHolder
    private lateinit var passphrase: PassphraseManager

    // §6.H: synthetic value, never a real tracker passphrase.
    private val correctPassphrase = "correct-horse-battery"

    @Before
    fun setUp() {
        repo = FakeCredentialsEntryRepository()
        storage = InMemoryPassphraseStorage()
        keyHolder = CredentialsKeyHolder()
        passphrase = PassphraseManager(storage, keyHolder)
    }

    private fun newViewModel() = CredentialsManagerViewModel(repo, passphrase, keyHolder)

    /**
     * Initialize the vault (real PBKDF2 setup) then re-lock the session so the
     * test starts from the user's "enter passphrase" screen.
     */
    private suspend fun initializeVaultThenLock() {
        passphrase.firstTimeSetup(correctPassphrase)
        keyHolder.lock()
    }

    // CHALLENGE
    /**
     * Correct passphrase → vault unlocks AND the stored entries become
     * observable on screen.
     *
     * Falsifiability rehearsal:
     *   Mutation: in CredentialsManagerViewModel.Unlock, replace
     *             `if (passphrase.unlock(action.passphrase))` with `if (false)`.
     *   Observed: this test FAILS at
     *             "correct passphrase MUST unlock the vault — was unlocked=false".
     *   Reverted: yes.
     */
    @Test
    fun `correct passphrase unlocks vault and surfaces stored entries`() =
        runTest(dispatcherRule.testDispatcher) {
            initializeVaultThenLock()
            // A credential the unlocked screen must render.
            repo.seed(entry("seeded", "GitHub", CredentialSecret.ApiKey("k-1")))

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial state (vault locked, no observe yet)

                viewModel.perform(CredentialsManagerAction.Unlock(correctPassphrase))
                awaitState() // unlocked = true
                awaitState() // entries collected from observe()

                val state = viewModel.container.stateFlow.value
                assertTrue(
                    "correct passphrase MUST unlock the vault — was unlocked=${state.unlocked}",
                    state.unlocked,
                )
                assertNull(
                    "no error expected on correct passphrase — was ${state.unlockError}",
                    state.unlockError,
                )
                assertTrue("session key holder MUST be unlocked", keyHolder.isUnlocked())
                assertEquals(
                    "the seeded credential MUST be visible on the unlocked screen",
                    listOf("GitHub"),
                    state.entries.map { it.displayName },
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE
    /**
     * WRONG passphrase → "Wrong passphrase" error, vault stays LOCKED, no crash,
     * entries are NOT exposed. This is the load-bearing security assertion.
     *
     * Falsifiability rehearsal:
     *   Mutation: in CredentialsManagerViewModel.Unlock, change the `else` branch
     *             to `reduce { state.copy(unlocked = true, unlockError = null) }`
     *             (i.e. unlock anyway on a wrong passphrase).
     *   Observed: this test FAILS at
     *             "wrong passphrase MUST NOT unlock the vault — was unlocked=true".
     *   Reverted: yes.
     */
    @Test
    fun `wrong passphrase shows error and keeps vault locked`() =
        runTest(dispatcherRule.testDispatcher) {
            initializeVaultThenLock()
            repo.seed(entry("secret", "RuTracker", CredentialSecret.UsernamePassword("u", "p")))

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial state (vault locked, no observe yet)

                // §6.H: synthetic wrong value.
                viewModel.perform(CredentialsManagerAction.Unlock("definitely-wrong"))
                awaitState() // unlockError populated

                val state = viewModel.container.stateFlow.value
                assertFalse(
                    "wrong passphrase MUST NOT unlock the vault — was unlocked=${state.unlocked}",
                    state.unlocked,
                )
                assertEquals(
                    "the user MUST see the 'Wrong passphrase' error",
                    "Wrong passphrase",
                    state.unlockError,
                )
                assertFalse(
                    "session key holder MUST stay locked on wrong passphrase",
                    keyHolder.isUnlocked(),
                )
                assertTrue(
                    "entries MUST NOT be exposed while the vault is locked — was ${state.entries}",
                    state.entries.isEmpty(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE
    /**
     * Save a new credential → it is persisted in the repository (the row the
     * screen renders) AND a "Saved" toast is emitted.
     *
     * Falsifiability rehearsal:
     *   Mutation: in CredentialsManagerViewModel.Save, delete the `repo.upsert(saved)`
     *             line.
     *   Observed: this test FAILS at
     *             "the saved credential MUST be persisted in the repository".
     *   Reverted: yes.
     */
    @Test
    fun `save new credential persists it to the repository and toasts`() =
        runTest(dispatcherRule.testDispatcher) {
            passphrase.firstTimeSetup(correctPassphrase) // leaves session unlocked

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                // Initial state already has entries=[]; onCreate's observe()
                // collects the same empty list, so StateFlow emits no distinct
                // second state here.
                awaitState() // initial state

                viewModel.perform(CredentialsManagerAction.AddNew)
                awaitState() // editing = empty draft

                viewModel.perform(
                    CredentialsManagerAction.Save(
                        displayName = "Internet Archive",
                        secret = CredentialSecret.BearerToken("token-xyz"),
                    ),
                )
                awaitState() // editing cleared
                val toast = awaitSideEffect()

                assertEquals(
                    CredentialsManagerSideEffect.ShowToast("Saved"),
                    toast,
                )
                val persisted = repo.list()
                assertEquals(
                    "the saved credential MUST be persisted in the repository",
                    listOf("Internet Archive"),
                    persisted.map { it.displayName },
                )
                assertEquals(
                    "the saved secret MUST round-trip through the repository",
                    CredentialSecret.BearerToken("token-xyz"),
                    persisted.single().secret,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE
    /**
     * Delete an existing credential → the row is removed from the repository
     * (the screen no longer renders it) AND a "Deleted" toast is emitted.
     *
     * Falsifiability rehearsal:
     *   Mutation: in CredentialsManagerViewModel.Delete, delete the
     *             `repo.delete(action.id)` line.
     *   Observed: this test FAILS at
     *             "the deleted credential MUST be removed from the repository".
     *   Reverted: yes.
     */
    @Test
    fun `delete credential removes it from the repository and toasts`() =
        runTest(dispatcherRule.testDispatcher) {
            passphrase.firstTimeSetup(correctPassphrase)
            val keep = entry("keep", "Keep Me", CredentialSecret.ApiKey("k-keep"))
            val remove = entry("remove", "Remove Me", CredentialSecret.ApiKey("k-remove"))
            repo.seed(keep)
            repo.seed(remove)

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial state
                awaitState() // onCreate observe() collected the seeded entry list

                viewModel.perform(CredentialsManagerAction.Delete("remove"))
                // Delete posts a side effect; the repository removal is the
                // user-visible outcome asserted via repo.list() below.
                val toast = awaitSideEffect()

                assertEquals(
                    CredentialsManagerSideEffect.ShowToast("Deleted"),
                    toast,
                )
                val remaining = repo.list().map { it.displayName }
                assertEquals(
                    "the deleted credential MUST be removed from the repository",
                    listOf("Keep Me"),
                    remaining,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE
    /**
     * First-time setup with a fresh vault → the passphrase is initialized (real
     * PBKDF2 + verifier write), the vault unlocks, and the entries become
     * observable. This is the very first screen a new user sees; a regression
     * here either bricks setup or unlocks without a passphrase.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in CredentialsManagerViewModel.FirstTimeSetup, drop the
     *             `passphrase.firstTimeSetup(action.passphrase)` line.
     *   Observed: this test FAILS at "first-time setup MUST initialize the vault"
     *             — passphrase.isInitialized() stays false.
     *   Reverted: yes.
     */
    @Test
    fun `first time setup initializes vault unlocks and surfaces entries`() =
        runTest(dispatcherRule.testDispatcher) {
            // Fresh vault: nothing initialized yet.
            assertFalse("precondition: vault not yet initialized", passphrase.isInitialized())
            repo.seed(entry("e1", "Internet Archive", CredentialSecret.BearerToken("t-1")))

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial (needsFirstTimeSetup = true, locked)

                viewModel.perform(CredentialsManagerAction.FirstTimeSetup(correctPassphrase))
                awaitState() // unlocked = true, needsFirstTimeSetup = false
                awaitState() // entries collected

                val state = viewModel.container.stateFlow.value
                assertTrue(
                    "first-time setup MUST initialize the vault",
                    passphrase.isInitialized(),
                )
                assertTrue(
                    "first-time setup MUST unlock the vault — was unlocked=${state.unlocked}",
                    state.unlocked,
                )
                assertFalse(
                    "first-time setup MUST clear the needsFirstTimeSetup flag",
                    state.needsFirstTimeSetup,
                )
                assertEquals(
                    "the seeded credential MUST be visible after setup",
                    listOf("Internet Archive"),
                    state.entries.map { it.displayName },
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE
    /**
     * AddNew → the editing draft opens with a blank credential, so the screen
     * shows the empty "new credential" form rather than null (no form).
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in CredentialsManagerViewModel.AddNew, replace
     *             `reduce { state.copy(editing = empty()) }` with
     *             `reduce { state.copy(editing = null) }`.
     *   Observed: this test FAILS at "AddNew MUST open a blank editing draft"
     *             — editing stays null so the form never appears.
     *   Reverted: yes.
     */
    @Test
    fun `add new opens a blank editing draft`() =
        runTest(dispatcherRule.testDispatcher) {
            passphrase.firstTimeSetup(correctPassphrase) // leaves session unlocked

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial

                viewModel.perform(CredentialsManagerAction.AddNew)
                val state = awaitState()
                assertNotNull(
                    "AddNew MUST open a blank editing draft",
                    state.editing,
                )
                assertEquals(
                    "the AddNew draft MUST start with an empty display name",
                    "",
                    state.editing?.displayName,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE
    /**
     * Edit on an existing id → the editing draft is populated with that stored
     * entry, so the screen pre-fills the form with the saved data the user edits.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in CredentialsManagerViewModel.Edit, replace
     *             `reduce { state.copy(editing = existing) }` with
     *             `reduce { state.copy(editing = empty()) }`.
     *   Observed: this test FAILS — the editing draft carries a random UUID id and
     *             a blank name instead of the stored "edit-me"/"Edit Me".
     *   Reverted: yes.
     */
    @Test
    fun `edit populates the editing draft from the stored entry`() =
        runTest(dispatcherRule.testDispatcher) {
            passphrase.firstTimeSetup(correctPassphrase)
            repo.seed(entry("edit-me", "Edit Me", CredentialSecret.ApiKey("k-edit")))

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial
                awaitState() // onCreate observe() collected the seeded entry

                viewModel.perform(CredentialsManagerAction.Edit("edit-me"))
                val state = awaitState()
                assertNotNull("Edit MUST open the editing draft", state.editing)
                assertEquals(
                    "Edit MUST populate the draft with the stored entry's id",
                    "edit-me",
                    state.editing?.id,
                )
                assertEquals(
                    "Edit MUST populate the draft with the stored display name",
                    "Edit Me",
                    state.editing?.displayName,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE
    /**
     * DismissEdit while a draft is open → the editing form is closed (editing =
     * null), so the screen returns to the entry list.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in CredentialsManagerViewModel.DismissEdit, replace the body
     *             with `Unit` (do nothing).
     *   Observed: this test FAILS at "DismissEdit MUST close the editing form" —
     *             editing remains the open draft instead of becoming null.
     *   Reverted: yes.
     */
    @Test
    fun `dismiss edit closes the editing form`() =
        runTest(dispatcherRule.testDispatcher) {
            passphrase.firstTimeSetup(correctPassphrase)

            val viewModel = newViewModel()
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial

                viewModel.perform(CredentialsManagerAction.AddNew)
                assertNotNull("precondition: a draft is open", awaitState().editing)

                viewModel.perform(CredentialsManagerAction.DismissEdit)
                assertNull(
                    "DismissEdit MUST close the editing form",
                    awaitState().editing,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    private fun entry(id: String, name: String, secret: CredentialSecret): CredentialsEntry =
        CredentialsEntry(
            id = id,
            displayName = name,
            secret = secret,
            createdAtUtc = 0L,
            updatedAtUtc = 0L,
        )
}

/**
 * Behaviorally-equivalent in-memory [CredentialsEntryRepository], matching the
 * observable contract of `CredentialsEntryRepositoryImpl`:
 *  - observe() is a live Flow that re-emits on every mutation,
 *  - upsert() replaces any existing row with the same id (DAO upsert semantics),
 *  - delete() removes the row from read paths (the user-visible effect of the
 *    real soft-delete),
 *  - get() returns the current row or null.
 * It does NOT mock the SUT; it stands in only for the outermost Room boundary.
 */
private class FakeCredentialsEntryRepository : CredentialsEntryRepository {
    private val state = MutableStateFlow<List<CredentialsEntry>>(emptyList())

    /** Pre-populate without going through upsert (already-stored rows). */
    fun seed(entry: CredentialsEntry) {
        state.value = state.value.filterNot { it.id == entry.id } + entry
    }

    override fun observe(): Flow<List<CredentialsEntry>> = state

    override suspend fun list(): List<CredentialsEntry> = state.value

    override suspend fun get(id: String): CredentialsEntry? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(entry: CredentialsEntry) {
        state.value = state.value.filterNot { it.id == entry.id } + entry
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
}

/** In-memory [PassphraseManager.Storage], mirroring `SharedPrefsPassphraseStorage`. */
private class InMemoryPassphraseStorage : PassphraseManager.Storage {
    private var salt: ByteArray? = null
    private var verifier: ByteArray? = null
    override fun saveSalt(b: ByteArray) { salt = b }
    override fun getSalt(): ByteArray? = salt
    override fun saveVerifier(b: ByteArray) { verifier = b }
    override fun getVerifier(): ByteArray? = verifier
}
