package me.rerere.rikkahub.ui.components.message

/** 设置里“折叠时显示的步骤数”的上限，滑块范围是 0..这个值。 */
internal const val THINKING_STEPS_VISIBLE_MAX = 10

/**
 * 一组思考/工具步骤折叠时，尾部保留几步可见。
 *
 * [setting] 来自 DisplaySetting.thinkingStepsVisibleCount，默认 2，和原来的行为一致。
 * 0 表示整组只剩一行“已执行 N 步”的标题。正在生成的最后一组至少留一步，
 * 不然模型在跑工具时界面上看不到任何进展；生成结束后再按设置收起。
 */
internal fun thinkingStepsCollapsedVisibleCount(setting: Int, isStreamingTail: Boolean): Int {
    val count = setting.coerceIn(0, THINKING_STEPS_VISIBLE_MAX)
    return if (isStreamingTail) count.coerceAtLeast(1) else count
}
