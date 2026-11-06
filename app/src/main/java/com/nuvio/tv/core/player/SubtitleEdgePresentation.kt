@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.core.player

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings

/** Drop-shadow colour, fixed (upstream 44e0fc456 value): 70% black. */
internal const val SUBTITLE_SHADOW_COLOR: Int = 0xB3000000.toInt()

/**
 * What the ExoPlayer SubtitleView draws. [ownSsaStyle] = the selected track is SSA/ASS and
 * libass is off: the file's own sizes and colours apply (embedded sizes on, white base text,
 * no box), while the user's edge, weight and bottom offset stay live. Media3's Cue carries no
 * edge, so the file's own outline cannot be drawn and the user's edge is kept (upstream drops it).
 * [fixedTextSizeSp] null = the file's sizes apply (embedded sizes on, Media3's default fraction
 * for cues without one); otherwise the user's size, embedded sizes off.
 */
internal data class ExoSubtitleLook(
    val foregroundColor: Int,
    val backgroundColor: Int,
    val edgeType: Int,
    val edgeColor: Int,
    val fixedTextSizeSp: Float?
)

private const val SUBTITLE_BASE_TEXT_SIZE_SP = 24f

internal fun exoSubtitleLook(style: SubtitleStyleSettings, ownSsaStyle: Boolean): ExoSubtitleLook =
    ExoSubtitleLook(
        foregroundColor = if (ownSsaStyle) Color.WHITE else style.textColor,
        backgroundColor = if (ownSsaStyle) Color.TRANSPARENT else style.backgroundColor,
        edgeType = when (style.edgeStyle) {
            SubtitleEdgeStyle.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleEdgeStyle.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdgeStyle.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        },
        edgeColor = if (style.edgeStyle == SubtitleEdgeStyle.DROP_SHADOW) SUBTITLE_SHADOW_COLOR else style.outlineColor,
        fixedTextSizeSp = if (ownSsaStyle) null else SUBTITLE_BASE_TEXT_SIZE_SP * (style.size / 100f)
    )

/**
 * Applies the user's subtitle look to an ExoPlayer [SubtitleView]: size, weight, colours, edge
 * and the bottom offset (a fraction of the view's height plus an extra padding once laid out).
 * [textScale] scales the user's fixed size (the Settings preview draws a smaller frame). One home
 * for the player and the Settings preview (Spec 31 PB6).
 */
internal fun SubtitleView.applySubtitleLook(
    style: SubtitleStyleSettings,
    ownSsaStyle: Boolean,
    textScale: Float = 1f
) {
    val look = exoSubtitleLook(style, ownSsaStyle)
    val fixedTextSizeSp = look.fixedTextSizeSp
    if (fixedTextSizeSp == null) {
        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    } else {
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, fixedTextSizeSp * textScale)
    }
    setApplyEmbeddedFontSizes(fixedTextSizeSp == null)
    setStyle(
        CaptionStyleCompat(
            look.foregroundColor,
            look.backgroundColor,
            Color.TRANSPARENT,
            look.edgeType,
            look.edgeColor,
            if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        )
    )
    setApplyEmbeddedStyles(true)
    setBottomPaddingFraction((0.06f + (style.verticalOffset / 250f)).coerceIn(0f, 0.4f))
    post {
        val extraPadding = (height * (style.verticalOffset / 400f)).toInt().coerceAtLeast(0)
        setPadding(paddingLeft, paddingTop, paddingRight, extraPadding)
    }
}

internal data class MpvSubtitleEdges(
    val outlineSize: Double,
    val shadowOffset: Double,
    val borderStyle: String,
    val backColor: Int
)

/**
 * mpv edge properties (upstream mapping). In outline-and-shadow mode sub-back-color is the
 * shadow colour; in background-box mode sub-shadow-offset is the box padding. The outline
 * width branch for ASS tracks is the pre-edge-style mapping, kept as is.
 */
internal fun mpvSubtitleEdges(style: SubtitleStyleSettings, assSelected: Boolean): MpvSubtitleEdges {
    if (style.edgeStyle == SubtitleEdgeStyle.DROP_SHADOW) {
        return MpvSubtitleEdges(
            outlineSize = 0.0,
            shadowOffset = 1.5,
            borderStyle = "outline-and-shadow",
            backColor = SUBTITLE_SHADOW_COLOR
        )
    }
    val background = (style.backgroundColor ushr 24) > 0
    val outlineSize = when {
        style.edgeStyle != SubtitleEdgeStyle.OUTLINE -> 0.0
        assSelected -> style.outlineWidth.coerceIn(1, 6).toDouble()
        else -> 1.0
    }
    return MpvSubtitleEdges(
        outlineSize = outlineSize,
        shadowOffset = if (background) 5.0 else 0.0,
        borderStyle = if (background) "background-box" else "outline-and-shadow",
        backColor = style.backgroundColor
    )
}
