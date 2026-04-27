package lava.connection

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import lava.designsystem.component.Icon
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.domain.model.endpoint.EndpointStatus

@Composable
internal fun ConnectionStatusIcon(status: EndpointStatus) {
    when (status) {
        EndpointStatus.Active -> {
            Icon(
                modifier = Modifier
                    .padding(AppTheme.spaces.large)
                    .size(AppTheme.sizes.mediumSmall),
                icon = LavaIcons.Connected,
                tint = AppTheme.colors.accentGreen,
                contentDescription = stringResource(R.string.content_description_connection_active),
            )
        }

        EndpointStatus.Blocked -> {
            Icon(
                modifier = Modifier
                    .padding(AppTheme.spaces.large)
                    .size(AppTheme.sizes.mediumSmall),
                icon = LavaIcons.Blocked,
                tint = AppTheme.colors.accentOrange,
                contentDescription = stringResource(R.string.content_description_connection_blocked),
            )
        }

        EndpointStatus.NoInternet -> {
            Icon(
                modifier = Modifier
                    .padding(AppTheme.spaces.large)
                    .size(AppTheme.sizes.mediumSmall),
                icon = LavaIcons.NoInternet,
                tint = AppTheme.colors.accentRed,
                contentDescription = stringResource(R.string.content_description_no_internet),
            )
        }

        EndpointStatus.Updating -> {
            val transition = rememberInfiniteTransition("ConnectionStatusIcon_Transition")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = -360f,
                animationSpec = InfiniteRepeatableSpec(
                    animation = tween(
                        durationMillis = 1000,
                        easing = LinearEasing,
                    ),
                ),
                label = "ConnectionStatusIcon_Rotation",
            )
            Icon(
                modifier = Modifier
                    .padding(AppTheme.spaces.large)
                    .size(AppTheme.sizes.mediumSmall)
                    .graphicsLayer { rotationZ = rotation },
                icon = LavaIcons.Updating,
                tint = AppTheme.colors.accentBlue,
                contentDescription = stringResource(R.string.content_description_connection_updating),
            )
        }
    }
}
