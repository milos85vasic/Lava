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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lava.designsystem.color.ProviderColors
import lava.designsystem.component.AppBar
import lava.designsystem.component.BackButton
import lava.designsystem.component.CircularProgressIndicator
import lava.designsystem.component.Divider
import lava.designsystem.component.Icon
import lava.designsystem.component.Scaffold
import lava.designsystem.component.Text
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.navigation.viewModel

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
                }
                is CredentialsSideEffect.NavigateToProviderLogin -> {
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
                    modifier = Modifier.navigationBarsPadding(),
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
                    EmptyCredentialsState()
                }
                else -> {
                    CredentialsList(
                        credentials = state.credentials,
                        onEdit = { item ->
                            viewModel.onAction(
                                CredentialsAction.ShowEditDialog(
                                    item.providerId,
                                    item.displayName,
                                ),
                            )
                        },
                        onClear = { item ->
                            viewModel.onAction(
                                CredentialsAction.ClearCredentials(item.providerId),
                            )
                        },
                    )
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
private fun CredentialsList(
    credentials: List<ProviderCredentialUiModel>,
    onEdit: (ProviderCredentialUiModel) -> Unit,
    onClear: (ProviderCredentialUiModel) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppTheme.spaces.large,
            end = AppTheme.spaces.large,
            top = AppTheme.spaces.large,
            bottom = AppTheme.spaces.extraLarge + AppTheme.spaces.extraLarge,
        ),
    ) {
        item {
            Column(
                modifier = Modifier.padding(bottom = AppTheme.spaces.large),
            ) {
                Text(
                    text = stringResource(R.string.credentials_manage_title),
                    style = AppTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(AppTheme.spaces.small))
                Text(
                    text = stringResource(R.string.credentials_manage_subtitle),
                    style = AppTheme.typography.bodyMedium,
                    color = AppTheme.colors.outline,
                )
            }
        }

        items(credentials) { item ->
            val isLast = credentials.lastOrNull() == item
            CredentialCard(
                model = item,
                onEdit = { onEdit(item) },
                onClear = { onClear(item) },
            )
            if (!isLast) {
                Divider(
                    modifier = Modifier.padding(
                        start = AppTheme.spaces.extraLarge + AppTheme.spaces.small,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EmptyCredentialsState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon = LavaIcons.Password,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = AppTheme.colors.outline,
        )
        Spacer(modifier = Modifier.height(AppTheme.spaces.large))
        Text(
            text = stringResource(R.string.credentials_no_providers),
            style = AppTheme.typography.titleMedium,
            color = AppTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.height(AppTheme.spaces.small))
        Text(
            text = stringResource(R.string.credentials_no_providers_desc),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colors.outline,
        )
    }
}

@Composable
private fun CredentialCard(
    model: ProviderCredentialUiModel,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    val providerColor = ProviderColors.forProvider(model.providerId)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spaces.small),
        shape = AppTheme.shapes.medium,
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
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(providerColor, AppTheme.shapes.circle),
            )

            Spacer(modifier = Modifier.width(AppTheme.spaces.medium))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = AppTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(AppTheme.spaces.small))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spaces.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val badgeText = when (model.authType) {
                        "FORM_LOGIN", "password" ->
                            stringResource(R.string.credentials_password)
                        "token" -> stringResource(R.string.credentials_token)
                        "apikey" -> stringResource(R.string.credentials_api_key)
                        "cookie" -> stringResource(R.string.credentials_cookie)
                        else -> model.authType.replaceFirstChar { it.uppercase() }
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(badgeText) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = providerColor.copy(alpha = 0.12f),
                            labelColor = providerColor,
                        ),
                        shape = AppTheme.shapes.small,
                    )
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

            IconButton(onClick = onEdit) {
                Icon(
                    icon = LavaIcons.Edit,
                    contentDescription = stringResource(R.string.credentials_edit),
                    tint = AppTheme.colors.outline,
                )
            }
            if (model.isAuthenticated) {
                IconButton(onClick = onClear) {
                    Icon(
                        icon = LavaIcons.Clear,
                        contentDescription = stringResource(R.string.credentials_clear),
                        tint = AppTheme.colors.error,
                    )
                }
            }
        }

        if (model.username != null) {
            Divider()
            Text(
                text = stringResource(R.string.credentials_user, model.username),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.outline,
                modifier = Modifier.padding(
                    horizontal = AppTheme.spaces.large,
                    vertical = AppTheme.spaces.small,
                ),
            )
        }
    }
}
