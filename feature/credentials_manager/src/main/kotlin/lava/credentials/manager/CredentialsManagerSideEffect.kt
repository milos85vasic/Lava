package lava.credentials.manager

sealed interface CredentialsManagerSideEffect {
    data class ShowToast(val msg: String) : CredentialsManagerSideEffect
}
