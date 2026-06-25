/*
 * AccountTestHostActivity — a minimal @AndroidEntryPoint host for
 * Challenge C57 (account).
 *
 * WHY THIS EXISTS:
 *   The production `AccountItem()` composable (feature/account) resolves
 *   its own `AccountViewModel` via `viewModel()` (= `hiltViewModel()`),
 *   so rendering the REAL composable with the REAL ViewModel requires a
 *   Hilt-capable `ViewModelStoreOwner`. As of 2026-06-25 `AccountItem`
 *   is NOT mounted by any production screen (it is an orphan feature
 *   module with only a @Preview — an honest finding recorded in C57's
 *   KDoc), so there is no in-app navigation path to it. This host
 *   activity composes the EXACT production `AccountItem(onLoginClick)`
 *   overload — same composable, same `hiltViewModel()` resolution, same
 *   real `AccountViewModel` + real `ObserveAuthStateUseCase` — so C57 can
 *   drive the real account rendering on a device without faking the VM.
 *
 *   This is NOT a Hilt module replacement and NOT a mock: it is a thin
 *   @AndroidEntryPoint Activity (the same component shape MainActivity
 *   uses) whose only job is to call setContent { AccountItem(...) }. The
 *   ViewModel, the auth-state observation, and the rendered tree are all
 *   real production code.
 *
 * Classification: project-specific (test infrastructure for Lava's
 * feature/account; the @AndroidEntryPoint host pattern is universal).
 */
package lava.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import lava.account.AccountItem
import lava.designsystem.theme.LavaTheme

@AndroidEntryPoint
class AccountTestHostActivity : ComponentActivity() {

    /** Set true by the test when the account row posts OpenLogin. */
    val openLoginInvoked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LavaTheme {
                // The PUBLIC production composable — resolves the real
                // AccountViewModel via hiltViewModel(). No state injection.
                AccountItem(
                    onLoginClick = { openLoginInvoked.value = true },
                )
            }
        }
    }
}
