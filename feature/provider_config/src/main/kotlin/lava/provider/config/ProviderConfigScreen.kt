package lava.provider.config

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import lava.designsystem.component.AppBar
import lava.designsystem.component.BackButton
import lava.designsystem.component.Divider
import lava.designsystem.component.Scaffold
import lava.designsystem.component.Text
import lava.navigation.viewModel
import lava.provider.config.sections.AnonymousSection
import lava.provider.config.sections.CloneSection
import lava.provider.config.sections.CredentialsSection
import lava.provider.config.sections.Header
import lava.provider.config.sections.MirrorsSection
import lava.provider.config.sections.SyncSection
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun ProviderConfigScreen(
    onBack: () -> Unit,
    onOpenCredentialsManager: () -> Unit,
    viewModel: ProviderConfigViewModel = viewModel(),
) {
    val context = LocalContext.current
    viewModel.collectSideEffect { effect ->
        when (effect) {
            is ProviderConfigSideEffect.ShowToast ->
                Toast.makeText(context, effect.msg, Toast.LENGTH_SHORT).show()
        }
    }
    val state by viewModel.collectAsState()
    ProviderConfigScreen(state, viewModel::perform, onBack, onOpenCredentialsManager)
}

@Composable
private fun ProviderConfigScreen(
    state: ProviderConfigState,
    onAction: (ProviderConfigAction) -> Unit,
    onBack: () -> Unit,
    onOpenCredentialsManager: () -> Unit,
) = Scaffold(
    topBar = { appBarState ->
        AppBar(
            navigationIcon = { BackButton(onClick = onBack) },
            title = { Text(state.displayName.ifEmpty { state.providerId }) },
            appBarState = appBarState,
        )
    },
) { padding ->
    // §6.Q: no LazyColumn inside verticalScroll. Sections render statically.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        Header(state)
        Divider()
        SyncSection(enabled = state.syncEnabled, onAction = onAction)
        Divider()
        CredentialsSection(
            state = state,
            onAction = onAction,
            onOpenCredentialsManager = onOpenCredentialsManager,
        )
        Divider()
        MirrorsSection(state = state, onAction = onAction)
        Divider()
        if (state.descriptor?.supportsAnonymous == true) {
            AnonymousSection(enabled = state.anonymous, onAction = onAction)
            Divider()
        }
        CloneSection(state = state, onAction = onAction)
    }
}
