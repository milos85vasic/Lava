package lava.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import lava.designsystem.component.AppBar
import lava.designsystem.component.BackButton
import lava.designsystem.component.Button
import lava.designsystem.component.CircularProgressIndicator
import lava.designsystem.component.Icon
import lava.designsystem.component.Scaffold
import lava.designsystem.component.Text
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

/**
 * Multi-provider login screen.
 *
 * Wireframe reference: docs/refactoring/multi_provieders/wireframe_provider_login.png
 *
 * Shows a list of providers with credential status. Tapping a provider
 * reveals the login form for that provider.
 */
@Composable
internal fun ProviderLoginScreen(
    viewModel: ProviderLoginViewModel,
    back: () -> Unit,
) {
    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is LoginSideEffect.Success -> back()
            else -> Unit // Error and HideKeyboard handled internally
        }
    }
    val state by viewModel.collectAsState()
    ProviderLoginScreen(state = state, onAction = viewModel::perform, back = back)
}

@Composable
internal fun ProviderLoginScreen(
    state: ProviderLoginState,
    onAction: (ProviderLoginAction) -> Unit,
    back: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppBar(
                title = {
                    Text(
                        if (state.selectedProviderId != null) {
                            stringResource(R.string.provider_login_title)
                        } else {
                            stringResource(R.string.provider_login_select_provider)
                        },
                    )
                },
                navigationIcon = {
                    BackButton(onClick = {
                        if (state.selectedProviderId != null) {
                            onAction(ProviderLoginAction.BackToProviders)
                        } else {
                            back()
                        }
                    })
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.selectedProviderId != null -> {
                    ProviderCredentialForm(
                        state = state,
                        onAction = onAction,
                    )
                }

                else -> {
                    ProviderList(
                        state = state,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderList(
    state: ProviderLoginState,
    onAction: (ProviderLoginAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.spaces.large),
    ) {
        // Anonymous access toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.provider_login_anonymous_access),
                    style = AppTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.provider_login_anonymous_description),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.outline,
                )
            }
            Switch(
                checked = state.anonymousMode,
                onCheckedChange = { onAction(ProviderLoginAction.SetAnonymousMode(it)) },
                modifier = Modifier.testTag(AnonymousAccessSwitchTestTag),
            )
        }

        Spacer(modifier = Modifier.height(AppTheme.spaces.large))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(AppTheme.spaces.medium),
        ) {
            items(state.providers) { provider ->
                ProviderCard(
                    provider = provider,
                    onClick = { onAction(ProviderLoginAction.SelectProvider(provider.providerId)) },
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderLoginItem,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = AppTheme.elevations.small),
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.colors.surface,
            contentColor = AppTheme.colors.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppTheme.spaces.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spaces.medium),
        ) {
            // Provider color circle with initials
            Box(
                modifier = Modifier
                    .size(AppTheme.sizes.large)
                    .clip(CircleShape)
                    .background(providerColor(provider.providerId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = provider.displayName.take(2).uppercase(),
                    color = Color.White,
                    style = AppTheme.typography.labelLarge,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = AppTheme.typography.titleMedium,
                )
                Text(
                    text = provider.providerType,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.primary,
                )
            }

            if (provider.isAuthenticated) {
                Icon(
                    icon = LavaIcons.Selected,
                    contentDescription = stringResource(R.string.provider_login_authenticated),
                    tint = AppTheme.colors.primary,
                )
            }
        }
    }
}

