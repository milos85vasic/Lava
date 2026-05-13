package lava.provider.config

sealed interface ProviderConfigAction {
    data object ToggleSync : ProviderConfigAction
    data object ToggleAnonymous : ProviderConfigAction
    data object OpenAssignSheet : ProviderConfigAction
    data object DismissAssignSheet : ProviderConfigAction
    data class BindCredential(val credentialId: String) : ProviderConfigAction
    data object UnbindCredential : ProviderConfigAction
    data class AddMirror(val url: String) : ProviderConfigAction
    data class RemoveMirror(val url: String) : ProviderConfigAction
    data class ProbeMirror(val url: String) : ProviderConfigAction
    data object OpenCloneDialog : ProviderConfigAction
    data object DismissCloneDialog : ProviderConfigAction
    data class ConfirmClone(val displayName: String, val primaryUrl: String) : ProviderConfigAction

    /** SP-4 Phase G.2 — surface the destructive Remove confirmation dialog. */
    data object OpenRemoveCloneDialog : ProviderConfigAction
    data object DismissRemoveCloneDialog : ProviderConfigAction

    /** SP-4 Phase G.2 — soft-delete the clone + pop back to Menu. */
    data object ConfirmRemoveClone : ProviderConfigAction
}
