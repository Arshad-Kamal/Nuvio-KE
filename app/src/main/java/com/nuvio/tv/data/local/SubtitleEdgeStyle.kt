package com.nuvio.tv.data.local

/** Edge drawn around subtitle text. Names are persisted (upstream's key and values). */
enum class SubtitleEdgeStyle { NONE, OUTLINE, DROP_SHADOW }

/**
 * A stored edge style wins; otherwise the pre-edge-style outline toggle maps 1:1 (on or absent →
 * OUTLINE, off → NONE). An unknown stored name falls back the same way.
 */
internal fun resolveSubtitleEdgeStyle(stored: String?, legacyOutlineEnabled: Boolean?): SubtitleEdgeStyle =
    SubtitleEdgeStyle.entries.firstOrNull { it.name == stored }
        ?: if (legacyOutlineEnabled == false) SubtitleEdgeStyle.NONE else SubtitleEdgeStyle.OUTLINE
