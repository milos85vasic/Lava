package lava.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import lava.onboarding.steps.ApiSelectionStep
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
    // Task 3.5/3.6 (2026-06-03): optional key-reader seam. Production
    // callers (MainActivity) pass an ApiKeyClient-backed lambda; the
    // default null leaves apiKeyReader unwired (key is omitted but probe
    // still runs — graceful degradation per spec §7).
    // The lambda type is a pure Kotlin function — no import from :app needed.
    apiKeyReader: ((authority: String) -> String?)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.collectAsState()
    val context = LocalContext.current

    // Wire the key-reader into the ViewModel once on first composition.
    // Uses Unit as the key so this fires exactly once per screen lifetime.
    LaunchedEffect(Unit) {
        viewModel.setApiKeyReader(apiKeyReader)
    }

    // Task 3.5 (2026-06-03): track whether the API app is installed so
    // ApiSelectionStep can show the correct button label. Updated by the
    // LaunchIntent side-effect handler: ACTION_MAIN = installed, ACTION_VIEW = absent.
    val onDeviceApiInstalled = remember { mutableStateOf(false) }

    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is OnboardingSideEffect.Finish -> onComplete()
            // §6.AB onboarding-gate enforcement: ExitApp from Welcome
            // back-press MUST close the app entirely (host calls
            // finishAffinity()) — NOT mark onboarding complete. Next
            // launch will re-enter onboarding because
            // `onboardingComplete` was never written.
            is OnboardingSideEffect.ExitApp -> onExitApp()
            // Task 3.5 (2026-06-03), refactored: the ViewModel already resolved
            // "installed → open intent WITH handoff extras" vs "not installed →
            // Firebase download URL intent" — the screen just fires startActivity.
            // Installed path: ACTION_MAIN + CATEGORY_LAUNCHER + extras (no market://).
            // Not-installed path: ACTION_VIEW → Firebase distribution URL.
            // §6.AC: no-telemetry — AnalyticsTracker not in Composable; ViewModel
            // error path is the telemetry surface.
            is OnboardingSideEffect.LaunchIntent -> {
                val isLaunchIntent = sideEffect.intent.action == Intent.ACTION_MAIN
                try {
                    context.startActivity(sideEffect.intent)
                    // Track install state: ACTION_MAIN = app was installed.
                    onDeviceApiInstalled.value = isLaunchIntent
                } catch (e: ActivityNotFoundException) {
                    // Race: app was uninstalled between check and tap.
                    // Or: ACTION_VIEW intent URI scheme not handled (unlikely for https://).
                    // Graceful no-op — user can retry.
                    onDeviceApiInstalled.value = false
                }
            }
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
            // 62nd §6.L invocation (2026-05-18): smoother non-annoying
            // animations per operator directive. FastOutSlowInEasing on
            // the slide track + LinearOutSlowIn on the fade overlap
            // produces a Material-spec polished feel without the
            // distraction of a long animation. 320ms slide + 220ms fade
            // is fast enough to feel snappy + slow enough to read.
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(
                durationMillis = 320,
                easing = FastOutSlowInEasing,
            )
            val fadeSpec = tween<Float>(
                durationMillis = 220,
                easing = LinearOutSlowInEasing,
            )
            (slideInHorizontally(animationSpec = slideSpec) { it * dir } + fadeIn(animationSpec = fadeSpec)) togetherWith
                (slideOutHorizontally(animationSpec = slideSpec) { -it * dir } + fadeOut(animationSpec = fadeSpec))
        },
    ) { step ->
        when (step) {
            OnboardingStep.Welcome -> WelcomeStep(
                providerCount = state.providers.size,
                onGetStarted = { viewModel.perform(OnboardingAction.NextStep) },
            )
            // 60th §6.L invocation (2026-05-18): new API selection step
            OnboardingStep.ApiSelection -> ApiSelectionStep(
                discoveryRunning = state.apiDiscoveryRunning,
                discovered = state.discoveredApis,
                selected = state.selectedApi,
                connectivity = state.apiConnectivity,
                onSelect = { viewModel.perform(OnboardingAction.SelectApi(it)) },
                onRetryDiscovery = { viewModel.perform(OnboardingAction.NextStep) }, // re-enters discovery via step refresh
                onRetryProbe = { viewModel.perform(OnboardingAction.RetryApiProbe) },
                // Cloud / remote-server section (2026-05-31 operator request)
                cloudInput = state.cloudAddressInput,
                cloudDefaults = state.cloudDefaults,
                cloudError = state.cloudAddressError,
                onCloudInputChange = { viewModel.perform(OnboardingAction.CloudAddressChanged(it)) },
                onAddCloud = { viewModel.perform(OnboardingAction.AddCloudApi) },
                // Task 3.5 (2026-06-03): "On this device" section
                onLaunchOnDeviceApi = { viewModel.perform(OnboardingAction.LaunchOnDeviceApi) },
                onDeviceApiInstalled = onDeviceApiInstalled.value,
            )
            OnboardingStep.Providers -> ProvidersStep(
                providers = state.providers,
                hasSelection = viewModel.hasSelectedProviders(),
                onToggle = { viewModel.perform(OnboardingAction.ToggleProvider(it)) },
                onNext = { viewModel.perform(OnboardingAction.NextStep) },
                // Phase 5 (2026-06-11): surface the dynamic-discovery fallback
                // notice when the API catalogue fetch failed (bundled list shown).
                catalogNotice = state.providerCatalogNotice,
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
