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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect
import androidx.compose.material3.Button as MaterialButton

/**
 * Multi-provider login screen.
 *
 * Wireframe reference: docs/refactoring/multi_provieders/wireframe_provider_login.png
 *
 * Shows a list of providers with credential status. Tapping a provider
 * reveals the login form for that provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderLoginScreen(
    state: ProviderLoginState,
    onAction: (ProviderLoginAction) -> Unit,
    back: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.selectedProviderId != null) "Login" else "Select Provider") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectedProviderId != null) {
                            onAction(ProviderLoginAction.BackToProviders)
                        } else {
                            back()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
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
            .padding(16.dp),
    ) {
        // Anonymous access toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Anonymous Access",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Some providers support browsing without login",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(
                checked = state.anonymousMode,
                onCheckedChange = { onAction(ProviderLoginAction.SetAnonymousMode(it)) },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Provider color circle with initials
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(providerColor(provider.providerId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = provider.displayName.take(2).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = provider.providerType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (provider.isAuthenticated) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Authenticated",
                    tint = MaterialTheme.colorScheme.primary,
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
            .padding(16.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (provider != null) {
            Text(
                text = "Sign in to ${provider.displayName}",
                style = MaterialTheme.typography.headlineSmall,
            )

            if (provider.authType == "NONE" || state.anonymousMode) {
                Text(
                    text = "This provider does not require authentication.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(16.dp))
                MaterialButton(
                    onClick = { onAction(ProviderLoginAction.SubmitClick) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Text("Continue")
                }
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
        "rutracker" -> Color(0xFF1E88E5)
        "rutor" -> Color(0xFFE53935)
        "nnmclub" -> Color(0xFF43A047)
        "kinozal" -> Color(0xFFFDD835)
        "archiveorg" -> Color(0xFF8E24AA)
        "gutenberg" -> Color(0xFFFDD835)
        else -> MaterialTheme.colorScheme.primary
    }
}
