package lava.provider.config

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialsEntryRepository
import lava.credentials.ProviderConfigRepository
import lava.credentials.model.CredentialsEntry
import lava.database.AppDatabase
import lava.database.entity.SyncOutboxEntity
import lava.domain.usecase.CloneProviderUseCase
import lava.domain.usecase.ProbeMirrorUseCase
import lava.domain.usecase.RemoveClonedProviderUseCase
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import lava.testing.rule.MainDispatcherRule
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §11.4.146 reproduce-first regression coverage for the prod 1.3.11(1075)
 * release crash: Settings → any provider → toggle "Sync this provider" →
 * `kotlinx.serialization.SerializationException: Serializer for class
 * 'WireToggle' is not found`.
 *
 * ROOT CAUSE: `feature/provider_config/build.gradle.kts` did NOT apply the
 * `lava.kotlin.serialization` convention plugin (which applies the
 * `org.jetbrains.kotlin.plugin.serialization` compiler plugin). The
 * `kotlinx-serialization-json` RUNTIME + `encodeToString` API leaked
 * transitively via `:core:domain`, so the code COMPILED — but the compiler
 * plugin that generates the `$serializer` for `@Serializable` `WireToggle`
 * / `WireBinding` / `WireMirror` was never applied to this module, so
 * `json.encodeToString(WireToggle(...))` threw at runtime. R8 release
 * (no serialization keep rules in `app/proguard-rules.pro`) would strip it
 * too → release-only crash.
 *
 * Why the prior `ProviderConfigViewModelTest` was a BLUFF here: its
 * `NoopOutbox.enqueue` returns `0L` and never inspects the payload, so the
 * `json.encodeToString(...)` argument WAS evaluated by the production VM
 * (and would have thrown) — but the test's settle-loops read the DAO row,
 * not the enqueued payload, and the upsert happens BEFORE the enqueue line.
 * In ToggleSync the `toggleDao.upsert(...)` (line 91) runs and persists
 * BEFORE the crashing `outbox.enqueue(..., json.encodeToString(...))`
 * (line 92), so a test that asserts only on the persisted toggle row goes
 * GREEN while the user-visible action still crashes (the side-effect /
 * outbox half never completes).
 *
 * This test instead uses a RECORDING outbox and asserts the production
 * code path REACHES the enqueue WITH A VALID JSON payload — the
 * user-observable "the toggle/bind/mirror change was queued for sync"
 * outcome. On the current (broken) code, `json.encodeToString(WireToggle)`
 * throws inside the Orbit `intent {}` coroutine BEFORE `enqueue` is called,
 * so no payload is ever recorded → the assertion below fails (RED). After
 * applying `lava.kotlin.serialization`, the `$serializer` is generated, the
 * payload is recorded as valid JSON → GREEN.
 *
 * All THREE latent-same-class wire types are covered:
 *   WireToggle  (ToggleSync,     SYNC_TOGGLE)
 *   WireBinding (BindCredential, BINDING)
 *   WireMirror  (AddMirror,      USER_MIRROR)
 *
 * R8-release verification is OWED at the §6.Z device gate — a JVM unit test
 * cannot exercise R8 resource/code shrinking. The `app/proguard-rules.pro`
 * keep rules added alongside this test are the fix for the release variant;
 * proving they hold requires running the release APK on the gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ProviderConfigWireSerializationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var providerConfigRepository: ProviderConfigRepository
    private lateinit var recordingOutbox: RecordingOutbox

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        providerConfigRepository = ProviderConfigRepository(db.providerConfigDao())
        recordingOutbox = RecordingOutbox()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * WireToggle — the exact prod 1.3.11(1075) crash surface.
     *
     * Falsifiability rehearsal (§11.4.146 / Sixth Law clause 2):
     *   Mutation: remove `id("lava.kotlin.serialization")` from
     *             feature/provider_config/build.gradle.kts (the bug state).
     *   Observed: `SerializationException: Serializer for class 'WireToggle'
     *             is not found` thrown inside the ToggleSync intent → the
     *             enqueue is never reached → this test fails with
     *             "ToggleSync MUST enqueue a SYNC_TOGGLE payload — none was
     *             recorded (serialization threw before enqueue)".
     *   Reverted: yes.
     */
    @Test
    fun `ToggleSync enqueues a valid-JSON WireToggle payload, does not crash`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(providerId = "rutracker")
            val stateJob = launch { vm.container.stateFlow.collect { } }
            try {
                vm.perform(ProviderConfigAction.ToggleSync)
                awaitEnqueue(SyncOutboxKind.SYNC_TOGGLE)

                val recorded = recordingOutbox.payloadFor(SyncOutboxKind.SYNC_TOGGLE)
                assertTrue(
                    "ToggleSync MUST enqueue a SYNC_TOGGLE payload — none was " +
                        "recorded (serialization threw before enqueue). recorded=${recordingOutbox.all}",
                    recorded != null,
                )
                // User-observable contract: a valid JSON object carrying the
                // provider id + the new enabled flag was queued for sync.
                val obj = Json.parseToJsonElement(recorded!!).jsonObject
                assertEquals(
                    "rutracker",
                    obj["providerId"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "true",
                    obj["enabled"]!!.jsonPrimitive.content,
                )
            } finally {
                stateJob.cancel()
            }
        }

    /**
     * WireBinding — same latent serialization class, BindCredential path.
     *
     * Falsifiability rehearsal:
     *   Mutation: remove the serialization plugin (bug state).
     *   Observed: `SerializationException: Serializer for class 'WireBinding'
     *             is not found` → no BINDING payload recorded → this test fails
     *             "BindCredential MUST enqueue a BINDING payload".
     *   Reverted: yes.
     */
    @Test
    fun `BindCredential enqueues a valid-JSON WireBinding payload, does not crash`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(providerId = "rutracker")
            val stateJob = launch { vm.container.stateFlow.collect { } }
            try {
                vm.perform(ProviderConfigAction.BindCredential("cred-1"))
                awaitEnqueue(SyncOutboxKind.BINDING)

                val recorded = recordingOutbox.payloadFor(SyncOutboxKind.BINDING)
                assertTrue(
                    "BindCredential MUST enqueue a BINDING payload — none was " +
                        "recorded (serialization threw before enqueue). recorded=${recordingOutbox.all}",
                    recorded != null,
                )
                val obj = Json.parseToJsonElement(recorded!!).jsonObject
                assertEquals(
                    "rutracker",
                    obj["providerId"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "cred-1",
                    obj["credentialId"]!!.jsonPrimitive.content,
                )
            } finally {
                stateJob.cancel()
            }
        }

    /**
     * WireMirror — same latent serialization class, AddMirror path.
     *
     * Falsifiability rehearsal:
     *   Mutation: remove the serialization plugin (bug state).
     *   Observed: `SerializationException: Serializer for class 'WireMirror'
     *             is not found` → no USER_MIRROR payload recorded → this test
     *             fails "AddMirror MUST enqueue a USER_MIRROR payload".
     *   Reverted: yes.
     */
    @Test
    fun `AddMirror enqueues a valid-JSON WireMirror payload, does not crash`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(providerId = "rutracker")
            val stateJob = launch { vm.container.stateFlow.collect { } }
            try {
                // A well-formed https URL passes the input-boundary scheme
                // guard so the enqueue path executes.
                vm.perform(ProviderConfigAction.AddMirror("https://mirror.example/rt"))
                awaitEnqueue(SyncOutboxKind.USER_MIRROR)

                val recorded = recordingOutbox.payloadFor(SyncOutboxKind.USER_MIRROR)
                assertTrue(
                    "AddMirror MUST enqueue a USER_MIRROR payload — none was " +
                        "recorded (serialization threw before enqueue). recorded=${recordingOutbox.all}",
                    recorded != null,
                )
                val obj = Json.parseToJsonElement(recorded!!).jsonObject
                assertEquals(
                    "rutracker",
                    obj["providerId"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "https://mirror.example/rt",
                    obj["url"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "false",
                    obj["removed"]!!.jsonPrimitive.content,
                )
            } finally {
                stateJob.cancel()
            }
        }

    /** Bounded real-time wait for the enqueue to land (cross-thread emission). */
    private suspend fun awaitEnqueue(kind: SyncOutboxKind) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                recordingOutbox.payloadFor(kind) == null
            ) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    private fun createViewModel(providerId: String): ProviderConfigViewModel {
        val sdk = LavaTrackerSdk(
            registry = DefaultTrackerRegistry(),
            clonedProviderDao = db.clonedProviderDao(),
        )
        return ProviderConfigViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(ProviderConfigViewModel.PROVIDER_ID_KEY to providerId),
            ),
            sdk = sdk,
            credentialsRepo = WireTestNoopCredentialsEntryRepository,
            providerConfigRepository = providerConfigRepository,
            bindingDao = db.providerCredentialBindingDao(),
            toggleDao = db.providerSyncToggleDao(),
            userMirrorDao = db.userMirrorDao(),
            clonedProviderDao = db.clonedProviderDao(),
            probe = ProbeMirrorUseCase(OkHttpClient()),
            cloneProvider = CloneProviderUseCase(db.clonedProviderDao(), recordingOutbox),
            removeClonedProvider = RemoveClonedProviderUseCase(db.clonedProviderDao(), recordingOutbox),
            outbox = recordingOutbox,
            analytics = recordingAnalytics,
        )
    }

    private val recordingAnalytics = object : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }
}

