package lava.provider.config

sealed interface ProviderConfigSideEffect {
    data class ShowToast(val msg: String) : ProviderConfigSideEffect
}
