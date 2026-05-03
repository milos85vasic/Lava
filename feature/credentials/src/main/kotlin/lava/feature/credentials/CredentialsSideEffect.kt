package lava.feature.credentials

/**
 * MVI side effects for the Credentials Management screen.
 *
 * Added in Multi-Provider Extension (Task 6.9).
 */
sealed interface CredentialsSideEffect {
    data class ShowToast(val message: String) : CredentialsSideEffect
    data class NavigateToProviderLogin(val providerId: String) : CredentialsSideEffect
}
