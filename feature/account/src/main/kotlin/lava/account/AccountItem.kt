package lava.account

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import lava.designsystem.component.Body
import lava.designsystem.component.CollectionPreviewParameterProvider
import lava.designsystem.component.ConfirmationDialog
import lava.designsystem.component.IconButton
import lava.designsystem.component.Surface
import lava.designsystem.component.TextButton
import lava.designsystem.component.ThemePreviews
import lava.designsystem.component.rememberConfirmationDialogState
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.designsystem.theme.LavaTheme
import lava.models.auth.AuthState
import lava.navigation.viewModel
import lava.ui.component.Avatar
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect
import lava.designsystem.R as dsR

@Composable
fun AccountItem(
    modifier: Modifier = Modifier,
    onLoginClick: () -> Unit,
) = AccountItem(
    modifier = modifier,
    viewModel = viewModel(),
    onLoginClick = onLoginClick,
)

@Composable
internal fun AccountItem(
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel,
    onLoginClick: () -> Unit,
) {
    val confirmationDialogState = rememberConfirmationDialogState()
    ConfirmationDialog(confirmationDialogState)
    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is AccountSideEffect.OpenLogin -> onLoginClick()
            is AccountSideEffect.ShowLogoutConfirmation -> confirmationDialogState.show(
                title = R.string.account_item_logout_title,
                text = R.string.account_item_logout_confirmation,
                onConfirm = { viewModel.perform(AccountAction.ConfirmLogoutClick) },
                onDismiss = confirmationDialogState::hide,
            )
        }
    }
    val state by viewModel.collectAsState()
    AccountItem(
        modifier = modifier,
        state = state,
        onAction = viewModel::perform,
    )
}

@Composable
internal fun AccountItem(
    modifier: Modifier = Modifier,
    state: AuthState,
    onAction: (AccountAction) -> Unit,
) {
    val avatarUrl = remember(state) {
        when (state) {
            is AuthState.Authorized -> state.avatarUrl
            is AuthState.Unauthorized -> null
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.large),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppTheme.spaces.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(avatarUrl)
            when (state) {
                is AuthState.Authorized -> {
                    Body(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = AppTheme.spaces.large),
                        text = state.name,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                    IconButton(
                        icon = LavaIcons.Logout,
                        contentDescription = stringResource(dsR.string.designsystem_action_logout),
                        tint = AppTheme.colors.primary,
                        onClick = { onAction(AccountAction.LogoutClick) },
                    )
                }

                is AuthState.Unauthorized -> {
                    TextButton(
                        text = stringResource(R.string.account_item_login_action),
                        onClick = { onAction(AccountAction.LoginClick) },
                    )
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun AccountItem_Preview(
    @PreviewParameter(AccountItemParamProvider::class) authState: AuthState,
) = LavaTheme {
    AccountItem(state = authState, onAction = {})
}

private class AccountItemParamProvider : CollectionPreviewParameterProvider<AuthState>(
    AuthState.Unauthorized,
    AuthState.Authorized("Long-long user name", null),
)
