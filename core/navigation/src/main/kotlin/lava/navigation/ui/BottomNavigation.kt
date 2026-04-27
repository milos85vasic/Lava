package lava.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import lava.designsystem.component.NavigationBar
import lava.designsystem.component.NavigationBarItem
import lava.navigation.model.NavigationBarItem

@Composable
internal fun BottomNavigation(
    items: List<NavigationBarItem>,
    selected: String?,
    onClick: (String) -> Unit,
) = BottomNavigation(
    items = items,
    selected = { it == selected },
    onClick = onClick,
)

@Composable
private fun BottomNavigation(
    items: List<NavigationBarItem>,
    selected: (route: String) -> Boolean,
    onClick: (route: String) -> Unit,
) = NavigationBar {
    val haptic = LocalHapticFeedback.current
    items.forEach { tab ->
        NavigationBarItem(
            icon = tab.icon,
            label = stringResource(tab.labelResId),
            selected = selected(tab.route),
            onClick = {
                onClick(tab.route)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        )
    }
}
