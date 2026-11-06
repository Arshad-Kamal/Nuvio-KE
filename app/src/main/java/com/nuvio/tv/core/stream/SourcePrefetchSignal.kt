package com.nuvio.tv.core.stream

/**
 * UI-facing signal for the details-page source line (fork feature).
 *
 * READY: the auto-play winner is chosen; [facts]/[badges] describe it, [winner] is the stream
 * itself and [lastPlayed] marks it as the source played last for this title (P10, set by the
 * details ViewModel). Addon
 * streams arrive resolved, so it is playable (a direct URL is also prewarmed).
 * SEARCHING is not emitted here: the ViewModel derives it from "key set,
 * no matching signal yet".
 */
enum class SourcePrefetchPhase { SEARCHING, READY, EMPTY }

data class SourcePrefetchSignal(
    val uiKey: String,
    val phase: SourcePrefetchPhase,
    val facts: ReleaseFacts?,
    val badges: List<com.nuvio.tv.domain.model.StreamBadge> = emptyList(),
    val winner: com.nuvio.tv.domain.model.Stream? = null,
    val lastPlayed: Boolean = false
)
