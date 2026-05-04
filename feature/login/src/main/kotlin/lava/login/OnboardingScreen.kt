package lava.login

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import org.orbitmvi.orbit.compose.collectSideEffect

/**
 * Mandatory onboarding screen shown on first launch when the user has no
 * configured credentials for any provider.
 *
 * Wraps [ProviderLoginScreen] and intercepts the success side-effect to
 * signal completion. The back navigation is blocked — the user must
 * authenticate with at least one provider (or enable anonymous mode for
 * a provider that supports it) before they can access the main app.
 *
 * Added in Multi-Provider Extension (Task 6.15).
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val viewModel: ProviderLoginViewModel = hiltViewModel()

    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is LoginSideEffect.Success -> onComplete()
            else -> Unit
        }
    }

    ProviderLoginScreen(
        viewModel = viewModel,
        back = { /* no-op: back is blocked during mandatory onboarding */ },
    )
}
