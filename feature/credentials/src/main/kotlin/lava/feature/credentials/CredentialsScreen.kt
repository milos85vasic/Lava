package lava.feature.credentials

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lava.designsystem.component.AppBar
import lava.designsystem.component.BackButton
import lava.designsystem.component.CircularProgressIndicator
import lava.designsystem.component.Icon
import lava.designsystem.component.Scaffold
import lava.designsystem.component.Text
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.navigation.viewModel

/**
 * Credentials Management screen (Multi-Provider Extension, Task 6.11).
 *
 * Displays a list of providers with their authentication status.
 * Tapping a provider opens an edit dialog where the user can enter
 * or clear credentials.
 *
 * Wireframe reference: docs/refactoring/multi_provieders/wireframe_credentials.png
 */
@Composable
fun CredentialsScreen(
    onBack: () -> Unit,
    viewModel: CredentialsViewModel = viewModel(),
) {
    val state by viewModel.container.stateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.container.sideEffectFlow.collect { effect ->
            when (effect) {
                is CredentialsSideEffect.ShowToast -> {
                    // Snackbar is handled by Scaffold's internal SnackbarHost
                }
                is CredentialsSideEffect.NavigateToProviderLogin -> {
                    // TODO: Navigate to provider-specific login screen
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = { Text(stringResource(R.string.credentials_title)) },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
        floatingActionButton = {
            if (state.credentials.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        val first = state.credentials.firstOrNull()
                        if (first != null) {
                            viewModel.onAction(
                                CredentialsAction.ShowEditDialog(
                                    first.providerId,
                                    first.displayName,
                                ),
                            )
                        }
                    },
                    containerColor = AppTheme.colors.primaryContainer,
                    contentColor = AppTheme.colors.onPrimaryContainer,
                ) {
                    Icon(
                        icon = LavaIcons.Add,
                        contentDescription = stringResource(R.string.credentials_add),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.credentials.isEmpty() -> {
                    EmptyCredentialsState(
                        onCreateClick = {
                            // No providers available; this shouldn't happen in practice
                        },
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(AppTheme.spaces.large),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spaces.medium),
                    ) {
                        items(state.credentials) { item ->
                            CredentialCard(
                                model = item,
                                onEdit = {
                                    viewModel.onAction(
                                        CredentialsAction.ShowEditDialog(
                                            item.providerId,
                                            item.displayName,
                                        ),
                                    )
                                },
                                onClear = {
                                    viewModel.onAction(
                                        CredentialsAction.ClearCredentials(item.providerId),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        state.dialogState?.let { dialogState ->
            CredentialEditDialog(
                state = dialogState,
                onAction = viewModel::onAction,
            )
        }
    }
}

@Composable
private fun EmptyCredentialsState(
    onCreateClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.credentials_no_providers),
            style = AppTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun CredentialCard(
    model: ProviderCredentialUiModel,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = AppTheme.elevations.small),
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.colors.surface,
            contentColor = AppTheme.colors.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppTheme.spaces.large),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spaces.medium),
                ) {
                    // Provider color dot
                    Box(
                        modifier = Modifier
                            .size(AppTheme.spaces.medium)
                            .clip(CircleShape)
                            .background(providerColor(model.providerId)),
                    )
                    Column {
                        Text(
                            text = model.displayName,
                            style = AppTheme.typography.titleMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spaces.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Auth type badge
                            val badgeText = when (model.authType) {
                                "FORM_LOGIN", "password" -> stringResource(R.string.credentials_password)
                                "token" -> stringResource(R.string.credentials_token)
                                "apikey" -> stringResource(R.string.credentials_api_key)
                                "cookie" -> stringResource(R.string.credentials_cookie)
                                else -> model.authType.replaceFirstChar { it.uppercase() }
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(badgeText) },
                            )
                            // Auth status
                            Text(
                                text = if (model.isAuthenticated) {
                                    stringResource(R.string.credentials_authenticated)
                                } else {
                                    stringResource(R.string.credentials_anonymous)
                                },
                                style = AppTheme.typography.bodySmall,
                                color = if (model.isAuthenticated) {
                                    AppTheme.colors.primary
                                } else {
                                    AppTheme.colors.outline
                                },
                            )
                        }
                    }
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            icon = LavaIcons.Edit,
                            contentDescription = stringResource(R.string.credentials_edit),
                        )
                    }
                    if (model.isAuthenticated) {
                        IconButton(onClick = onClear) {
                            Icon(
                                icon = LavaIcons.Clear,
                                contentDescription = stringResource(R.string.credentials_clear),
                            )
                        }
                    }
                }
            }

            if (model.username != null) {
                Spacer(modifier = Modifier.height(AppTheme.spaces.small))
                Text(
                    text = stringResource(R.string.credentials_user, model.username),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.outline,
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
