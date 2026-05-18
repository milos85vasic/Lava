package lava.onboarding.steps

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lava.designsystem.component.Button
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.theme.AppTheme
import lava.models.settings.Endpoint
import lava.onboarding.ApiConnectivityState

/**
 * The API discovery + selection step inserted as the FIRST screen the
 * user interacts with after Welcome (60th §6.L invocation, 2026-05-18).
 *
 * UX contract:
 * - On entry, [discoveryRunning] is true; show a spinner + "Searching..."
 * - As [discovered] populates (via mDNS NSD), render each entry tappable.
 * - Tapping an entry triggers a connectivity probe (`ConnectionService
 *   .isReachable`). [connectivity] becomes `Testing`; on success the
 *   ViewModel advances the step; on failure [connectivity] is `Failure`
 *   and the user can pick another or retry.
 * - If no APIs are discovered, surface a "Search again" affordance.
 *
 * §6.Q compliance: this screen uses `Column(verticalScroll)` with the
 * discovered-list as plain composables (NOT a LazyColumn) — onboarding
 * lists are bounded to N≈3 candidates so LazyColumn is unnecessary and
 * would violate §6.Q if nested in a parent scroller.
 *
 * §6.R compliance: this screen renders only values the Endpoint already
 * carries; no host/port literals embedded here. The "search again"
 * button re-triggers the ViewModel's discovery, which uses the existing
 * `LocalNetworkDiscoveryService` mDNS NSD source of truth.
 *
 * §6.AB rendering: every visible Text rendered here is a candidate for
 * Challenge-test assertion. The "Searching for APIs…" / "Found N APIs"
 * / "No APIs discovered" status copy is the rendered-state contract.
 */
@Composable
fun ApiSelectionStep(
    discoveryRunning: Boolean,
    discovered: List<Endpoint>,
    selected: Endpoint?,
    connectivity: ApiConnectivityState,
    onSelect: (Endpoint) -> Unit,
    onRetryDiscovery: () -> Unit,
    onRetryProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = AppTheme.colors.background,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Choose your API",
                style = AppTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Lava talks to a Lava-API service running on your local network. Pick the one you want to use.",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            // Status line — tells the user what's happening
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (discoveryRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.primary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "Searching for APIs on your network…",
                        style = AppTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "api-discovery-searching" },
                    )
                } else if (discovered.isEmpty()) {
                    Text(
                        text = "No APIs discovered on your network.",
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = "api-discovery-empty" },
                    )
                } else {
                    Text(
                        text = "Found ${discovered.size} API${if (discovered.size == 1) "" else "s"}:",
                        style = AppTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "api-discovery-found" },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Discovered API list
            discovered.forEach { endpoint ->
                ApiRow(
                    endpoint = endpoint,
                    isSelected = endpoint == selected,
                    connectivity = if (endpoint == selected) connectivity else ApiConnectivityState.Idle,
                    onClick = { onSelect(endpoint) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Failure detail + retry — visible only when probe failed
            val failure = connectivity as? ApiConnectivityState.Failure
            if (failure != null) {
                Text(
                    text = "Could not reach this API: ${failure.reason}",
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.error,
                    modifier = Modifier.semantics { contentDescription = "api-probe-failure" },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    text = "Try again",
                    onClick = onRetryProbe,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            // Search again — always visible so the user can rescan
            Button(
                text = if (discoveryRunning) "Searching…" else "Search again",
                onClick = onRetryDiscovery,
                enabled = !discoveryRunning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ApiRow(
    endpoint: Endpoint,
    isSelected: Boolean,
    connectivity: ApiConnectivityState,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppTheme.shapes.medium,
        color = if (isSelected) {
            AppTheme.colors.primary.copy(alpha = 0.12f)
        } else {
            AppTheme.colors.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = endpoint.displayHostPort(),
                    style = AppTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = endpoint.displaySubtitle(),
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.size(24.dp)) {
                if (connectivity is ApiConnectivityState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.primary,
                    )
                }
            }
        }
    }
}

/**
 * Format an Endpoint as host:port for display. No literal embedded —
 * the values come from the Endpoint instance the discovery service
 * produced.
 */
internal fun Endpoint.displayHostPort(): String = when (this) {
    is Endpoint.GoApi -> "$host:$port"
    is Endpoint.Mirror -> host
    is Endpoint.Rutracker -> host
}

internal fun Endpoint.displaySubtitle(): String = when (this) {
    is Endpoint.GoApi -> "Lava API"
    is Endpoint.Mirror -> "Mirror"
    is Endpoint.Rutracker -> "Direct"
}
