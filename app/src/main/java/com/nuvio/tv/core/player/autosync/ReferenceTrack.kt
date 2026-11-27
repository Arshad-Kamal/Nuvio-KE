package com.nuvio.tv.core.player.autosync

import com.nuvio.tv.core.player.SubtitleSyncCue

/**
 * One embedded subtitle track's timing, the reference an addon subtitle is aligned to: read from
 * the container index ([EmbeddedSubtitleTimelineLoader]) or observed during playback.
 * Upstream keeps it in AutomaticSubtitleSync.kt; KE gives it its own file because the index
 * loader is ported before the orchestrator.
 */
internal data class ReferenceTrack(
    val key: String,
    val language: String?,
    val cues: List<SubtitleSyncCue>,
    val label: String? = null,
    val selectionFlags: Int = 0,
    val roleFlags: Int = 0,
    val generation: Long = 0L,
    val estimatedEndStartsMs: Set<Long> = emptySet(),
    val sampled: Boolean = false,
)
