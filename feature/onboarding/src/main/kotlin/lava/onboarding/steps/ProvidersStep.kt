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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.designsystem.component.Button
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.color.ProviderColors
import lava.designsystem.theme.AppTheme
import lava.onboarding.ProviderOnboardingItem

@Composable
fun ProvidersStep(
    providers: List<ProviderOnboardingItem>,
    hasSelection: Boolean,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
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
                text = "Pick your providers",
                style = AppTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Select one or more content providers to configure.",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                providers.forEach { item ->
                    Surface(
                        onClick = { onToggle(item.descriptor.trackerId) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppTheme.shapes.medium,
                        color = AppTheme.colors.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Canvas(Modifier.size(12.dp)) {
                                drawCircle(ProviderColors.forProvider(item.descriptor.trackerId))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.descriptor.displayName,
                                    style = AppTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = item.descriptor.authType.name,
                                    style = AppTheme.typography.labelSmall,
                                    color = AppTheme.colors.onSurfaceVariant,
                                )
                            }
                            Checkbox(
                                checked = item.selected,
                                onCheckedChange = { onToggle(item.descriptor.trackerId) },
                                colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.primary),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Button(
                text = "Next",
                onClick = onNext,
                enabled = hasSelection,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