/**
 * Records every (kind, payload) the production VM enqueues. The payload is
 * the OUTPUT of the production `json.encodeToString(Wire*(...))` call — so
 * if serialization throws, nothing is recorded for that kind. This is the
 * user-observable "queued for sync" surface.
 */
private class RecordingOutbox : SyncOutbox {
    val all = mutableListOf<Pair<SyncOutboxKind, String>>()
    private var nextId = 1L
    override suspend fun enqueue(kind: SyncOutboxKind, payload: String): Long {
        all.add(kind to payload)
        return nextId++
    }
    override fun observe(): Flow<List<SyncOutboxEntity>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun ack(id: Long) = Unit
    fun payloadFor(kind: SyncOutboxKind): String? =
        all.firstOrNull { it.first == kind }?.second
}

/** No-op CredentialsEntryRepository — VM only reads observe() in these tests. */
private object WireTestNoopCredentialsEntryRepository : CredentialsEntryRepository {
    private val flow = MutableSharedFlow<List<CredentialsEntry>>(replay = 1).apply { tryEmit(emptyList()) }
    override fun observe() = flow.asSharedFlow()
    override suspend fun list(): List<CredentialsEntry> = emptyList()
    override suspend fun get(id: String): CredentialsEntry? = null
    override suspend fun upsert(entry: CredentialsEntry) = Unit
    override suspend fun delete(id: String) = Unit
}
