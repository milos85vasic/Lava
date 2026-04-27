package lava.navigation.model

import androidx.annotation.StringRes
import lava.designsystem.drawables.Icon

data class NavigationBarItem(
    val route: String,
    @StringRes val labelResId: Int,
    val icon: Icon,
)
