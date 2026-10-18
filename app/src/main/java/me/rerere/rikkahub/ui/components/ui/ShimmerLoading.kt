package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.StartOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/** One left→right pass, then a short rest before the next. */
internal const val SHIMMER_SWEEP_MS = 1400
internal const val SHIMMER_PAUSE_MS = 500

/** Highlight band width, as a fraction of the label width. */
internal const val SHIMMER_BAND_FRACTION = 0.45f

/**
 * Left edge of the highlight band for sweep [progress] (0..1) across a [width]-wide label with a
 * [band]-wide highlight: it starts fully off the left edge and ends fully off the right one, so
 * the loop never pops.
 */
internal fun shimmerBandStart(progress: Float, width: Float, band: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return -band + p * (width + band)
}

/**
 * "Thinking…" (or the live [text], e.g. a processing status) on a soft pill, with a light band
 * sweeping across the letters. Theme colours only: onSurfaceVariant letters, primary highlight,
 * surfaceContainerHigh backing so it stays legible over photo wallpapers.
 *
 * The sweep only re-draws; reading the animated value inside the draw lambda keeps it out of
 * composition and layout.
 */
@Composable
fun ShimmerThinkingLabel(
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = colors.primary
    val progress by shimmerProgress()
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text ?: stringResource(R.string.chat_loading_shimmer_thinking),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                // Offscreen so SrcAtop paints the band onto the glyphs only.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val band = size.width * SHIMMER_BAND_FRACTION
                    val start = shimmerBandStart(progress, size.width, band)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, highlight, Color.Transparent),
                            startX = start,
                            endX = start + band,
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
    }
}

/**
 * Compact form for square slots (e.g. the compress dialog): a short rounded bar, centred in the
 * given bounds, with the same sweep.
 */
@Composable
fun ShimmerBar(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val base = colors.primary.copy(alpha = 0.22f)
    val highlight = colors.primary
    val progress by shimmerProgress()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.32f)
                .clip(CircleShape)
                .background(base)
                .drawWithContent {
                    drawContent()
                    val band = size.width * SHIMMER_BAND_FRACTION * 1.4f
                    val start = shimmerBandStart(progress, size.width, band)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, highlight, Color.Transparent),
                            startX = start,
                            endX = start + band,
                        ),
                        topLeft = Offset.Zero,
                    )
                },
        )
    }
}

@Composable
private fun shimmerProgress() = rememberInfiniteTransition(label = "shimmer").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(
            durationMillis = SHIMMER_SWEEP_MS + SHIMMER_PAUSE_MS,
            easing = { t ->
                // Sweep during the first part of the cycle, hold off-screen for the rest.
                val sweep = SHIMMER_SWEEP_MS.toFloat() / (SHIMMER_SWEEP_MS + SHIMMER_PAUSE_MS)
                if (t >= sweep) 1f else shimmerEase(t / sweep)
            },
        ),
        repeatMode = RepeatMode.Restart,
        initialStartOffset = StartOffset(0),
    ),
    label = "shimmer-progress",
)

/** Gentle ease-in-out (smoothstep), so the band glides rather than ticks. */
internal fun shimmerEase(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
