package com.nuvio.tv.core.player.autosync

import android.util.Log
import androidx.media3.common.C
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.tv.core.player.PlayerRuntimeController
import com.nuvio.tv.core.player.addonSubtitleKey
import com.nuvio.tv.core.player.canAttachAddonSubtitleViaSidecar
import com.nuvio.tv.core.player.commitPreparedSidecarSubtitle
import com.nuvio.tv.core.player.currentSidecarGenerationFor
import com.nuvio.tv.core.player.downloadSubtitleBody
import com.nuvio.tv.core.player.parseSidecarTimedCuesRobust
import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "NuvioAutoSyncSidecar"
private const val SIDECAR_WAIT_MS = 15_000L
private const val SIDECAR_WAIT_POLL_MS = 25L
private const val DIRECT_INDEX_TOLERANCE_MS = 50L

/**
 * Retimes the cues of the sidecar subtitle attached under [key] (KE: `addonSubtitleKey`, not the
 * URL as upstream) and commits them in place, only if that attachment is still the active one.
 */
internal suspend fun applyAutoSyncSidecarTimeline(
    sidecar: PlayerRuntimeController,
    key: String,
    timeline: AutoSyncTimelineRetimeResult,
): Boolean {
    if (!timeline.confident || sidecar.activeSidecarSubtitleKey != key) return false
    val expectedGeneration = sidecar.currentSidecarGenerationFor(key) ?: return false

    val current = sidecar.sidecarTimedCues.takeIf { it.isNotEmpty() } ?: withTimeoutOrNull(
        SIDECAR_WAIT_MS,
    ) {
        while (
            sidecar.activeSidecarSubtitleKey == key &&
            sidecar.currentSidecarGenerationFor(key) == expectedGeneration &&
            sidecar.sidecarTimedCues.isEmpty()
        ) {
            delay(SIDECAR_WAIT_POLL_MS)
        }
        sidecar.sidecarTimedCues.takeIf {
            sidecar.activeSidecarSubtitleKey == key &&
                sidecar.currentSidecarGenerationFor(key) == expectedGeneration &&
                it.isNotEmpty()
        }
    } ?: return false

    val retimed = withContext(AutomaticSubtitleSync.autoSyncDispatcher) {
        retimeSidecarTimedCues(current, timeline)
    }

    return sidecar.commitPreparedSidecarSubtitle(
        expectedCurrentKey = key,
        newKey = key,
        cues = retimed,
        expectedGeneration = expectedGeneration,
    )
}

/**
 * Swaps the sidecar attached under [expectedCurrentKey] for [replacement] retimed by [timeline]
 * (a better-timed same-language candidate), keeping the buffer. [rawBody] is AutoSync's
 * already-downloaded body; otherwise KE's [downloadSubtitleBody] fetches it (charset detection,
 * scoped stream credentials).
 */
internal suspend fun replaceAutoSyncSidecarSubtitle(
    sidecar: PlayerRuntimeController,
    expectedCurrentKey: String,
    replacement: Subtitle,
    rawBody: String? = null,
    timeline: AutoSyncTimelineRetimeResult,
): Boolean {
    if (!timeline.confident) return false
    if (!sidecar.canAttachAddonSubtitleViaSidecar(replacement)) return false
    if (sidecar.activeSidecarSubtitleKey != expectedCurrentKey) return false
    val expectedGeneration =
        sidecar.currentSidecarGenerationFor(expectedCurrentKey) ?: return false
    val url = replacement.url

    val retimed = try {
        val body = rawBody ?: withContext(Dispatchers.IO) {
            sidecar.downloadSubtitleBody(url = url, languageHint = replacement.lang, headers = replacement.headers)
        }

        val parsed = withContext(AutomaticSubtitleSync.autoSyncDispatcher) {
            parseSidecarTimedCuesRobust(body, url).cues
        }
        if (parsed.isEmpty()) {
            Log.w(TAG, "replacement parse empty host=${url.safeHost()}; keeping the current subtitle")
            return false
        }

        withContext(AutomaticSubtitleSync.autoSyncDispatcher) {
            retimeSidecarTimedCues(parsed, timeline)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.w(
            TAG,
            "replacement preparation failed host=${url.safeHost()}: ${error.javaClass.simpleName}; " +
                "keeping the current subtitle",
        )
        return false
    }

    if (
        sidecar.activeSidecarSubtitleKey != expectedCurrentKey ||
        sidecar.currentSidecarGenerationFor(expectedCurrentKey) != expectedGeneration
    ) {
        return false
    }

    return sidecar.commitPreparedSidecarSubtitle(
        expectedCurrentKey = expectedCurrentKey,
        newKey = addonSubtitleKey(replacement),
        cues = retimed,
        expectedGeneration = expectedGeneration,
    )
}

private fun retimeSidecarTimedCuesByIndexIfCompatible(
    source: List<CuesWithTiming>,
    timeline: AutoSyncTimelineRetimeResult,
): List<CuesWithTiming>? {
    if (source.size != timeline.cues.size || source.isEmpty()) return null

    val out = ArrayList<CuesWithTiming>(source.size)
    for (index in source.indices) {
        val entry = source[index]
        if (entry.startTimeUs == C.TIME_UNSET) return null

        val originalStartMs = entry.startTimeUs / 1_000L
        val originalEndMs = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs / 1_000L
            entry.durationUs != C.TIME_UNSET -> originalStartMs + entry.durationUs / 1_000L
            else -> return null
        }.coerceAtLeast(originalStartMs + 1L)

        val retimedCue = timeline.cues[index]
        if (
            kotlin.math.abs(originalStartMs - retimedCue.originalStartTimeMs) > DIRECT_INDEX_TOLERANCE_MS ||
            kotlin.math.abs(originalEndMs - retimedCue.originalEndTimeMs) > DIRECT_INDEX_TOLERANCE_MS
        ) {
            return null
        }

        val startMs = retimedCue.startTimeMs.coerceAtLeast(0L)
        val endMs = retimedCue.endTimeMs.coerceAtLeast(startMs + 1L)
        out += CuesWithTiming(
            entry.cues,
            startMs * 1_000L,
            (endMs - startMs) * 1_000L,
        )
    }

    clampIntroducedSidecarOverlaps(source, out)
    // KE: the sidecar renderer stops at the first cue after the playhead, so keep start order.
    out.sortBy { it.startTimeUs }
    return out
}

