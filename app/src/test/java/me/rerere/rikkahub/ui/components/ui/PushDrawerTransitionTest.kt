package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push transition is a draw-phase effect on top of `ModalNavigationDrawer`, so a JVM test can
 * only pin down the two pieces that are plain arithmetic: how wide the drawer is allowed to get,
 * and how the sheet's travel is staged onto the page's push. Those two are exactly the values that
 * decide whether the transition looks wrong (a drawer covering the whole window, or a page that
 * drifts off the seam) — so they get asserted here rather than eyeballed on a device.
 */
class PushDrawerTransitionTest {

    @Test
    fun `phone drawer leaves part of the page visible`() {
        val window = 411.dp

        val drawer = conversationDrawerWidthFor(window)

        // A drawer that takes the whole window would hide the very push this transition exists for.
        assertTrue("drawer must not cover the window", drawer < window)
        // ... and it still has to be wide enough to hold the identity card and the action row.
        assertTrue("drawer must stay usable", drawer >= window * 0.7f)
    }

    @Test
    fun `wide windows are capped`() {
        assertEquals(MAX_DRAWER_WIDTH, conversationDrawerWidthFor(1280.dp))
        assertEquals(MAX_DRAWER_WIDTH, conversationDrawerWidthFor(700.dp))
    }

    @Test
    fun `narrow windows shrink the drawer with the window`() {
        val narrow = conversationDrawerWidthFor(320.dp)
        val wide = conversationDrawerWidthFor(411.dp)

        assertTrue(narrow < wide)
        assertTrue(narrow < 320.dp)
    }

    @Test
    fun `width stays inside the range ModalDrawerSheet will honour`() {
        // Outside this range M3 silently resizes the sheet while the page keeps the raw value,
        // which is exactly how a seam appears at the end of the open animation.
        val windows = listOf(200.dp, 280.dp, 320.dp, 411.dp, 600.dp, 1280.dp)
        windows.forEach { window ->
            val drawer = conversationDrawerWidthFor(window)
            assertTrue("too narrow for $window", drawer >= MIN_DRAWER_WIDTH)
            assertTrue("too wide for $window", drawer <= MAX_DRAWER_WIDTH)
        }
    }

    @Test
    fun `page does not move until the sheet has peeled off the edge`() {
        assertEquals(0f, stagedPush(0f), 1e-6f)
        assertEquals(0f, stagedPush(0.05f), 1e-6f)
        assertEquals(0f, stagedPush(0.12f), 1e-6f)
    }

    @Test
    fun `fully open sheet pushes the page exactly one drawer width`() {
        assertEquals(1f, stagedPush(1f), 1e-6f)
        // The page must not move *further* than the sheet when a drag overshoots.
        assertEquals(1f, stagedPush(1.4f), 1e-6f)
    }

    @Test
    fun `staged push is monotonic`() {
        var previous = -1f
        var travel = 0f
        while (travel <= 1f) {
            val pushed = stagedPush(travel)
            assertTrue("push went backwards at $travel", pushed >= previous)
            previous = pushed
            travel += 0.01f
        }
    }
}
