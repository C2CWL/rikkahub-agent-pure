package me.rerere.rikkahub.ui.pages.chat

/**
 * A custom chat photo sits under theme-coloured UI. The old veil painted
 * `colorScheme.background` at alpha 0.2 → 0.5 across the whole picture.
 *
 * In the dark theme that veil is near-black, so a dark photo is unchanged and the
 * light text stays readable. The same alphas in the light theme are a white fog,
 * which is why one wallpaper looks vivid at night and washed out by day.
 *
 * The light theme therefore does not veil the photo. Contrast moves onto the text
 * itself: a plate behind bare assistant replies and under the title. The bar fade
 * stays a dissolve for the status icons, not a coat of paint.
 */

internal fun chatPhotoShown(background: String?, useGradientBackground: Boolean): Boolean =
    background != null && !useGradientBackground

/**
 * Resolve the effective wallpaper for the current theme. A per-theme wallpaper
 * (`darkBackground` / `lightBackground`) overrides the shared `background` when set;
 * null falls back to `background`. Both null → no wallpaper.
 */
internal fun effectiveBackground(
    darkTheme: Boolean,
    background: String?,
    darkBackground: String?,
    lightBackground: String?,
): String? = if (darkTheme) darkBackground ?: background else lightBackground ?: background

internal data class WallpaperScrim(val topAlpha: Float, val bottomAlpha: Float)

/** Dark theme keeps the historical veil. Light theme leaves the photo alone. */
internal fun wallpaperScrim(darkTheme: Boolean): WallpaperScrim =
    if (darkTheme) WallpaperScrim(topAlpha = 0.20f, bottomAlpha = 0.50f)
    else WallpaperScrim(topAlpha = 0f, bottomAlpha = 0f)

internal data class TitleBarFade(val topAlpha: Float, val midAlpha: Float, val bottomAlpha: Float)

internal fun titleBarFade(darkTheme: Boolean, photoShown: Boolean): TitleBarFade =
    if (!darkTheme && photoShown) {
        // Status-bar icons are dark in the light theme, so the very top stays mostly
        // covered. The title has its own plate, and the picture is clear by the bottom
        // edge of the bar.
        TitleBarFade(topAlpha = 0.78f, midAlpha = 0.36f, bottomAlpha = 0f)
    } else {
        TitleBarFade(topAlpha = 1f, midAlpha = 0.75f, bottomAlpha = 0f)
    }

internal data class BottomBarFade(val topAlpha: Float, val midAlpha: Float, val bottomAlpha: Float)

internal fun bottomBarFade(darkTheme: Boolean, photoShown: Boolean): BottomBarFade =
    if (!darkTheme && photoShown) {
        // Gentle dissolve over a custom photo so the light theme does not whitewash the wallpaper.
        BottomBarFade(topAlpha = 0f, midAlpha = 0.30f, bottomAlpha = 0.72f)
    } else {
        BottomBarFade(topAlpha = 0f, midAlpha = 0.65f, bottomAlpha = 0.95f)
    }

/** Plate behind assistant text that opted out of a bubble. Opaque enough to read. */
internal const val PHOTO_TEXT_PLATE_ALPHA = 0.92f

/** Plate under the title. Slightly clearer so the photo still shows through. */
internal const val PHOTO_TITLE_PLATE_ALPHA = 0.86f

/** Any loose onSurface text (title, status line) drawn straight on a light-theme photo. */
internal fun looseTextNeedsPhotoPlate(darkTheme: Boolean, photoShown: Boolean): Boolean =
    !darkTheme && photoShown

/** Bare assistant text. A real bubble already provides the plate. */
internal fun assistantTextNeedsPhotoPlate(
    darkTheme: Boolean,
    photoShown: Boolean,
    showAssistantBubble: Boolean,
): Boolean = looseTextNeedsPhotoPlate(darkTheme, photoShown) && !showAssistantBubble

/** Which theme colour the per-turn usage line under an assistant reply is drawn in. */
internal enum class UsageLineInk { SECONDARY, ON_SURFACE }

/**
 * Usage line styling. [plateAlpha] is the `surface` plate (or, when frosted, the `surface`
 * tint over the blurred wallpaper) behind the line, or null for none. [blurRadiusDp] is the
 * wallpaper blur under the plate, or null for a flat plate.
 */
internal data class UsageLineStyle(
    val ink: UsageLineInk,
    val inkAlpha: Float,
    val plateAlpha: Float?,
    val blurRadiusDp: Float? = null,
)

/** Haze source key of the chat wallpaper, so the usage line blurs only the photo, not messages. */
internal const val CHAT_WALLPAPER_HAZE_KEY = "chat_wallpaper"

/**
 * Off a photo the line stays the quiet `secondary@0.5`. On a photo it uses neutral onSurface
 * text (a tinted `secondary` clashes with sky / sea / flower photos), in both themes:
 *
 *  - [frosted] (API 31+ and the wallpaper is a blur source): a frosted pill. The photo under it
 *    is blurred, which removes the texture that made text hard to read. A lighter
 *    `surface@0.70` tint is then enough, with slightly stronger ink.
 *  - otherwise: the flat title plate, `surface@0.86`.
 */
internal fun usageLineStyle(photoShown: Boolean, frosted: Boolean = false): UsageLineStyle = when {
    !photoShown -> UsageLineStyle(UsageLineInk.SECONDARY, inkAlpha = 0.5f, plateAlpha = null)
    frosted -> UsageLineStyle(
        UsageLineInk.ON_SURFACE,
        inkAlpha = 0.85f,
        plateAlpha = USAGE_FROSTED_TINT_ALPHA,
        blurRadiusDp = USAGE_FROSTED_BLUR_DP,
    )
    else -> UsageLineStyle(UsageLineInk.ON_SURFACE, inkAlpha = 0.72f, plateAlpha = PHOTO_TITLE_PLATE_ALPHA)
}

internal const val USAGE_FROSTED_TINT_ALPHA = 0.70f
internal const val USAGE_FROSTED_BLUR_DP = 16f
