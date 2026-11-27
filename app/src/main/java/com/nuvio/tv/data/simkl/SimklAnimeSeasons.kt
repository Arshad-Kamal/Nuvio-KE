package com.nuvio.tv.data.simkl

/**
 * How Simkl's anime (its `anime` entries and playbacks, one per MyAnimeList-style season) are named in Nuvio (Spec 106
 * S5, E3; D4, D13, D14). Per profile, synced with the Nuvio account. [storageId] is the persisted value: never rename it.
 * Films and shows from Simkl, and every write (sent by the catalog's own ids), are the same in both.
 */
enum class SimklAnimeSeasons(val storageId: String) {
    /** A show's seasons share one id (IMDb, then TMDB, then TVDB), episodes numbered by Simkl's TVDB mapping. */
    PER_SHOW("show"),

    /** Each season its own title (Kitsu, then MyAnimeList), episodes numbered within it (the Kitsu addon's numbering). */
    PER_SEASON("season");

    companion object {
        val DEFAULT: SimklAnimeSeasons = PER_SHOW

        /** Unknown or missing values read as [DEFAULT], so a value written by a newer build never breaks an older one. */
        fun fromStorage(value: String?): SimklAnimeSeasons =
            entries.firstOrNull { it.storageId.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * The id Nuvio names this title by, or null when Simkl gave none Nuvio uses. [anime] is the profile's mode when the title
 * is one of Simkl's anime, else null: films and shows go IMDb, TMDB, Kitsu, MAL, Simkl (S2a's order).
 */
internal fun SimklMedia.contentId(anime: SimklAnimeSeasons? = null): String? {
    val imdb = id("imdb")?.takeIf { it.startsWith("tt") }
    val tmdb = id("tmdb")?.toLongOrNull()?.let { "tmdb:$it" }
    val kitsu = id("kitsu")?.toLongOrNull()?.let { "kitsu:$it" }
    val mal = id("mal")?.toLongOrNull()?.let { "mal:$it" }
    val simkl = simklId?.let { "simkl:$it" }
    return when (anime) {
        null -> imdb ?: tmdb ?: kitsu ?: mal ?: simkl
        SimklAnimeSeasons.PER_SHOW -> imdb ?: tmdb ?: id("tvdb")?.toLongOrNull()?.let { "tvdb:$it" } ?: kitsu ?: mal ?: simkl
        SimklAnimeSeasons.PER_SEASON -> kitsu ?: mal ?: imdb ?: tmdb ?: simkl
    }
}

/**
 * True when [anime] names this title by an id of its own entry (Kitsu, MAL, Simkl): its episodes are then numbered
 * within it. One per season falling back to a show-wide IMDb or TMDB id keeps the TVDB numbering, or two seasons'
 * episodes would share keys.
 */
internal fun SimklMedia.numbersWithinEntry(anime: SimklAnimeSeasons?): Boolean =
    anime == SimklAnimeSeasons.PER_SEASON && contentId(anime)?.substringBefore(':') in ENTRY_ID_PREFIXES

private val ENTRY_ID_PREFIXES = setOf("kitsu", "mal", "simkl")
