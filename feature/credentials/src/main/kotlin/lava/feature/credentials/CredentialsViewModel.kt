package lava.feature.credentials

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import lava.credentials.ProviderCredentialManager
import lava.tracker.client.LavaTrackerSdk
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

/**
 * Orbit MVI ViewModel for the Credentials Management screen.
 *
 * Added in Multi-Provider Extension (Task 6.10).
 */
@HiltViewModel
class CredentialsViewModel @Inject constructor(
    private val credentialManager: ProviderCredentialManager,
    private val sdk: LavaTrackerSdk,
) : ContainerHost<CredentialsState, CredentialsSideEffect>, ViewModel() {

    override val container = container<CredentialsState, CredentialsSideEffect>(
        initialState = CredentialsState(loading = true),
        onCreate = { load() },
    )

    fun onAction(action: CredentialsAction) = intent {
        when (action) {
            CredentialsAction.Load -> load()
            is CredentialsAction.SelectProvider -> {
                reduce { state.copy(selectedProvider = action.providerId) }
            }
            is CredentialsAction.SavePassword -> {
                credentialManager.setPassword(
                    action.providerId,
                    action.username,
                    action.password,
                )
                postSideEffect(CredentialsSideEffect.ShowToast("Credentials saved"))
                load()
            }
            is CredentialsAction.SaveApiKey -> {
                credentialManager.setApiKey(action.providerId, action.apiKey)
                postSideEffect(CredentialsSideEffect.ShowToast("API key saved"))
                load()
            }
            is CredentialsAction.ClearCredentials -> {
                credentialManager.clear(action.providerId)
                postSideEffect(CredentialsSideEffect.ShowToast("Credentials cleared"))
                load()
            }
            is CredentialsAction.ShowEditDialog -> {
                val existing = state.credentials.firstOrNull { it.providerId == action.providerId }
                reduce {
                    state.copy(
                        dialogState = CredentialDialogState(
                            providerId = action.providerId,
                            providerDisplayName = action.providerDisplayName,
                            credentialType = when (existing?.authType) {
                                "password" -> CredentialType.PASSWORD
                                "token" -> CredentialType.TOKEN
                                "apikey" -> CredentialType.API_KEY
                                else -> CredentialType.PASSWORD
                            },
                            label = existing?.let { "${it.displayName} Login" } ?: "",
                            username = existing?.username ?: "",
                            isEditing = existing?.isAuthenticated ?: false,
                        ),
                    )
                }
            }
            CredentialsAction.DismissDialog -> {
                reduce { state.copy(dialogState = null) }
            }
            is CredentialsAction.SetCredentialType -> {
                reduce {
                    state.copy(dialogState = state.dialogState?.copy(credentialType = action.type))
                }
            }
            is CredentialsAction.SetLabel -> {
                reduce {
                    state.copy(dialogState = state.dialogState?.copy(label = action.label))
                }
            }
            is CredentialsAction.SetUsername -> {
                reduce {
                    state.copy(dialogState = state.dialogState?.copy(username = action.username))
                }
            }
            is CredentialsAction.SetPassword -> {
                reduce {
                    state.copy(dialogState = state.dialogState?.copy(password = action.password))
                }
            }
            is CredentialsAction.SetToken -> {
                reduce {
                    state.copy(dialogState = state.dialogState?.copy(token = action.token))
                }
            }
            is CredentialsAction.SetApiKey -> {
                reduce {
                    state.copy(dialogState = state.dialogState?.copy(apiKey = action.apiKey))
                }
            }
            is CredentialsAction.SetApiSecret -> {
                reduce {
                    state.copy(dialogState = state.dialogState?.copy(apiSecret = action.apiSecret))
                }
            }
            is CredentialsAction.SubmitDialog -> {
                val dialog = state.dialogState ?: return@intent
                when (dialog.credentialType) {
                    CredentialType.PASSWORD -> {
                        if (dialog.username.isNotBlank() && dialog.password.isNotBlank()) {
                            credentialManager.setPassword(
                                dialog.providerId,
                                dialog.username,
                                dialog.password,
                            )
                        }
                    }
                    CredentialType.TOKEN -> {
                        if (dialog.token.isNotBlank()) {
                            credentialManager.setToken(dialog.providerId, dialog.token)
                        }
                    }
                    CredentialType.API_KEY -> {
                        if (dialog.apiKey.isNotBlank()) {
                            credentialManager.setApiKey(
                                dialog.providerId,
                                dialog.apiKey,
                                dialog.apiSecret.takeIf { it.isNotBlank() },
                            )
                        }
                    }
                }
                postSideEffect(CredentialsSideEffect.ShowToast("Credentials saved"))
                reduce { state.copy(dialogState = null) }
                load()
            }
        }
    }

    private suspend fun org.orbitmvi.orbit.syntax.simple.SimpleSyntax<CredentialsState, CredentialsSideEffect>.load() {
        reduce { state.copy(loading = true, error = null) }
        try {
            val descriptors = sdk.listAvailableTrackers()
            val creds = credentialManager.observeAll().first()
            val uiModels = descriptors.map { desc ->
                val cred = creds.firstOrNull { it.providerId == desc.trackerId }
                ProviderCredentialUiModel(
                    providerId = desc.trackerId,
                    displayName = desc.displayName,
                    authType = desc.authType.name,
                    isAuthenticated = cred != null && cred.authType != "none",
                    username = cred?.username,
                )
            }
            reduce {
                state.copy(
                    loading = false,
                    credentials = uiModels,
                    error = null,
                )
            }
        } catch (t: Throwable) {
            reduce { state.copy(loading = false, error = t.message ?: "load failed") }
        }
    }
}
