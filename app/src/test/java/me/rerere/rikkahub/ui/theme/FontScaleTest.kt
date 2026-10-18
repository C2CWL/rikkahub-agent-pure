package me.rerere.rikkahub.ui.theme

import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontScaleTest {

    private val eps = 1e-6f

    @Test
    fun `new settings default to off and unchanged scale`() {
        val d = DisplaySetting()
        assertFalse(d.applyChatFontGlobally)
        assertEquals(1.0f, d.uiFontScale, eps)
        assertEquals(1.0f, d.fontSizeRatio, eps)
    }

    @Test
    fun `snap rounds to whole percent and clamps`() {
        assertEquals(0.87f, FontScale.snap(0.8712f, FontScale.CHAT_RANGE), eps)
        assertEquals(1.23f, FontScale.snap(1.2349f, FontScale.UI_RANGE), eps)
        assertEquals(0.8f, FontScale.snap(0.1f, FontScale.UI_RANGE), eps)
        assertEquals(1.5f, FontScale.snap(9f, FontScale.UI_RANGE), eps)
        assertEquals(1.0f, FontScale.snap(Float.NaN, FontScale.UI_RANGE), eps)
    }

    @Test
    fun `percent label rounds old stepped values instead of truncating`() {
        assertEquals(88, FontScale.percentLabel(0.875f))
        assertEquals(100, FontScale.percentLabel(1.0f))
        assertEquals(63, FontScale.percentLabel(0.625f))
    }

    @Test
    fun `parse percent accepts integers with optional percent sign and clamps`() {
        assertEquals(0.87f, FontScale.parsePercent("87", FontScale.CHAT_RANGE)!!, eps)
        assertEquals(1.2f, FontScale.parsePercent(" 120 ", FontScale.UI_RANGE)!!, eps)
        assertEquals(0.95f, FontScale.parsePercent("95%", FontScale.UI_RANGE)!!, eps)
        assertEquals(2.0f, FontScale.parsePercent("500", FontScale.CHAT_RANGE)!!, eps)
        assertEquals(0.8f, FontScale.parsePercent("10", FontScale.UI_RANGE)!!, eps)
    }

    @Test
    fun `parse percent ignores invalid input`() {
        assertNull(FontScale.parsePercent("", FontScale.UI_RANGE))
        assertNull(FontScale.parsePercent("abc", FontScale.UI_RANGE))
        assertNull(FontScale.parsePercent("1.5", FontScale.UI_RANGE))
        assertNull(FontScale.parsePercent("%", FontScale.UI_RANGE))
    }

    @Test
    fun `ui scale multiplies the system font scale and clamps corrupt values`() {
        assertEquals(1.3f, FontScale.effectiveFontScale(1.3f, 1.0f), eps)
        assertEquals(1.56f, FontScale.effectiveFontScale(1.3f, 1.2f), eps)
        assertEquals(1.5f, FontScale.effectiveFontScale(1.0f, 7f), eps)
        assertEquals(1.0f, FontScale.effectiveFontScale(1.0f, Float.NaN), eps)
    }

    @Test
    fun `global font applies only when enabled and a font is chosen`() {
        assertFalse(FontScale.shouldApplyGlobalFont(false, ChatFontFamily.SERIF, ""))
        assertFalse(FontScale.shouldApplyGlobalFont(true, ChatFontFamily.DEFAULT, ""))
        assertFalse(FontScale.shouldApplyGlobalFont(true, ChatFontFamily.CUSTOM, ""))
        assertTrue(FontScale.shouldApplyGlobalFont(true, ChatFontFamily.CUSTOM, "fonts/chat_font.ttf"))
        assertTrue(FontScale.shouldApplyGlobalFont(true, ChatFontFamily.SERIF, ""))
        assertTrue(FontScale.shouldApplyGlobalFont(true, ChatFontFamily.MONOSPACE, ""))
    }
}
