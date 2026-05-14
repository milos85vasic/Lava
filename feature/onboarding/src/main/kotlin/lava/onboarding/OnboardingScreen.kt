package lava.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import lava.onboarding.steps.ConfigureStep
import lava.onboarding.steps.ProvidersStep
import lava.onboarding.steps.SummaryStep
import lava.onboarding.steps.WelcomeStep
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.collectAsState()

    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is OnboardingSideEffect.Finish -> onComplete()
            // §6.AB onboarding-gate enforcement: ExitApp from Welcome
            // back-press MUST close the app entirely (host calls
            // finishAffinity()) — NOT mark onboarding complete. Next
            // launch will re-enter onboarding because
            // `onboardingComplete` was never written.
            is OnboardingSideEffect.ExitApp -> onExitApp()
        }
    }

    // Intercept system back on every step; the ViewModel's onBackStep() decides
    // whether to walk back through provider config, return to a prior step, or
    // (only on Welcome) emit Finish so the host can leave the wizard.
    BackHandler(enabled = true) {
        viewModel.perform(OnboardingAction.BackStep)
    }

    // §6.Q-spirit fix: MainActivity calls enableEdgeToEdge, so onboarding draws
    // under the status bar + gesture/navigation bar on tall-aspect devices
    // (Samsung Galaxy S23 Ultra, Pixel 9 Pro XL). safeDrawing covers status
    // bars + nav bars + display cutout + IME so each step's title row stays
    // visible and Configure's text fields stay above the keyboard.
    AnimatedContent(
        targetState = state.step,
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                (slideOutHorizontally { -it * dir } + fadeOut())
        },
    ) { step ->
        when (step) {
            OnboardingStep.Welcome -> WelcomeStep(
                providerCount = state.providers.size,
                onGetStarted = { viewModel.perform(OnboardingAction.NextStep) },
            )
            OnboardingStep.Providers -> ProvidersStep(
                providers = state.providers,
                hasSelection = viewModel.hasSelectedProviders(),
                onToggle = { viewModel.perform(OnboardingAction.ToggleProvider(it)) },
                onNext = { viewModel.perform(OnboardingAction.NextStep) },
            )
            OnboardingStep.Configure -> {
                val provider = viewModel.currentProvider() ?: return@AnimatedContent
                val config = state.configs[provider.trackerId] ?: return@AnimatedContent
                ConfigureStep(
                    provider = provider,
                    config = config,
                    isRunning = state.connectionTestRunning,
                    onUsernameChanged = { viewModel.perform(OnboardingAction.UsernameChanged(it)) },
                    onPasswordChanged = { viewModel.perform(OnboardingAction.PasswordChanged(it)) },
                    onToggleAnonymous = { viewModel.perform(OnboardingAction.ToggleAnonymous(it)) },
                    onTestAndContinue = { viewModel.perform(OnboardingAction.TestAndContinue) },
                )
            }
            OnboardingStep.Summary -> SummaryStep(
                providers = state.providers,
                configs = state.configs,
                onStartExploring = { viewModel.perform(OnboardingAction.Finish) },
            )
        }
    }
}
