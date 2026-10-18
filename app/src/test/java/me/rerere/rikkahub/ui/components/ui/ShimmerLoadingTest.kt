package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShimmerLoadingTest {

    @Test
    fun `band starts fully left and ends fully right`() {
        assertEquals(-45f, shimmerBandStart(0f, width = 100f, band = 45f), 0.001f)
        assertEquals(100f, shimmerBandStart(1f, width = 100f, band = 45f), 0.001f)
        assertEquals(27.5f, shimmerBandStart(0.5f, width = 100f, band = 45f), 0.001f)
    }

    @Test
    fun `band progress is clamped`() {
        assertEquals(-45f, shimmerBandStart(-1f, 100f, 45f), 0.001f)
        assertEquals(100f, shimmerBandStart(3f, 100f, 45f), 0.001f)
    }

    @Test
    fun `ease is a smooth 0 to 1`() {
        assertEquals(0f, shimmerEase(0f), 0f)
        assertEquals(0.5f, shimmerEase(0.5f), 0.0001f)
        assertEquals(1f, shimmerEase(1f), 0f)
        assertEquals(1f, shimmerEase(2f), 0f)
    }
}
