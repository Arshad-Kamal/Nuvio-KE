package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.FilterSubject
import com.nuvio.tv.domain.model.MetaPreview

/** What a gate does with one title (Spec 122 V2). */
sealed interface TitleVerdict {
    data object Clean : TitleVerdict

    /** Every pack that hides it, in pack order: hidden, or kept and marked while previewing. */
    data class Hit(val packIds: List<String>) : TitleVerdict

    /** Its TMDB tags may still come: hidden either way (117/D10). */
    data object Undecided : TitleVerdict
}

/**
 * [item] as a gate shows it: unmarked when clean, marked with its packs when a hit is previewed, null
 * when hidden (a hit not previewed, or undecided either way).
 */
fun TitleVerdict.shown(item: MetaPreview, preview: Boolean): MetaPreview? = when (this) {
    TitleVerdict.Clean -> if (item.filterHits.isEmpty()) item else item.copy(filterHits = emptyList())
    is TitleVerdict.Hit -> if (preview) item.copy(filterHits = packIds) else null
    TitleVerdict.Undecided -> null
}

/** The lookup identity of a catalog title; null for a type TMDB has no namespace for (words + genres only). */
fun MetaPreview.titleRef(): TitleRef? = TitleRef.of(apiType, id)

/**
 * Judges [item] as it is (its own name, description, genres and, for a TMDB list row, its genre ids and
 * adult flag) plus its cached TMDB tags: a map read,
 * no waiting. A hit on words or genres decides at once, unless every hitting pack's allowlist (117/D30)
 * could still exempt the title once its IMDb id resolves to a TMDB id.
 */
fun TitleTagLookup.verdictOf(filter: ContentFilter, item: MetaPreview): TitleVerdict {
    val ref = item.titleRef()
    val state = ref?.let(::state) ?: TagState.WordsOnly
    val tmdbKey = ref?.let(::tmdbKey)
    val hits = filter.hits(
        FilterSubject(
            name = item.name,
            description = item.description,
            genreNames = item.genres,
            genreIds = item.tmdbGenreIds.toSet(),
            adult = item.tmdbAdult,
            tags = (state as? TagState.Tagged)?.tags,
            tmdbKey = tmdbKey
        )
    )
    val undecided = state == TagState.Undecided
    return when {
        hits.isEmpty() -> if (undecided) TitleVerdict.Undecided else TitleVerdict.Clean
        undecided && tmdbKey == null &&
            filter.packs.filter { it.id in hits }.all { it.allowTitles.isNotEmpty() } ->
            TitleVerdict.Undecided
        else -> TitleVerdict.Hit(hits)
    }
}

/**
 * Whether [item]'s tags could still change what a gate shows: its [verdict] is undecided or, while
 * previewing, a hit whose tags may add packs to its mark. Not previewing, a hit hides whatever its
 * tags add, so it never waits. [verdict], when the caller has it, saves judging again.
 */
fun TitleTagLookup.waitsForTags(
    filter: ContentFilter,
    item: MetaPreview,
    preview: Boolean,
    verdict: TitleVerdict? = null
): Boolean {
    val ref = item.titleRef() ?: return false
    if (state(ref) != TagState.Undecided) return false
    return preview || (verdict ?: verdictOf(filter, item)) !is TitleVerdict.Hit
}
