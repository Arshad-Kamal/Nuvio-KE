package com.nuvio.tv.core.player.autosync

import kotlinx.coroutines.delay

/**
 * Spec 108 Q5: a run waits until [firstFrameRendered] before its index and subtitle downloads, so a
 * pick restored at stream open never competes with startup (Spec 12 S84 measured +0.77 s Exo /
 * +0.36 s mpv TTFF when it did). Polls a Main-thread flag; returns at once once it is set.
 */
internal suspend fun awaitAutoSyncFirstFrame(
    firstFrameRendered: () -> Boolean,
    pollMs: Long = AUTO_SYNC_FIRST_FRAME_POLL_MS,
) {
    while (!firstFrameRendered()) delay(pollMs)
}

internal const val AUTO_SYNC_FIRST_FRAME_POLL_MS = 100L

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,

    /**
     * A selection restored from an earlier episode: check it first and keep it if it fits;
     * only when it does not, search the other same-language subtitles like a startup run.
     */
    SELECTED_THEN_SEARCH,
}

/** Whether a failed check of the selected subtitle should be followed by a full search. */
internal fun AutoSyncCandidateScope.searchAfterSelectedFailed(
    outcome: AutoSyncAnalysisOutcome?,
): Boolean =
    this == AutoSyncCandidateScope.SELECTED_THEN_SEARCH &&
        // Nothing in the video to compare with: other subtitles cannot fit either.
        outcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
        outcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE

internal fun decideAutoSyncStart(enabled: Boolean): AutoSyncStartAction =
    if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldRestoreOriginalSubtitle(
    activeSidecarSubtitleKey: String?,
): Boolean = activeSidecarSubtitleKey == null

