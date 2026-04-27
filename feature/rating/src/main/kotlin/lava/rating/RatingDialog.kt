package lava.rating

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import lava.designsystem.component.Dialog
import lava.designsystem.component.Icon
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.component.ThemePreviews
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.drawables.Icon
import lava.designsystem.theme.AppTheme
import lava.designsystem.theme.LavaTheme
import lava.domain.model.rating.RatingRequest
import lava.navigation.viewModel
import lava.ui.platform.LocalOpenLinkHandler
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun RatingDialog() = RatingDialog(viewModel())

@Composable
private fun RatingDialog(viewModel: RatingViewModel) {
    val openLinkHandler = LocalOpenLinkHandler.current
    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is RatingSideEffect.OpenLink -> openLinkHandler.openLink(sideEffect.link)
        }
    }
    val state by viewModel.collectAsState()
    RatingDialog(state = state, onAction = viewModel::perform)
}

@Composable
private fun RatingDialog(
    state: RatingRequest,
    onAction: (RatingAction) -> Unit,
) = when (state) {
    is RatingRequest.Hide -> Unit
    is RatingRequest.Show -> Dialog(
        onDismissRequest = { onAction(RatingAction.DismissClick) },
    ) {
        Surface(shape = AppTheme.shapes.large) {
            Column(modifier = Modifier.padding(AppTheme.spaces.large)) {
                Box(
                    modifier = Modifier
                        .padding(AppTheme.spaces.large)
                        .fillMaxWidth()
                        .wrapContentWidth(),
                ) {
                    Text(
                        text = "Как вам приложение?",
                        style = AppTheme.typography.headlineSmall,
                    )
                }
                RatingRow { onAction(RatingAction.RatingClick) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.End),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (state.allowDisableForever) {
                        TextButton(
                            text = "Больше не спрашивать",
                            onClick = { onAction(RatingAction.NeverAskAgainClick) },
                        )
                    }
                    TextButton(
                        text = "Спросить позже",
                        onClick = { onAction(RatingAction.AskLaterClick) },
                    )
                }
            }
        }
    }
}

private const val StarsNumber = 5

@Composable
private fun RatingRow(onSubmit: () -> Unit) {
    var rating by remember { mutableFloatStateOf(0f) }
    fun getStateFor(value: Int): RatingStarState {
        return if (rating > value - 0.5) {
            RatingStarState.Full
        } else if (rating > value - 1) {
            RatingStarState.Half
        } else {
            RatingStarState.Empty
        }
    }

    var width by remember { mutableFloatStateOf(0f) }
    fun onPointerPositionChanged(position: Float) {
        rating = (StarsNumber * position.coerceIn(0f, width) / width)
            .coerceAtLeast(1f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .onGloballyPositioned { width = it.size.width.toFloat() }
            .padding(AppTheme.spaces.large)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onPointerPositionChanged(down.position.x)

                    var drag: PointerInputChange?
                    do {
                        drag = awaitDragOrCancellation(down.id)
                        if (drag != null) {
                            onPointerPositionChanged(drag.position.x)
                        }
                    } while (drag != null)

                    onSubmit()
                }
            },
    ) {
        repeat(StarsNumber) { i ->
            RatingStar(getStateFor(i + 1))
        }
    }
}

@Composable
private fun RatingStar(state: RatingStarState) {
    val scale by animateFloatAsState(
        targetValue = when (state) {
            RatingStarState.Empty -> 1f
            RatingStarState.Half -> 1.1f
            RatingStarState.Full -> 1.2f
        },
        label = "RatingStar_Scale",
    )
    Icon(
        modifier = Modifier
            .padding(AppTheme.spaces.mediumSmall)
            .size(AppTheme.sizes.medium)
            .scale(scale),
        icon = state.icon,
        tint = AppTheme.colors.accentOrange,
        contentDescription = null,
    )
}

private enum class RatingStarState(val icon: Icon) {
    Empty(LavaIcons.StarEmpty),
    Half(LavaIcons.StarHalf),
    Full(LavaIcons.StarFull),
}

@ThemePreviews
@Composable
private fun RatingDialog_Preview() {
    LavaTheme {
        RatingDialog(RatingRequest.Show(false)) {
            Log.d("RatingDialog", "Perform: $it")
        }
    }
}
