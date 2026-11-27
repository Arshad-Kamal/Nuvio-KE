package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.domain.contentfilter.TitleTags

/** TMDB's two title namespaces: movie 123 and tv 123 are different titles (Spec 117 P4). */
enum class TitleKind(val key: String) {
    MOVIE("movie"),
    TV("tv");

    companion object {
        /** A catalog item's type as `TmdbService.normalizeMediaType` reads it; null for any other type. */
        fun fromType(type: String): TitleKind? = when (type.lowercase()) {
            "movie", "film" -> MOVIE
            "series", "tv", "show", "tvshow" -> TV
            else -> null
        }
    }
}

/**
 * One catalog title as content filters look it up. Build it with [of], which normalises the id
 * (`tt123:1:2` -> `tt123`); the constructor is internal so no caller skips that.
 */
@ConsistentCopyVisibility
data class TitleRef internal constructor(val kind: TitleKind, val id: String) {
    internal val cacheKey: String get() = "${kind.key}:$id"

    /** How TMDB can be asked about this id. Bare numbers and other schemes (kitsu:, mal:) are not TMDB ids. */
    internal fun target(): TmdbTarget {
        if (id.startsWith(TMDB_PREFIX)) {
            val digits = id.removePrefix(TMDB_PREFIX).substringBefore(':')
            return digits.toIntOrNull()?.takeIf { it > 0 }?.let(TmdbTarget::ById) ?: TmdbTarget.Unresolvable
        }
        val base = id.substringBefore(':').substringBefore('/')
        if (base.length > 2 && base.startsWith("tt") && base.drop(2).all(Char::isDigit)) return TmdbTarget.ByImdb(base)
        return TmdbTarget.Unresolvable
    }

    companion object {
        private const val TMDB_PREFIX = "tmdb:"

        /** Null for an item type TMDB has no namespace for: that title is judged on words + genres only. */
        fun of(type: String, id: String): TitleRef? = TitleKind.fromType(type)?.let { TitleRef(it, normalizeId(id)) }

        /** One identity per title: `tt123:1:2` and `tt123` (or `tmdb:9:1`, `tmdb:9`) queue and cache as one. */
        internal fun normalizeId(id: String): String {
            if (id.startsWith(TMDB_PREFIX)) return TMDB_PREFIX + id.removePrefix(TMDB_PREFIX).substringBefore(':')
            val base = id.substringBefore(':').substringBefore('/')
            return if (base.length > 2 && base.startsWith("tt") && base.drop(2).all(Char::isDigit)) base else id
        }
    }
}

internal sealed interface TmdbTarget {
    data class ById(val tmdbId: Int) : TmdbTarget
    data class ByImdb(val imdbId: String) : TmdbTarget
    data object Unresolvable : TmdbTarget
}

/** What the tag source knows about a title right now. */
sealed interface TagState {
    data class Tagged(val tags: TitleTags) : TagState

    /** No TMDB tags will come (no key, not on TMDB, an id TMDB cannot resolve): judge on words + genres. */
    data object WordsOnly : TagState

    /** Tags may still come: the title is held hidden (D10). */
    data object Undecided : TagState
}

/** Queue levels, highest first (P4; R7-M1: a waiting gate always runs before draw work). */
enum class TagPriority { NOW, PAGE, DRAW, BACKGROUND }

enum class TagSourceStatus {
    /** The key store has not been read yet: titles stay undecided. */
    KEY_UNKNOWN,

    /** No key, or a 401 period: titles are decided on words + genres; nothing queued or held. */
    NO_KEY,

    /** Circuit breaker open: gates do not wait, undecided titles stay hidden. */
    PAUSED,
    READY
}
