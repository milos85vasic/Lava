package lava.feature.credentials

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(
    onBack: () -> Unit,
    viewModel: CredentialsViewModel = viewModel(),
) {
    val state by viewModel.container.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.container.sideEffectFlow.collect { effect ->
            when (effect) {
                is CredentialsSideEffect.ShowToast ->
                    snackbarHostState.showSnackbar(effect.message)
                is CredentialsSideEffect.NavigateToProviderLogin -> {
                    // TODO: Navigate to provider-specific login screen
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credentials") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // Only show FAB when we have providers to configure
            if (state.credentials.isNotEmpty()) {
                FloatingActionButton(onClick = {
                    // Open dialog for the first provider as default
                    val first = state.credentials.firstOrNull()
                    if (first != null) {
                        viewModel.onAction(
                            CredentialsAction.ShowEditDialog(
                                first.providerId,
                                first.displayName,
                            ),
                        )
                    }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add credential")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            text = "No providers available",
            style = MaterialTheme.typography.bodyLarge,
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Provider color dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(providerColor(model.providerId)),
                    )
                    Column {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Auth type badge
                            val badgeText = when (model.authType) {
                                "FORM_LOGIN", "password" -> "Password"
                                "token" -> "Token"
                                "apikey" -> "API Key"
                                "cookie" -> "Cookie"
                                else -> model.authType.replaceFirstChar { it.uppercase() }
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(badgeText) },
                            )
                            // Auth status
                            Text(
                                text = if (model.isAuthenticated) "✓ Authenticated" else "○ Anonymous",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (model.isAuthenticated) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit credential",
                        )
                    }
                    if (model.isAuthenticated) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear credential",
                            )
                        }
                    }
                }
            }

            if (model.username != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "User: ${model.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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


