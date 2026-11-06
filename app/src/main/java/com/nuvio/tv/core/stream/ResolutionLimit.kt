package com.nuvio.tv.core.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream

/** How the resolution limit is set (Spec 58 F3; Settings › Playback › Buffer & network › This TV). */
enum class ResolutionLimitMode {
    /** This TV's largest display mode (D2, the default). */
    THIS_TV,
    UP_TO_1080P,
    UP_TO_720P,
    OFF;

    companion object {
        /** A stored name, else the default (an unknown or missing value). */
        fun fromStored(name: String?): ResolutionLimitMode = entries.firstOrNull { it.name == name } ?: THIS_TV
    }
}

/**
 * The resolution limit as it applies (device-wide, beside the stream list limit): [mode] as stored and
 * [tvMax], the largest resolution the TV reports (null when it reports none).
 */
data class ResolutionLimit(
    val mode: ResolutionLimitMode = ResolutionLimitMode.THIS_TV,
    val tvMax: ReleaseResolution? = null
) {
    /**
     * The highest resolution shown, or null when nothing is hidden: OFF, This TV with an unknown TV,
     * or a cap at the top of the ladder (nothing is above 2160p, so a 4K TV hides nothing and its
     * streams are never parsed).
     */
    val cap: ReleaseResolution?
        get() = when (mode) {
            ResolutionLimitMode.THIS_TV -> tvMax
            ResolutionLimitMode.UP_TO_1080P -> ReleaseResolution.P1080
            ResolutionLimitMode.UP_TO_720P -> ReleaseResolution.P720
            ResolutionLimitMode.OFF -> null
        }?.takeIf { it != ReleaseResolution.UNKNOWN && it != ReleaseResolution.P2160 }
}

/** What a stream is hidden for by the resolution limit: the [cap] it is over, and whether that is the TV's own. */
data class ResolutionVerdict(val cap: ReleaseResolution, val fromTv: Boolean)

/** How the copy names a resolution: "4K" for 2160p (the TV word), else the ladder's own "1080p". */
fun resolutionLabel(resolution: ReleaseResolution): String =
    if (resolution == ReleaseResolution.P2160) "4K" else resolution.label

/** Highest first; UNKNOWN is not on it. */
private val LADDER = ReleaseResolution.defaultOrder.filter { it != ReleaseResolution.UNKNOWN }

/** True when [resolution] is known and above [cap] (an unknown resolution always shows, D2). */
fun isOverResolutionCap(resolution: ReleaseResolution, cap: ReleaseResolution): Boolean {
    if (resolution == ReleaseResolution.UNKNOWN) return false
    return LADDER.indexOf(resolution) < LADDER.indexOf(cap)
}

/** The verdict for [stream] under [limit], or null when the limit hides nothing or the stream is not over it. */
fun resolutionLimitVerdict(stream: Stream, limit: ResolutionLimit): ResolutionVerdict? {
    val cap = limit.cap ?: return null
    if (!isOverResolutionCap(ReleaseFactsParser.resolutionOf(stream), cap)) return null
    return ResolutionVerdict(cap, fromTv = limit.mode == ResolutionLimitMode.THIS_TV)
}

/**
 * The ladder rung of a display mode [width] x [height], landscape whichever way it is reported: the
 * larger of its short side and the 16:9 height of its long side (a scope-width mode such as 3840x1600
 * is still 4K; 2560x1080 reads 1440p by width). Below 360 lines reads as 360p.
 */
fun resolutionOfMode(width: Int, height: Int): ReleaseResolution {
    val lines = maxOf(minOf(width, height), maxOf(width, height) * 9 / 16)
    return when {
        lines >= 2160 -> ReleaseResolution.P2160
        lines >= 1440 -> ReleaseResolution.P1440
        lines >= 1080 -> ReleaseResolution.P1080
        lines >= 720 -> ReleaseResolution.P720
        lines >= 576 -> ReleaseResolution.P576
        lines >= 480 -> ReleaseResolution.P480
        else -> ReleaseResolution.P360
    }
}

/**
 * The largest resolution the TV reports (Spec 58 D2): the largest of the default display's supported
 * modes, null when it reports none. A box that reports only its current output mode reads that mode;
 * the fixed choices cover it. Read where the limit inputs are built (a binder call, not per stream).
 */
fun tvMaxResolution(context: Context): ReleaseResolution? = runCatching {
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    display?.supportedModes
        ?.map { resolutionOfMode(it.physicalWidth, it.physicalHeight) }
        ?.minByOrNull { LADDER.indexOf(it) }
}.getOrNull()