@Composable
private fun ProviderCredentialForm(
    state: ProviderLoginState,
    onAction: (ProviderLoginAction) -> Unit,
) {
    val provider = state.providers.firstOrNull { it.providerId == state.selectedProviderId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.spaces.large)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spaces.medium),
    ) {
        if (provider != null) {
            Text(
                text = stringResource(R.string.provider_login_sign_in_to, provider.displayName),
                style = AppTheme.typography.headlineSmall,
            )

            // Phase 1.5 UI alignment (2026-05-04): the screen renders the
            // Continue button only when this provider WILL accept the
            // anonymous bypass. Without the supportsAnonymous gate, a user
            // could pick a no-anonymous provider (RuTracker CAPTCHA_LOGIN,
            // Kinozal/NNM-Club FORM_LOGIN), see "Continue" because the
            // global anonymousMode toggle is on, tap it, and the
            // ViewModel's onSubmitClick would silently fall through to
            // sdk.login() with empty credentials → InputState.Invalid +
            // confusing UI state. The ViewModel gate (Phase 1.5) blocks
            // the bypass; this UI gate prevents the misleading button
            // from rendering in the first place.
            if (provider.authType == "NONE" ||
                (state.anonymousMode && provider.supportsAnonymous)
            ) {
                Text(
                    text = stringResource(R.string.provider_login_no_auth_required),
                    style = AppTheme.typography.bodyMedium,
                    color = AppTheme.colors.outline,
                )
                Spacer(modifier = Modifier.height(AppTheme.spaces.large))
                Button(
                    text = stringResource(R.string.provider_login_continue),
                    onClick = { onAction(ProviderLoginAction.SubmitClick) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Show the existing credential form
                UsernameInputField(
                    state = LoginState(
                        isLoading = state.isLoading,
                        usernameInput = state.usernameInput,
                        passwordInput = state.passwordInput,
                        captchaInput = state.captchaInput,
                        captcha = state.captcha,
                    ),
                    onChanged = { onAction(ProviderLoginAction.UsernameChanged(it)) },
                    onSelectNext = {},
                )
                PasswordInputField(
                    state = LoginState(
                        isLoading = state.isLoading,
                        usernameInput = state.usernameInput,
                        passwordInput = state.passwordInput,
                        captchaInput = state.captchaInput,
                        captcha = state.captcha,
                    ),
                    onChanged = { onAction(ProviderLoginAction.PasswordChanged(it)) },
                    onSelectNext = {},
                    onSubmit = { onAction(ProviderLoginAction.SubmitClick) },
                )
                if (state.hasCaptcha) {
                    CaptchaImage(
                        captcha = state.captcha!!,
                        onRetry = { onAction(ProviderLoginAction.ReloadCaptchaClick) },
                    )
                    CaptchaInputField(
                        state = LoginState(
                            isLoading = state.isLoading,
                            usernameInput = state.usernameInput,
                            passwordInput = state.passwordInput,
                            captchaInput = state.captchaInput,
                            captcha = state.captcha,
                        ),
                        onChanged = { onAction(ProviderLoginAction.CaptchaChanged(it)) },
                        onSubmit = { onAction(ProviderLoginAction.SubmitClick) },
                    )
                }
                LoginButton(
                    state = LoginState(
                        isLoading = state.isLoading,
                        usernameInput = state.usernameInput,
                        passwordInput = state.passwordInput,
                        captchaInput = state.captchaInput,
                        captcha = state.captcha,
                    ),
                    onSubmit = { onAction(ProviderLoginAction.SubmitClick) },
                )
            }
        }
    }
}

@Composable
private fun providerColor(providerId: String): Color {
    return when (providerId) {
        "rutracker" -> AppTheme.colors.accentBlue
        "rutor" -> AppTheme.colors.accentRed
        "nnmclub" -> AppTheme.colors.accentGreen
        "kinozal" -> AppTheme.colors.accentOrange
        "archiveorg" -> AppTheme.colors.primary
        "gutenberg" -> AppTheme.colors.accentOrange
        else -> AppTheme.colors.primary
    }
}

/**
 * Test tag for the Anonymous Access switch on the provider list. Used by
 * `app/src/androidTest/.../Challenge03AnonymousSearchOnRuTorTest` and any
 * future Compose UI tests that need to interact with the toggle without
 * relying on coordinate-based input.
 */
const val AnonymousAccessSwitchTestTag = "anonymous_access_switch"
