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
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import lava.testing.rule.MainDispatcherRule
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
        viewModel.test(this) {
            runOnCreate()
            awaitState() // loading
            awaitState() // loaded

            viewModel.onAction(CredentialsAction.SelectProvider("rutracker"))
            val state = awaitState()
            assertEquals("rutracker", state.selectedProvider)
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
    }
}
