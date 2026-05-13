package lava.provider.config

sealed interface ProviderConfigAction {
    /** SP-4 Phase C — make this provider the SDK's active target. Removed when Phase D's multi-provider parallel search lands. */
    data object MakeActive : ProviderConfigAction
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
}
