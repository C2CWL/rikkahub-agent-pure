package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Clips to [shape] and paints the chat page's Haze sources (wallpaper and message list), blurred
 * and tinted with [tint] at [FrostedPlateSpec.tintAlpha]. The input is the same one the top-bar
 * glass buttons and the drawer panel already use. Put it on a Surface / Card whose own colour is
 * transparent.
 */
@Composable
internal fun Modifier.frostedPlate(
    hazeState: HazeState,
    shape: Shape,
    spec: FrostedPlateSpec,
    tint: Color = MaterialTheme.colorScheme.surface,
): Modifier {
    val backdrop = MaterialTheme.colorScheme.background
    val tintColor = tint.copy(alpha = spec.tintAlpha)
    val style = remember(backdrop, tintColor, spec.blurRadiusDp) {
        HazeBlurStyle {
            backgroundColor(backdrop)
            blurRadius(spec.blurRadiusDp.dp)
            colorEffects(listOf(HazeColorEffect.tint(tintColor)))
        }
    }
    return this
        .clip(shape)
        .hazeBlur(input = HazeInput.Sources(hazeState), style = style)
}
