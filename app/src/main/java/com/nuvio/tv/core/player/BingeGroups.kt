package com.nuvio.tv.core.player

/**
 * Spec 47 D2: whether a bingeGroup identifies a release, so remembering and matching it means
 * "the same source". AIOStreams v2.36.0 builds `<addonId>|<attr>|<attr>...` from the row's
 * resolution / quality / releaseGroup (generateBingeGroup, transformers/utils.ts) and drops the
 * ones it could not parse; a row with none (every CNCVerse row) gets the bare `<addonId>|`, which
 * every such row of that gateway shares. Matching on it pinned a whole series to whichever
 * unlabelled row played first (Dora S7: a French dub). A group without `|` is another addon's own
 * scheme and is taken as named, as before.
 */
object BingeGroups {
    fun namesRelease(group: String?): Boolean {
        val trimmed = group?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if ('|' !in trimmed) return true
        return trimmed.split('|').drop(1).any { it.isNotBlank() }
    }
}
