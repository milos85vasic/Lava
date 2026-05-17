package lava.provider.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialsEntryRepository
import lava.credentials.ProviderConfigRepository
import lava.database.dao.ClonedProviderDao
import lava.database.dao.ProviderCredentialBindingDao
import lava.database.dao.ProviderSyncToggleDao
import lava.database.dao.UserMirrorDao
import lava.database.entity.ProviderCredentialBindingEntity
import lava.database.entity.ProviderSyncToggleEntity
import lava.database.entity.UserMirrorEntity
import lava.designsystem.color.ProviderColors
import lava.domain.usecase.CloneProviderUseCase
import lava.domain.usecase.ProbeMirrorUseCase
import lava.domain.usecase.RemoveClonedProviderUseCase
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import lava.tracker.client.LavaTrackerSdk
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
class ProviderConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sdk: LavaTrackerSdk,
    private val credentialsRepo: CredentialsEntryRepository,
    private val providerConfigRepository: ProviderConfigRepository,
    private val bindingDao: ProviderCredentialBindingDao,
    private val toggleDao: ProviderSyncToggleDao,
    private val userMirrorDao: UserMirrorDao,
    private val clonedProviderDao: ClonedProviderDao,
    private val probe: ProbeMirrorUseCase,
    private val cloneProvider: CloneProviderUseCase,
    private val removeClonedProvider: RemoveClonedProviderUseCase,
    private val outbox: SyncOutbox,
    private val analytics: AnalyticsTracker,
) : ContainerHost<ProviderConfigState, ProviderConfigSideEffect>, ViewModel() {

    private val providerId: String = savedStateHandle.get<String>(PROVIDER_ID_KEY).orEmpty()

    override val container = container<ProviderConfigState, ProviderConfigSideEffect>(
        initialState = ProviderConfigState(providerId = providerId),
        onCreate = {
            val descriptor = sdk.listAvailableTrackers()
                .firstOrNull { it.trackerId == providerId }
            // SP-4 Phase G.2: check whether this providerId is a user-cloned
            // synthetic id. The clone surfaces a destructive Remove action;
            // original (registered) providers do not.
            val isClone = clonedProviderDao.getAll().any { it.syntheticId == providerId }
            reduce {
                state.copy(
                    descriptor = descriptor,
                    displayName = descriptor?.displayName.orEmpty(),
                    color = ProviderColors.forProvider(providerId),
                    descriptorMirrors = descriptor?.baseUrls?.map { it.url }.orEmpty(),
                    isClone = isClone,
                )
            }
            observeAll()
        },
    )

    fun perform(action: ProviderConfigAction) = intent {
        when (action) {
            ProviderConfigAction.ToggleSync -> {
                // Sweep Finding #10 closure (2026-05-17, §6.L 59th).
                //
                // Pre-fix: `val next = !state.syncEnabled` could read a
                // STALE `false` default if the user tapped the Switch
                // BEFORE `observeAll()` emitted the persisted `true`. The
                // upsert then wrote `false` on top of the persisted `true`
                // (silent flip). Read the persisted value from the DAO
                // synchronously so the toggle is deterministic regardless
                // of observe-flow timing.
                val current = toggleDao.get(providerId)?.enabled ?: false
                val next = !current
                toggleDao.upsert(ProviderSyncToggleEntity(providerId, next))
                outbox.enqueue(SyncOutboxKind.SYNC_TOGGLE, json.encodeToString(WireToggle(providerId, next)))
            }
            ProviderConfigAction.ToggleAnonymous -> {
                // Sweep Finding #1 closure (2026-05-17, §6.L 59th).
                //
                // Pre-fix: handler did `reduce { state.copy(anonymous = !state.anonymous) }`
                // — in-memory only. The Switch flipped, the user closed the
                // app, and on next launch the default `anonymous = false`
                // won (§6.J bluff: tested state-after-mutation; never
                // tested state-after-recreate).
                //
                // Fix: persist via [ProviderConfigRepository.setUseAnonymous].
                // The mutation goes to provider_configs.use_anonymous; the
                // observeAll() flow re-emits → `state.anonymous` re-binds.
                // The user-visible Switch + Header reflect the persisted
                // value across process restarts. Read the persisted value
                // (not state.anonymous) before computing the toggle so the
                // first-tap race (analogous to Finding #10) cannot leak.
                val current = providerConfigRepository.load(providerId)?.useAnonymous ?: false
                providerConfigRepository.setUseAnonymous(providerId, !current)
            }
            ProviderConfigAction.OpenAssignSheet -> reduce { state.copy(showAssignSheet = true) }
            ProviderConfigAction.DismissAssignSheet -> reduce { state.copy(showAssignSheet = false) }
            is ProviderConfigAction.BindCredential -> {
                bindingDao.upsert(ProviderCredentialBindingEntity(providerId, action.credentialId))
                outbox.enqueue(SyncOutboxKind.BINDING, json.encodeToString(WireBinding(providerId, action.credentialId)))
                reduce { state.copy(showAssignSheet = false) }
                postSideEffect(ProviderConfigSideEffect.ShowToast("Credential bound"))
            }
            ProviderConfigAction.UnbindCredential -> {
                bindingDao.unbind(providerId)
                outbox.enqueue(SyncOutboxKind.BINDING, json.encodeToString(WireBinding(providerId, credentialId = null)))
            }
            is ProviderConfigAction.AddMirror -> {
                val trimmed = action.url.trim()
                if (trimmed.isEmpty()) return@intent
                // §6.O closure for Crashlytics 39469d3bc00aabf76a86d5d15f2e7f2b:
                // user can type any string in the AddMirror field; pre-fix,
                // "djdnjd" reached ProbeMirrorUseCase + crashed inside
                // okhttp's URL builder. Reject at the input boundary
                // before storing in the DB. The defense-in-depth catch
                // in ProbeMirrorUseCase covers any remaining bad URL that
                // sneaks past validation (e.g. via sync-from-other-device).
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    postSideEffect(
                        ProviderConfigSideEffect.ShowToast(
                            "Mirror URL must start with http:// or https://",
                        ),
                    )
                    analytics.recordWarning(
                        "AddMirror rejected — missing scheme",
                        mapOf(
                            AnalyticsTracker.Params.FEATURE to "provider_config",
                            AnalyticsTracker.Params.OPERATION to "add_mirror",
                            AnalyticsTracker.Params.PROVIDER to providerId,
                            // NEVER include the raw URL — could contain
                            // sensitive segments per §6.H. Just the length
                            // + first 3 chars hash signature for triage.
                            "url_length" to trimmed.length.toString(),
                            "url_prefix" to trimmed.take(3),
                        ),
                    )
                    return@intent
                }
                val entity = UserMirrorEntity(
                    trackerId = providerId,
                    url = trimmed,
                    priority = (state.userMirrors.size + state.descriptorMirrors.size + 1),
                    protocol = if (trimmed.startsWith("http://")) "HTTP" else "HTTPS",
                    addedAt = System.currentTimeMillis(),
                )
                userMirrorDao.upsert(entity)
                outbox.enqueue(
                    SyncOutboxKind.USER_MIRROR,
                    json.encodeToString(
                        WireMirror(providerId, trimmed, entity.priority, entity.protocol, removed = false),
                    ),
                )
            }
            is ProviderConfigAction.RemoveMirror -> {
                userMirrorDao.delete(providerId, action.url)
                outbox.enqueue(
                    SyncOutboxKind.USER_MIRROR,
                    json.encodeToString(
                        WireMirror(providerId, action.url, priority = 0, protocol = "HTTPS", removed = true),
                    ),
                )
            }
            is ProviderConfigAction.ProbeMirror -> {
                val result = probe(action.url)
                reduce { state.copy(probeResults = state.probeResults + (action.url to result)) }
            }
            ProviderConfigAction.OpenCloneDialog -> reduce { state.copy(showCloneDialog = true) }
            ProviderConfigAction.DismissCloneDialog -> reduce { state.copy(showCloneDialog = false) }
            is ProviderConfigAction.ConfirmClone -> {
                val source = state.descriptor?.trackerId ?: return@intent
                cloneProvider(
                    sourceTrackerId = source,
                    displayName = action.displayName,
                    primaryUrl = action.primaryUrl,
                )
                reduce { state.copy(showCloneDialog = false) }
                // SP-4 Phase F.2 / F.2.6 (2026-05-13): URL routing override
                // is live — clone HTTP traffic actually hits the clone's
                // primaryUrl. The F.1-era disclosure ("URL routing
                // pending — searches use source URLs") is dropped.
                postSideEffect(ProviderConfigSideEffect.ShowToast("Cloned"))
            }
            // SP-4 Phase G.2 — destructive clone removal.
            ProviderConfigAction.OpenRemoveCloneDialog ->
                reduce { state.copy(showRemoveCloneDialog = true) }
            ProviderConfigAction.DismissRemoveCloneDialog ->
                reduce { state.copy(showRemoveCloneDialog = false) }
            ProviderConfigAction.ConfirmRemoveClone -> {
                if (!state.isClone) return@intent
                removeClonedProvider(providerId)
                reduce { state.copy(showRemoveCloneDialog = false) }
                postSideEffect(ProviderConfigSideEffect.ShowToast("Clone removed"))
                postSideEffect(ProviderConfigSideEffect.NavigateBack)
            }
        }
    }

    private fun org.orbitmvi.orbit.syntax.simple.SimpleSyntax<ProviderConfigState, ProviderConfigSideEffect>.observeAll() {
        viewModelScope.launch {
            combine(
                bindingDao.observe(providerId),
                toggleDao.observe(providerId),
                userMirrorDao.observe(providerId),
                credentialsRepo.observe(),
                // Sweep Finding #1 (2026-05-17): include the persisted
                // ProviderConfig so `use_anonymous` round-trips into
                // `state.anonymous`. The single-emission default (null)
                // maps to `anonymous = false`, identical to the entity's
                // column default, so brand-new providers render the
                // Switch as off before the user has ever opened the row.
                providerConfigRepository.observe(providerId),
            ) { binding, toggle, mirrors, allCreds, config ->
                Snapshot(binding, toggle, mirrors, allCreds, config)
            }
                .distinctUntilChanged()
                .collect { snap ->
                    val bound = snap.binding?.let { b -> snap.creds.firstOrNull { it.id == b.credentialId } }
                    reduce {
                        state.copy(
                            syncEnabled = snap.toggle?.enabled ?: false,
                            anonymous = snap.config?.useAnonymous ?: false,
                            boundCredential = bound,
                            availableCredentials = snap.creds,
                            userMirrors = snap.mirrors.map { it.url },
                        )
                    }
                }
        }
    }

    @kotlinx.serialization.Serializable
    private data class WireToggle(val providerId: String, val enabled: Boolean)

    @kotlinx.serialization.Serializable
    private data class WireBinding(val providerId: String, val credentialId: String?)

    @kotlinx.serialization.Serializable
    private data class WireMirror(
        val providerId: String,
        val url: String,
        val priority: Int,
        val protocol: String,
        val removed: Boolean,
    )

    private data class Snapshot(
        val binding: ProviderCredentialBindingEntity?,
        val toggle: ProviderSyncToggleEntity?,
        val mirrors: List<UserMirrorEntity>,
        val creds: List<lava.credentials.model.CredentialsEntry>,
        // Sweep Finding #1 (2026-05-17): carries the persisted
        // ProviderConfig so `use_anonymous` round-trips into the UI.
        val config: lava.credentials.ProviderConfig?,
    )

    companion object {
        const val PROVIDER_ID_KEY: String = "provider_id"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