private fun clampIntroducedSidecarOverlaps(
    source: List<CuesWithTiming>,
    out: MutableList<CuesWithTiming>,
) {
    for (index in 0 until out.lastIndex) {
        val sourceCurrent = source[index]
        val sourceNext = source[index + 1]
        if (sourceCurrent.startTimeUs == C.TIME_UNSET || sourceNext.startTimeUs == C.TIME_UNSET) continue

        val sourceCurrentEndUs = when {
            sourceCurrent.endTimeUs != C.TIME_UNSET -> sourceCurrent.endTimeUs
            sourceCurrent.durationUs != C.TIME_UNSET -> sourceCurrent.startTimeUs + sourceCurrent.durationUs
            else -> continue
        }
        if (sourceCurrentEndUs > sourceNext.startTimeUs) continue

        val current = out[index]
        val next = out[index + 1]
        if (current.startTimeUs == C.TIME_UNSET || next.startTimeUs == C.TIME_UNSET) continue
        val currentEndUs = when {
            current.endTimeUs != C.TIME_UNSET -> current.endTimeUs
            current.durationUs != C.TIME_UNSET -> current.startTimeUs + current.durationUs
            else -> continue
        }
        if (currentEndUs > next.startTimeUs && next.startTimeUs > current.startTimeUs) {
            out[index] = CuesWithTiming(
                current.cues,
                current.startTimeUs,
                (next.startTimeUs - current.startTimeUs).coerceAtLeast(1L),
            )
        }
    }
}

private fun retimeSidecarTimedCues(
    source: List<CuesWithTiming>,
    timeline: AutoSyncTimelineRetimeResult,
): List<CuesWithTiming> {
    retimeSidecarTimedCuesByIndexIfCompatible(source, timeline)?.let { direct ->
        Log.d(TAG, "retime mapped sidecar=${source.size} mapping=direct-index")
        return direct
    }

    val mapper = AutoSyncTimelineTimeMapper(timeline)

    val out = ArrayList<CuesWithTiming>(source.size)
    source.forEach { entry ->
        if (entry.startTimeUs == C.TIME_UNSET) {
            out += entry
            return@forEach
        }

        val originalStartMs = entry.startTimeUs / 1_000L
        val originalEndMs = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs / 1_000L
            entry.durationUs != C.TIME_UNSET ->
                originalStartMs + entry.durationUs / 1_000L
            else -> originalStartMs + 1L
        }.coerceAtLeast(originalStartMs + 1L)

        val startMs = mapper.mapStartMs(originalStartMs)
        val endMs = mapper.mapEndMs(originalEndMs).coerceAtLeast(startMs + 1L)
        out += CuesWithTiming(
            entry.cues,
            startMs * 1_000L,
            (endMs - startMs) * 1_000L,
        )
    }

    clampIntroducedSidecarOverlaps(source, out)
    // KE: the sidecar renderer stops at the first cue after the playhead, so keep start order.
    out.sortBy { it.startTimeUs }

    Log.d(
        TAG,
        "retime mapped sidecar=${source.size} " +
            "boundaryMapped=${mapper.boundaryMapped} affineFallback=${mapper.affineFallback}",
    )
    return out
}
