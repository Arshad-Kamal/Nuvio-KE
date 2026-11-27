package com.nuvio.tv.core.tracking

/** The ids a tracker can match a title by. Ported from upstream NuvioTV (`core/tracking/TrackingMedia.kt`), Trakt id dropped (D2). */
data class TrackingExternalIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: String? = null,
    val simkl: Long? = null,
    val mal: Long? = null,
    val anidb: Long? = null,
    val anilist: Long? = null,
    val kitsu: Long? = null,
    val mdblist: String? = null
) {
    val hasAny: Boolean
        get() = listOf(imdb, tmdb, tvdb, simkl, mal, anidb, anilist, kitsu, mdblist).any { it != null }

    fun mergeMissing(other: TrackingExternalIds): TrackingExternalIds = TrackingExternalIds(
        imdb = imdb ?: other.imdb,
        tmdb = tmdb ?: other.tmdb,
        tvdb = tvdb ?: other.tvdb,
        simkl = simkl ?: other.simkl,
        mal = mal ?: other.mal,
        anidb = anidb ?: other.anidb,
        anilist = anilist ?: other.anilist,
        kitsu = kitsu ?: other.kitsu,
        mdblist = mdblist ?: other.mdblist
    )
}

/** Two id sets name the same title when any id they both carry is equal (a tracker's row against Nuvio's ids). */
fun TrackingExternalIds.sharesAnyId(other: TrackingExternalIds): Boolean =
    (imdb != null && imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (tvdb != null && tvdb == other.tvdb) ||
        (simkl != null && simkl == other.simkl) ||
        (mal != null && mal == other.mal) ||
        (anidb != null && anidb == other.anidb) ||
        (anilist != null && anilist == other.anilist) ||
        (kitsu != null && kitsu == other.kitsu) ||
        (mdblist != null && mdblist == other.mdblist)

enum class TrackingMediaKind {
    MOVIE,
    SHOW,
    ANIME
}

data class TrackingEpisode(
    val season: Int? = null,
    val number: Int,
    val title: String? = null,
    val tvdbId: Long? = null,
    val usesTvdbSeasonMapping: Boolean = false,
    val tmdbId: Long? = null
)

/** Where the title came from in Nuvio, so a tracker's answer can be mapped back to the catalog item. */
data class TrackingCatalogReference(
    val contentId: String,
    val contentType: String,
    val videoId: String? = null
)

data class TrackingMediaReference(
    val kind: TrackingMediaKind,
    val title: String? = null,
    val year: Int? = null,
    val ids: TrackingExternalIds = TrackingExternalIds(),
    val episode: TrackingEpisode? = null,
    val catalog: TrackingCatalogReference? = null,
    val posterUrl: String? = null
) {
    val hasResolvableIdentity: Boolean
        get() = ids.hasAny || !title.isNullOrBlank()

    /**
     * A reference to a whole series rather than something watched. Without episode coordinates Simkl and MDBList
     * mark (or unmark) every episode of the show, including ones never opened. Only films go without coordinates; an
     * anime one counts too (an anime film cannot be told from an anime series without more metadata). Never sent.
     */
    val isWholeSeries: Boolean
        get() = episode == null && kind != TrackingMediaKind.MOVIE

    /** One key per title (not per episode), best id first; a title-only reference falls back to title + year. */
    val stableKey: String
        get() = buildString {
            append(kind.name.lowercase())
            append(':')
            append(
                ids.simkl?.let { "simkl:$it" }
                    ?: ids.imdb?.takeIf(String::isNotBlank)?.let { "imdb:$it" }
                    ?: ids.tmdb?.let { "tmdb:$it" }
                    ?: ids.tvdb?.takeIf(String::isNotBlank)?.let { "tvdb:$it" }
                    ?: ids.mal?.let { "mal:$it" }
                    ?: ids.anidb?.let { "anidb:$it" }
                    ?: ids.anilist?.let { "anilist:$it" }
                    ?: ids.kitsu?.let { "kitsu:$it" }
                    ?: ids.mdblist?.let { "mdblist:$it" }
                    ?: "title:${title.orEmpty().trim().lowercase()}:${year ?: 0}"
            )
        }
}

/** Reads a Nuvio content or video id ("tt123", "tt123:1:2", "tmdb:603", "kitsu:1:5"); an unknown shape has no ids. */
fun parseTrackingExternalIds(rawValue: String?): TrackingExternalIds {
    if (rawValue.isNullOrBlank()) return TrackingExternalIds()
    val full = rawValue.trim()
    if (full.startsWith("tt", ignoreCase = true)) {
        return TrackingExternalIds(imdb = full.substringBefore(':'))
    }
    val prefix = full.substringBefore(':').lowercase()
    val value = full.substringAfter(':', "").substringBefore(':').trim()
    return when (prefix) {
        "imdb" -> TrackingExternalIds(imdb = value.takeIf(String::isNotBlank))
        "tmdb" -> TrackingExternalIds(tmdb = value.toLongOrNull())
        "tvdb" -> TrackingExternalIds(tvdb = value.takeIf(String::isNotBlank))
        "simkl" -> TrackingExternalIds(simkl = value.toLongOrNull())
        "mal" -> TrackingExternalIds(mal = value.toLongOrNull())
        "anidb" -> TrackingExternalIds(anidb = value.toLongOrNull())
        "anilist" -> TrackingExternalIds(anilist = value.toLongOrNull())
        "kitsu" -> TrackingExternalIds(kitsu = value.toLongOrNull())
        "mdblist" -> TrackingExternalIds(mdblist = value.takeIf(String::isNotBlank))
        else -> TrackingExternalIds()
    }
}

fun trackingMediaKind(contentType: String, ids: TrackingExternalIds = TrackingExternalIds()): TrackingMediaKind {
    val normalized = contentType.trim().lowercase()
    val hasAnimeIds = ids.mal != null || ids.anidb != null || ids.anilist != null || ids.kitsu != null
    return when {
        hasAnimeIds || normalized == "anime" -> TrackingMediaKind.ANIME
        normalized in setOf("movie", "film") -> TrackingMediaKind.MOVIE
        else -> TrackingMediaKind.SHOW
    }
}

fun extractTrackingYear(value: String?): Int? = value
    ?.let { TRACKING_YEAR_PATTERN.find(it)?.value }
    ?.toIntOrNull()

/**
 * True for an episode's own video id (`tt4940456:2:19`, `mal:42203:7`: id plus episode coordinates). A bare parent id (the
 * player's or the store's fallback to the content id) is not one.
 */
fun isEpisodeVideoId(videoId: String?): Boolean = (videoId?.split(':')?.size ?: 0) >= 3

fun buildTrackingMediaReference(
    contentType: String,
    parentMetaId: String,
    videoId: String? = null,
    title: String? = null,
    releaseInfo: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null
): TrackingMediaReference {
    val ids = parseTrackingExternalIds(parentMetaId).mergeMissing(parseTrackingExternalIds(videoId))
    return TrackingMediaReference(
        kind = trackingMediaKind(contentType, ids),
        title = title?.trim()?.takeIf(String::isNotBlank),
        year = extractTrackingYear(releaseInfo),
        ids = ids,
        episode = episodeNumber?.let { number ->
            TrackingEpisode(season = seasonNumber, number = number, title = episodeTitle)
        },
        catalog = TrackingCatalogReference(
            contentId = parentMetaId.trim(),
            contentType = contentType.trim(),
            videoId = videoId?.trim()?.takeIf(String::isNotBlank)
        )
    )
}

private val TRACKING_YEAR_PATTERN = Regex("(19|20)\\d{2}")
