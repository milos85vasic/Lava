package lava.onboarding.steps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import lava.designsystem.color.ProviderColors
import lava.designsystem.component.Button
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.theme.AppTheme
import lava.onboarding.ProviderOnboardingItem
import lava.onboarding.util.displayLabel

@Composable
fun ProvidersStep(
    providers: List<ProviderOnboardingItem>,
    hasSelection: Boolean,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    // Phase 5 (2026-06-11): non-blocking notice shown when the chosen API's
    // provider catalogue could not be fetched and the bundled list is being
    // shown instead (§6.AB — never a blank list). `null` = no notice.
    catalogNotice: String? = null,
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
            if (catalogNotice != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = catalogNotice,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.error,
                    modifier = Modifier.testTag(ProviderCatalogNoticeTestTag),
                )
            }
            // Select-all / deselect-all — shown when there are at least 2
            // providers (the chosen API's catalogue can be dozens; the operator
            // asked for a single control so the user need not tap each row).
            if (providers.size >= 2) {
                val allSelected = providers.all { it.selected }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SelectAllProvidersTestTag)
                        .clickable { onToggleAll() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { onToggleAll() },
                        colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.primary),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (allSelected) "Deselect all" else "Select all",
                        style = AppTheme.typography.bodyMedium,
                    )
                }
            }
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
                                    text = item.descriptor.authType.displayLabel(),
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

/**
 * Test tag for the Phase 5 dynamic-provider-discovery fallback notice. Used by
 * Compose UI Challenge tests to assert the notice renders (without relying on
 * fragile copy matching) when the API catalogue fetch fails and the bundled
 * provider list is shown instead.
 */
const val ProviderCatalogNoticeTestTag = "provider_catalog_notice"

/**
 * Test tag for the Select-all / Deselect-all control (shown when ≥2 providers).
 * Lets Compose UI Challenge tests tap the control and assert that every provider
 * row's checkbox flips.
 */
const val SelectAllProvidersTestTag = "select_all_providers"
