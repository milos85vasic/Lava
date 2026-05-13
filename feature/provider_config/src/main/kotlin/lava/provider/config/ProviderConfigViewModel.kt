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
import lava.credentials.CredentialsEntryRepository
import lava.database.dao.ProviderCredentialBindingDao
import lava.database.dao.ProviderSyncToggleDao
import lava.database.dao.UserMirrorDao
import lava.database.entity.ProviderCredentialBindingEntity
import lava.database.entity.ProviderSyncToggleEntity
import lava.database.entity.UserMirrorEntity
import lava.designsystem.color.ProviderColors
import lava.domain.usecase.CloneProviderUseCase
import lava.domain.usecase.ProbeMirrorUseCase
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
    private val bindingDao: ProviderCredentialBindingDao,
    private val toggleDao: ProviderSyncToggleDao,
    private val userMirrorDao: UserMirrorDao,
    private val probe: ProbeMirrorUseCase,
    private val cloneProvider: CloneProviderUseCase,
    private val outbox: SyncOutbox,
) : ContainerHost<ProviderConfigState, ProviderConfigSideEffect>, ViewModel() {

    private val providerId: String = savedStateHandle.get<String>(PROVIDER_ID_KEY).orEmpty()

    override val container = container<ProviderConfigState, ProviderConfigSideEffect>(
        initialState = ProviderConfigState(providerId = providerId),
        onCreate = {
            val descriptor = sdk.listAvailableTrackers()
                .firstOrNull { it.trackerId == providerId }
            reduce {
                state.copy(
                    descriptor = descriptor,
                    displayName = descriptor?.displayName.orEmpty(),
                    color = ProviderColors.forProvider(providerId),
                    descriptorMirrors = descriptor?.baseUrls?.map { it.url }.orEmpty(),
                )
            }
            observeAll()
        },
    )

    fun perform(action: ProviderConfigAction) = intent {
        when (action) {
            ProviderConfigAction.ToggleSync -> {
                val next = !state.syncEnabled
                toggleDao.upsert(ProviderSyncToggleEntity(providerId, next))
                outbox.enqueue(SyncOutboxKind.SYNC_TOGGLE, json.encodeToString(WireToggle(providerId, next)))
            }
            ProviderConfigAction.ToggleAnonymous -> {
                reduce { state.copy(anonymous = !state.anonymous) }
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
                // SP-4 Phase F.1: the clone is reachable + results are tagged
                // with the clone's id, but the URL routing override is owed
                // (Phase F.2). Disclosed in the Toast copy.
                postSideEffect(
                    ProviderConfigSideEffect.ShowToast(
                        "Cloned (URL routing pending — searches use source URLs)",
                    ),
                )
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
            ) { binding, toggle, mirrors, allCreds ->
                Snapshot(binding, toggle, mirrors, allCreds)
            }
                .distinctUntilChanged()
                .collect { snap ->
                    val bound = snap.binding?.let { b -> snap.creds.firstOrNull { it.id == b.credentialId } }
                    reduce {
                        state.copy(
                            syncEnabled = snap.toggle?.enabled ?: false,
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
    )

    companion object {
        const val PROVIDER_ID_KEY: String = "provider_id"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
