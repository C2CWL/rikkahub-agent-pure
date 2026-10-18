package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CleanTopBarTokenFormatTest {

    @Test
    fun `chinese keeps the plain number below ten thousand`() {
        assertEquals("0", formatTokensChinese(0L))
        assertEquals("1234", formatTokensChinese(1234L))
        assertEquals("9999", formatTokensChinese(9999L))
    }

    @Test
    fun `chinese counts in wan with up to four decimals and no trailing zeros`() {
        assertEquals("1万", formatTokensChinese(10_000L))
        assertEquals("14.3793万", formatTokensChinese(143_793L))
        assertEquals("10万", formatTokensChinese(100_000L))
        assertEquals("1.05万", formatTokensChinese(10_500L))
        assertEquals("9999.9999万", formatTokensChinese(99_999_999L))
    }

    @Test
    fun `chinese counts in yi from one hundred million`() {
        assertEquals("1亿", formatTokensChinese(100_000_000L))
        assertEquals("1.2346亿", formatTokensChinese(123_456_789L))
        assertEquals("25亿", formatTokensChinese(2_500_000_000L))
    }

    @Test
    fun `traditional chinese uses wan and yi in traditional characters`() {
        assertEquals("14.3793萬", formatTokensChinese(143_793L, traditional = true))
        assertEquals("1億", formatTokensChinese(100_000_000L, traditional = true))
        assertTrue(isTraditionalChineseLocale(Locale.TRADITIONAL_CHINESE))
        assertTrue(isTraditionalChineseLocale(Locale.forLanguageTag("zh-HK")))
        assertFalse(isTraditionalChineseLocale(Locale.SIMPLIFIED_CHINESE))
        assertTrue(isChineseLocale(Locale.SIMPLIFIED_CHINESE))
        assertFalse(isChineseLocale(Locale.US))
    }

    @Test
    fun `token text keeps the unit word and uses compact form outside chinese`() {
        assertEquals(" / 14.3793万 token", cleanTopBarTokenText(143_793L, chinese = true))
        assertEquals(" / 29.2K token", cleanTopBarTokenText(29_200L, chinese = false))
        assertEquals(" / 512 token", cleanTopBarTokenText(512L, chinese = false))
        assertEquals(" / 512 token", cleanTopBarTokenText(512L, chinese = true))
    }
}
