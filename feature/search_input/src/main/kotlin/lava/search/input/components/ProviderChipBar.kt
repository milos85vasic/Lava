package lava.search.input.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.designsystem.color.ProviderColors

data class ProviderChip(
    val providerId: String,
    val displayName: String,
    val selected: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderChipBar(
    chips: List<ProviderChip>,
    onChipToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val color = ProviderColors.forProvider(chip.providerId)
            FilterChip(
                selected = chip.selected,
                onClick = { onChipToggled(chip.providerId) },
                label = {
                    Text(
                        text = chip.displayName,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.2f),
                    selectedLabelColor = color,
                ),
            )
        }
    }
}
