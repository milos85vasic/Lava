package lava.login

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Mandatory onboarding screen shown on first launch when the user has no
 * configured credentials for any provider.
 *
 * Wraps [ProviderLoginScreen]. Uses the screen's `back` callback as the
 * completion signal — when the user finishes login (real auth, anonymous
 * mode toggled, or AuthType.NONE Continue), [ProviderLoginScreen] emits
 * [LoginSideEffect.Success] and its own `collectSideEffect` handler
 * invokes `back()`. We bind `back = onComplete` so that single dispatch
 * routes the user out of mandatory onboarding into the main app.
 *
 * Forensic anchor (clause 6.J / clause 6.L): a previous version of this
 * file ALSO called `viewModel.collectSideEffect { ... onComplete() }`
 * with `back = no-op`. That created two competing collectors on the
 * same Orbit container; the inner one (in ProviderLoginScreen) won the
 * dispatch, called the no-op `back()`, and the outer `onComplete` never
 * fired — leaving the user stranded on the Select Provider / Login
 * screen forever. Fixed 2026-05-04 by collapsing to one collector path.
 *
 * Falsifiability rehearsal (Sixth Law clause 2):
 *   1. Replace `back = onComplete` with `back = { /* no-op */ }`.
 *   2. Reinstate the duplicate `viewModel.collectSideEffect` block.
 *   3. Manual rehearsal on the gating emulator: tap Continue on
 *      Internet Archive → screen stays put (the original 2026-05-04
 *      finding from .lava-ci-evidence/sixth-law-incidents/
 *      2026-05-04-onboarding-navigation.json).
 *   4. Revert; rehearsal succeeds → main app appears.
 *
 * Added in Multi-Provider Extension (Task 6.15); navigation fix
 * 2026-05-04 in response to the onboarding-navigation incident.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val viewModel: ProviderLoginViewModel = hiltViewModel()

    ProviderLoginScreen(
        viewModel = viewModel,
        back = onComplete,
    )
}
