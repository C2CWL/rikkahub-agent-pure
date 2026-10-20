package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * The conversation drawer is a *card* that is slid out from under the chat, not a shelf bolted to
 * the left edge: it takes most of the window, but never all of it.
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
 * How much the drawer dims its own face.
 *
 * A lighter/darker *surface step* alone cannot carry the "drawer is underneath" cue: in a dark
 * theme every `surfaceContainer*` is *lighter* than `background`, so stepping by role would make
 * the drawer brighter than the page exactly where the stack has to read the other way round. An
 * explicit dim works in both themes.
 */
internal fun drawerDimAlpha(darkTheme: Boolean): Float = if (darkTheme) 0.16f else 0.06f

/**
 * Strength of the shadow the chat page casts on the drawer along the seam. One value, used twice:
 * by the drawer for the band just left of its edge, and here for the sliver that shows through the
 * page's rounded corner. If the two disagree the shadow stops dead at the corner.
 */
internal fun drawerSeamShadowAlpha(darkTheme: Boolean): Float = if (darkTheme) 0.34f else 0.18f

/**
 * The face colour of the conversation drawer — already dimmed.
 *
 * Two surfaces share it, and they have to stay in step: the drawer panel itself, and the sliver
 * that shows through the rounded corners cut out of the pushed-away chat page. If they drift apart
 * the corner reads as a separate backdrop and the page stops looking like it is lying *on* the
 * drawer.
 *
 * The chat page keeps `background` — the brightest surface in the stack — so the page is the raised
 * card and the drawer is the lid's base.
 */
@Composable
fun conversationDrawerSurface(darkTheme: Boolean = LocalDarkMode.current): Color {
    val scheme = MaterialTheme.colorScheme
    return lerp(scheme.surfaceContainer, scheme.scrim, drawerDimAlpha(darkTheme))
}

/**
 * Push-style drawer transition — the "flip phone" stack: the drawer is the lid's base, the chat
 * page is the lid that swings aside.
 *
 * M3's [androidx.compose.material3.ModalNavigationDrawer] slides the sheet over a page that stays
 * put, so the chat is either a stationary backdrop or — under the sheet's scrim — invisible. This
 * wrapper instead moves the *page* by the same distance the sheet travels, so opening the drawer
 * reads as one gesture with two halves: the sheet slides out from the left, the chat is pushed to
 * the right and its leading corners round off into a card lying on top of it.
 *
 * How the stacking is sold (all four cues have to agree, or the drawer reads as the top card):
 *  - **The page moves, the drawer does not.** The drawer's own sheet is square and does not animate
 *    beyond sliding in; all rounding belongs to the page.
 *  - **The page is brighter and casts the shadow.** The drawer's face is dimmed (see
 *    [conversationDrawerSurface]); the shadow band along the seam is drawn by the drawer
 *    (`ChatDrawerContent`), and the sliver that shows through the page's rounded corner gets the
 *    same band here so it does not stop dead at the rounding.
 *  - **The corners are filled with the drawer, not with a backdrop.** [seamColor] must be the same
 *    colour the drawer paints, so the cut-out corner continues the drawer instead of revealing an
 *    unrelated backdrop behind it.
 *  - **The scrim stays out of the way.** Called with `scrimColor = Color.Transparent` the chat keeps
 *    its full brightness while open; dimming it would flatten the stack back into one layer.
 *
 * How it is wired:
 *  - The drawer offset ([DrawerState.currentOffset]) is read in the draw/layer phase, never in
 *    composition, so a drag costs one layer update per frame instead of a recomposition of the
 *    whole chat page. It is the same value M3's own scrim animates with, so the page stays glued to
 *    the sheet through a fling, a drag or a programmatic `open()`.
 *  - The push is staged: the first 12% of the sheet's travel only moves the sheet, the page picks
 *    up afterwards. Starting both on the same frame makes the two halves look welded together.
 *  - The page is clipped to a rounded rectangle whose radius grows with the push, so the corner is
 *    invisible when closed (radius 0) and fully rounded when open.
 *
 * [content] must be the drawer's *content* slot — the page the sheet pushes away. Pass the same
 * [drawerWidth] the sheet itself uses, or the page will drift against the sheet's edge.
 */
@Composable
fun PushDrawerContent(
    drawerState: DrawerState,
    drawerWidth: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    seamColor: Color = conversationDrawerSurface(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    val seamShadowPx = with(density) { SEAM_SHADOW_WIDTH.toPx() }
    val seamShadow = MaterialTheme.colorScheme.scrim
        .copy(alpha = drawerSeamShadowAlpha(LocalDarkMode.current))

    Box(
        modifier = modifier
            .fillMaxSize()
            // Only ever visible through the corners cut out of the page card — the area the card
            // has vacated is covered by the drawer sheet itself.
            .background(seamColor)
            // ...and the corner has to carry the same shadow band the drawer draws on its own edge,
            // otherwise the shadow stops dead where the rounding starts. Painted behind the page,
            // so only the cut-out sliver is ever visible.
            .drawBehind {
                val pushed = drawerState.pushedFraction(drawerWidthPx)
                if (pushed <= 0f) return@drawBehind
                val seamX = drawerWidthPx * pushed
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(seamShadow, Color.Transparent),
                        startX = seamX,
                        endX = seamX + seamShadowPx,
                    ),
                    topLeft = Offset(seamX, 0f),
                    size = Size(seamShadowPx, size.height),
                )
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

/** Width of the shadow band the page casts on the drawer. */
internal val SEAM_SHADOW_WIDTH: Dp = 18.dp
