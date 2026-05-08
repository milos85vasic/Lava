package lava.onboarding.steps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.designsystem.color.ProviderColors
import lava.designsystem.component.Button
import lava.designsystem.component.Icon
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.onboarding.ProviderConfigState
import lava.onboarding.ProviderOnboardingItem

@Composable
fun SummaryStep(
    providers: List<ProviderOnboardingItem>,
    configs: Map<String, ProviderConfigState>,
    onStartExploring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = AppTheme.colors.background,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = "All set!",
                style = AppTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your providers are ready.",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                providers.filter { it.selected }.forEach { item ->
                    val config = configs[item.descriptor.trackerId]
                    val isConfigured = config?.configured == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(12.dp)) {
                            drawCircle(ProviderColors.forProvider(item.descriptor.trackerId))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = item.descriptor.displayName,
                            style = AppTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            icon = if (isConfigured) LavaIcons.Selected else LavaIcons.Clear,
                            contentDescription = null,
                            tint = if (isConfigured) AppTheme.colors.accentGreen else AppTheme.colors.accentRed,
                        )
                    }
                }
            }
            Button(
                text = "Start Exploring",
                onClick = onStartExploring,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
