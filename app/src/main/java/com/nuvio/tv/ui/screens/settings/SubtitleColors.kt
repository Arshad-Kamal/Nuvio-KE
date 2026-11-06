package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import com.nuvio.tv.R
import java.util.Locale
import kotlin.math.roundToInt

/** A named colour the Subtitles colour panel offers; [argb] is fully opaque, or 0 for None. */
internal data class SubtitleSwatch(val argb: Int, @param:StringRes val name: Int)

/** The subtitle colours the Subtitles chip sets (Spec 31 PB5). */
internal enum class SubtitleColorTarget(val swatches: List<SubtitleSwatch>) {
    TEXT(
        listOf(
            SubtitleSwatch(0xFFFFFFFF.toInt(), R.string.sub_colour_white),
            SubtitleSwatch(0xFFD9D9D9.toInt(), R.string.sub_colour_light_grey),
            SubtitleSwatch(0xFFFFFF00.toInt(), R.string.sub_colour_yellow),
            SubtitleSwatch(0xFF00FFFF.toInt(), R.string.sub_colour_cyan),
            SubtitleSwatch(0xFF00FF00.toInt(), R.string.sub_colour_green),
            SubtitleSwatch(0xFFFF00FF.toInt(), R.string.sub_colour_magenta),
            SubtitleSwatch(0xFFFF6B6B.toInt(), R.string.sub_colour_coral),
            SubtitleSwatch(0xFFFFA500.toInt(), R.string.sub_colour_orange),
            SubtitleSwatch(0xFF90EE90.toInt(), R.string.sub_colour_light_green)
        )
    ),
    BACKGROUND(
        listOf(
            SubtitleSwatch(0, R.string.action_none),
            SubtitleSwatch(0xFF000000.toInt(), R.string.sub_colour_black),
            SubtitleSwatch(0xFF1A1A1A.toInt(), R.string.sub_colour_near_black),
            SubtitleSwatch(0xFF2D2D2D.toInt(), R.string.sub_colour_charcoal)
        )
    ),
    OUTLINE(
        listOf(
            SubtitleSwatch(0xFF000000.toInt(), R.string.sub_colour_black),
            SubtitleSwatch(0xFF1A1A1A.toInt(), R.string.sub_colour_near_black),
            SubtitleSwatch(0xFF333333.toInt(), R.string.sub_colour_dark_grey),
            SubtitleSwatch(0xFFFFFFFF.toInt(), R.string.sub_colour_white)
        )
    )
}

/** The lowest opacity the panel steps to: 0 would hide the colour (None is its own swatch). */
internal const val SUBTITLE_MIN_OPACITY = 10

private fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

private fun rgbOf(argb: Int): Int = argb and 0xFFFFFF

/** The swatch a stored colour shows as: alpha 0 is None (when offered), else the RGB match; null off-list. */
internal fun subtitleSwatchFor(argb: Int, swatches: List<SubtitleSwatch>): SubtitleSwatch? =
    if (alphaOf(argb) == 0) {
        swatches.firstOrNull { it.argb == 0 }
    } else {
        swatches.firstOrNull { it.argb != 0 && rgbOf(it.argb) == rgbOf(argb) }
    }

/** A stored colour's opacity in percent (0-100). */
internal fun subtitleOpacityPercent(argb: Int): Int = (alphaOf(argb) * 100f / 255f).roundToInt()

/** [argb] at [percent] opacity (0-100). */
internal fun subtitleColorWithOpacity(argb: Int, percent: Int): Int {
    val alpha = (percent.coerceIn(0, 100) * 255f / 100f).roundToInt()
    return (alpha shl 24) or rgbOf(argb)
}

/** Picking [swatch] keeps the current opacity; from a hidden (alpha 0) colour it starts opaque. None stays 0. */
internal fun subtitlePickSwatch(swatch: SubtitleSwatch, current: Int): Int {
    if (swatch.argb == 0) return 0
    val alpha = alphaOf(current).takeIf { it > 0 } ?: 0xFF
    return (alpha shl 24) or rgbOf(swatch.argb)
}

/** "#RRGGBB" for a colour that matches no swatch. */
internal fun subtitleColorHex(argb: Int): String = String.format(Locale.ROOT, "#%06X", rgbOf(argb))

/** Whether a row shows the opacity after the name: only a partly transparent colour. */
internal fun subtitleOpacityShown(argb: Int): Boolean = alphaOf(argb) in 1..254
