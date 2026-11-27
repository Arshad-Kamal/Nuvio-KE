package com.nuvio.tv.core.player.autosync

/** One addon subtitle AutoSync may compare or switch to. */
internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
    /** KE: the addon's own subtitle headers (Subtitle.headers); upstream sends none. */
    val headers: Map<String, String> = emptyMap(),
)
