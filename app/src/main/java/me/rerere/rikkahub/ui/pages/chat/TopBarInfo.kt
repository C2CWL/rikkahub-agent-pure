package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.usage.TurnFooter
import me.rerere.rikkahub.ui.pages.stats.formatCompactTokens
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Conversation token consumption shown in the chat top bars (regular and clean layout), summed
 * across all turns and tool calls. The usage ledger ([turnFooters]) is the ground truth (it
 * includes every tool-loop step); [TokenBudgetTracker] is the fallback for legacy or imported
 * conversations without ledger rows.
 */
internal fun conversationUsedTokens(turnFooters: Map<String, TurnFooter>, conversation: Conversation): Long =
    usedTokensOrFallback(
        ledgerTokens = turnFooters.values.mapNotNull { it.usage }.sumOf { it.inputTokens + it.outputTokens },
        fallback = { TokenBudgetTracker.aggregate(conversation).totalTokens },
    )

/** Ledger total when there is one, else the [fallback] estimate. */
internal fun usedTokensOrFallback(ledgerTokens: Long, fallback: () -> Long): Long =
    if (ledgerTokens > 0L) ledgerTokens else fallback()

/** "Assistant / Model / N token", the subtitle line of both chat top bars. */
internal fun topBarSubtitle(assistantName: String, modelName: String, usedTokens: Long): String =
    "$assistantName / $modelName / $usedTokens token"

/**
 * Token part of the clean-layout subtitle, " / N token", kept as its own text so a long model
 * name cannot ellipsize the count away. Chinese locales count in 万 / 亿 ([formatTokensChinese],
 * 萬 / 億 when [traditional]); others use the compact K / M form of the statistics page
 * ([formatCompactTokens]).
 */
internal fun cleanTopBarTokenText(usedTokens: Long, chinese: Boolean, traditional: Boolean = false): String {
    val count = if (chinese) formatTokensChinese(usedTokens, traditional) else formatCompactTokens(usedTokens)
    return " / $count token"
}

/** Whether [locale] reads Chinese, and whether in traditional characters (zh-TW, zh-HK, zh-Hant). */
internal fun isChineseLocale(locale: Locale): Boolean = locale.language == "zh"

internal fun isTraditionalChineseLocale(locale: Locale): Boolean =
    isChineseLocale(locale) && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO"))

/**
 * [count] the Chinese way: the plain number below 10000, then 万 (10^4) and 亿 (10^8) with up to
 * four decimals and no trailing zeros, e.g. 143793 -> "14.3793万", 100000 -> "10万".
 */
internal fun formatTokensChinese(count: Long, traditional: Boolean = false): String = when {
    count < 10_000L -> count.toString()
    count < 100_000_000L -> scaled(count, 10_000L) + if (traditional) "萬" else "万"
    else -> scaled(count, 100_000_000L) + if (traditional) "億" else "亿"
}

private fun scaled(count: Long, unit: Long): String =
    BigDecimal.valueOf(count)
        .divide(BigDecimal.valueOf(unit), 4, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
