package lava.provider.config

sealed interface ProviderConfigSideEffect {
    data class ShowToast(val msg: String) : ProviderConfigSideEffect

    /** SP-4 Phase G.2 — pop the ProviderConfig destination after a clone removal. */
    data object NavigateBack : ProviderConfigSideEffect
}
