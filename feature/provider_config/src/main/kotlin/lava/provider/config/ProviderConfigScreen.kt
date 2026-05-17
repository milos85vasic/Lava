package lava.provider.config

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import lava.provider.config.sections.RemoveCloneSection
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
            // SP-4 Phase G.2 — after Remove clone, pop the screen.
            ProviderConfigSideEffect.NavigateBack -> onBack()
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
    // Sweep finding #3 closure (2026-05-17, 1.2.29-1049). Pre-fix the
    // root container was Column(verticalScroll(rememberScrollState())) with
    // statically-rendered sections. With many mirrors per provider the
    // static composition computed layout for every row up-front, producing
    // scroll-jank on lower-end devices. Convert to LazyColumn so each
    // section composes only when scrolled into view. §6.Q stays satisfied
    // because there are no nested lazy layouts — each section is a single
    // item() in the outer LazyColumn (or two, for the inner Divider).
    //
    // Architectural detail: the inner sections (MirrorsSection,
    // CredentialsSection, etc.) remain regular @Composable functions that
    // render Column/Row trees. Only the outer container changes from
    // Column-with-scroll to LazyColumn. Their static composition is fine
    // when wrapped in item() because LazyColumn defers them per-viewport.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        item { Header(state) }
        item { Divider() }
        item { SyncSection(enabled = state.syncEnabled, onAction = onAction) }
        item { Divider() }
        item {
            CredentialsSection(
                state = state,
                onAction = onAction,
                onOpenCredentialsManager = onOpenCredentialsManager,
            )
        }
        item { Divider() }
        item { MirrorsSection(state = state, onAction = onAction) }
        item { Divider() }
        if (state.descriptor?.supportsAnonymous == true) {
            item { AnonymousSection(enabled = state.anonymous, onAction = onAction) }
            item { Divider() }
        }
        item { CloneSection(state = state, onAction = onAction) }
        // SP-4 Phase G.2 — Remove affordance, visible only for cloned providers.
        if (state.isClone) {
            item { Divider() }
            item { RemoveCloneSection(state = state, onAction = onAction) }
        }
    }
}
