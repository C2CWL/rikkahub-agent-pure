package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The conversation drawer is a *card* that slides in over the chat, not a shelf bolted to the
 * left edge: it takes most of the window, but never all of it.
 *
 * 78% of the window keeps a strip of the pushed-away page visible on every phone, and the 340dp
 * cap stops a tablet from getting a drawer wider than the reading column behind it.
 *
 * Both bounds are load-bearing. `ModalDrawerSheet` itself coerces the sheet to
 * [MIN_DRAWER_WIDTH]..[MAX_DRAWER_WIDTH]; a width outside that range would be silently changed by
 * M3 while [PushDrawerContent] kept pushing the page by the un-clamped value, opening a seam.
 */
fun conversationDrawerWidthFor(windowWidth: Dp): Dp =
    (windowWidth * DRAWER_WIDTH_FRACTION).coerceIn(MIN_DRAWER_WIDTH, MAX_DRAWER_WIDTH)

/** M3's own minimum sheet width (`DrawerSheet` clamps to it). */
internal val MIN_DRAWER_WIDTH: Dp = 240.dp

/** Comfortably under M3's 360dp cap, and wide enough for the identity card and the action row. */
internal val MAX_DRAWER_WIDTH: Dp = 340.dp

private const val DRAWER_WIDTH_FRACTION = 0.78f

/**
 * Push-style drawer transition ("the page gets out of the drawer's way").
 *
 * M3's [androidx.compose.material3.ModalNavigationDrawer] slides the sheet over a page that stays
 * put, so the chat is either a stationary backdrop or — under the sheet's scrim — invisible. This
 * wrapper instead moves the *page* by the same distance the sheet travels, so opening the drawer
 * reads as one gesture with two halves: the sheet comes in from the left, the chat is pushed to
 * the right and its leading corners round off into a stacked card.
 *
 * How it is wired:
 *  - The drawer offset ([DrawerState.currentOffset]) is read in the draw/layer phase, never in
 *    composition, so a drag costs one layer update per frame instead of a recomposition of the
 *    whole chat page. The value is the same one M3's own scrim animates with, which is why the
 *    page stays glued to the sheet through a fling, a drag or a programmatic `open()`.
 *  - The push is staged: the first 12% of the sheet's travel only moves the sheet, the page picks
 *    up afterwards. Starting both on the same frame makes the two halves look welded together.
 *  - The page is clipped to a rounded rectangle whose radius grows with the push, so the corner is
 *    invisible when closed (radius 0) and fully rounded when open.
 *  - A short shadow gradient is drawn along the seam the sheet casts on the page. It is clipped to
 *    the same rounded rectangle, otherwise it would paint over the cut-out corners.
 *
 * [content] must be the drawer's *content* slot — the page the sheet pushes away. Pass the same
 * [drawerWidth] the sheet itself uses, or the page will drift against the sheet's edge.
 */
@Composable
fun PushDrawerContent(
    drawerState: DrawerState,
    drawerWidth: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    val seamWidthPx = with(density) { 22.dp.toPx() }
    // What shows through the gap the page leaves behind — and through the cut-out corners of the
    // page card. Kept in the drawer's own surface family so the sheet and the page look stacked
    // rather than unrelated.
    val backdrop = MaterialTheme.colorScheme.surfaceContainerLow
    val seamShadow = MaterialTheme.colorScheme.scrim

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backdrop)
            .drawWithContent {
                drawContent()
                val pushed = drawerState.pushedFraction(drawerWidthPx)
                if (pushed <= 0f) return@drawWithContent
                val seamX = drawerWidthPx * pushed
                val radius = CornerRadius(cornerRadiusPx * pushed)
                val pageCard = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(
                                offset = Offset(seamX, 0f),
                                size = Size(size.width - seamX, size.height),
                            ),
                            topLeft = radius,
                            topRight = CornerRadius.Zero,
                            bottomRight = CornerRadius.Zero,
                            bottomLeft = radius,
                        )
                    )
                }
                clipPath(pageCard) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                seamShadow.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                            startX = seamX,
                            endX = seamX + seamWidthPx,
                        ),
                        topLeft = Offset(seamX, 0f),
                        size = Size(seamWidthPx, size.height),
                    )
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val pushed = drawerState.pushedFraction(drawerWidthPx)
                    translationX = drawerWidthPx * pushed
                    clip = pushed > 0f
                    shape = RoundedCornerShape((cornerRadiusPx * pushed).toDp())
                }
        ) {
            content()
        }
    }
}

/**
 * The staged 0..1 travel of the page. 0 while the sheet is still peeling off the edge, 1 when the
 * sheet is fully open and the page sits exactly one drawer-width to the right.
 */
private fun DrawerState.pushedFraction(drawerWidthPx: Float): Float {
    if (drawerWidthPx <= 0f) return 0f
    // `currentOffset` is Float.NaN until the sheet has been measured into its anchors — i.e. on the
    // first frame, where the drawer is closed by definition.
    val offset = currentOffset
    if (offset.isNaN()) return 0f
    val travel = (1f + offset / drawerWidthPx).coerceIn(0f, 1f)
    return stagedPush(travel)
}

/**
 * Maps the sheet's raw 0..1 travel onto the page's 0..1 push: nothing for the first
 * [PUSH_DELAY_FRACTION], then the remainder in one smooth run. Kept pure and internal so the
 * staging can be asserted in a JVM test — the drag itself cannot be.
 */
internal fun stagedPush(travel: Float): Float {
    val clamped = travel.coerceIn(0f, 1f)
    return ((clamped - PUSH_DELAY_FRACTION) / (1f - PUSH_DELAY_FRACTION)).coerceIn(0f, 1f)
}

/** Fraction of the sheet's travel that passes before the page starts moving. */
private const val PUSH_DELAY_FRACTION = 0.12f
