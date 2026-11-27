package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val TAG = "SimklRelated"

/** TMDB's More like this count (`TmdbMetadataService.fetchMoreLikeThis`). */
internal const val SIMKL_RELATED_MAX_ITEMS = 12
private const val CACHE_TITLES = 64

/**
 * Where the detail page's More like this comes from (Spec 106 D3, E1/E6). Per profile, synced with the Nuvio account.
 * [storageId] is the persisted value: never rename it.
 */
enum class RelatedTitlesSource(val storageId: String) {
    TMDB("tmdb"),
    SIMKL("simkl");

    /** The source actually used: Simkl only while it is connected, else TMDB. The choice itself is kept (C3's rule). */
    fun effective(connectedProviderIds: Set<TrackingProviderId>): RelatedTitlesSource =
        if (this == SIMKL && TrackingProviderId.SIMKL !in connectedProviderIds) TMDB else this

    companion object {
        val DEFAULT: RelatedTitlesSource = TMDB

        /** Unknown or missing values read as [DEFAULT], so a value written by a newer build never breaks an older one. */
        fun fromStorage(value: String?): RelatedTitlesSource =
            entries.firstOrNull { it.storageId.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Simkl's related titles for a detail page (Spec 106 S4a, F4): the title's Simkl id from `/redirect` (its 301
 * `Location`, read and never followed), then the title's record (`/movies|tv|anime/{id}`): members' recommendations
 * first, then Simkl's similar titles. Both calls are anonymous catalog reads that cost no account allowance, so
 * [client] is its own [SimklClient] (on an engine that does not follow redirects): a running sync never delays it.
 * Any failure gives an empty list, never cached; cancellation is rethrown. No app id set up = empty, nothing sent.
 */
class SimklRelated(
    private val client: SimklClient,
    private val configuration: SimklApiConfiguration,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val cache = object : LinkedHashMap<String, List<MetaPreview>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MetaPreview>>?) = size > CACHE_TITLES
    }

    /**
     * Up to [maxItems] titles related to [itemId] (a Nuvio content id: `tt...`, `tmdb:N`, `kitsu:N`...) of [itemType],
     * named by Nuvio ids; Simkl's anime are named by [anime] (the profile's mode).
     */
    suspend fun related(
        itemId: String,
        itemType: String,
        anime: SimklAnimeSeasons,
        maxItems: Int = SIMKL_RELATED_MAX_ITEMS
    ): List<MetaPreview> = withContext(dispatcher) {
        val target = SimklLibraryTarget.of(itemId, itemType)
        val query = simklRedirectQuery(target.ids, target.isMovie) ?: return@withContext emptyList()
        val key = query.entries.joinToString("&") { (name, value) -> "$name=$value" } + "|${anime.storageId}|$maxItems"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val items = try {
            fetch(itemId, query, anime, maxItems)
        } catch (failure: IOException) {
            // SimklException included: not set up, unreachable, or Simkl refused. The body is never logged (KT-JSON-04).
            Log.w(TAG, "Simkl related titles not loaded: ${failure.javaClass.simpleName}")
            return@withContext emptyList()
        } ?: return@withContext emptyList()
        synchronized(cache) { cache[key] = items }
        items
    }

    /**
     * The title's page on Simkl as the list picker's link back shows it (`simkl.com/tv/1088153/sintel`, F1/F2; Simkl's
     * terms ask for one per item), from the same anonymous `/redirect`. Null when Simkl does not know the title or
     * cannot be reached; cancellation is rethrown.
     */
    suspend fun pageAddress(itemId: String, itemType: String): String? = withContext(dispatcher) {
        val target = SimklLibraryTarget.of(itemId, itemType)
        val query = simklRedirectQuery(target.ids, target.isMovie) ?: return@withContext null
        try {
            val redirect = client.execute(SimklRequest(SimklHttpMethod.GET, "/redirect", query, redirectIsAnswer = true), accessToken = null)
            simklPageAddress(configuration.baseUrl, redirect.headers["location"])
        } catch (failure: IOException) {
            Log.w(TAG, "Simkl page address not loaded: ${failure.javaClass.simpleName}")
            null
        }
    }

    /** Null when Simkl does not know the title (no catalog page in the redirect). */
    private suspend fun fetch(
        itemId: String,
        query: Map<String, String>,
        anime: SimklAnimeSeasons,
        maxItems: Int
    ): List<MetaPreview>? {
        val redirect = client.execute(
            SimklRequest(SimklHttpMethod.GET, "/redirect", query, redirectIsAnswer = true),
            accessToken = null
        )
        val (path, simklId) = simklCatalogPath(configuration.baseUrl, redirect.headers["location"]) ?: return null
        val detail = client.execute(SimklRequest(SimklHttpMethod.GET, path), accessToken = null)
        return simklRelatedTitles(detail.body, simklId, itemId, anime, maxItems)
    }
}

/**
 * The `/redirect` query for a title: the first of IMDb, TMDB, TVDB, Kitsu, MAL, AniList, AniDB, Simkl. `type` goes only
 * with TMDB (required) and TVDB (their film and series ids overlap); anime ids carry none (an anime may be a film).
 */
internal fun simklRedirectQuery(ids: TrackingExternalIds, isMovie: Boolean): Map<String, String>? {
    val type = if (isMovie) "movie" else "tv"
    val idParams = when {
        ids.imdb != null -> mapOf("imdb" to ids.imdb)
        ids.tmdb != null -> mapOf("tmdb" to ids.tmdb.toString(), "type" to type)
        ids.tvdb != null -> mapOf("tvdb" to ids.tvdb, "type" to type)
        ids.kitsu != null -> mapOf("kitsu" to ids.kitsu.toString())
        ids.mal != null -> mapOf("mal" to ids.mal.toString())
        ids.anilist != null -> mapOf("anilist" to ids.anilist.toString())
        ids.anidb != null -> mapOf("anidb" to ids.anidb.toString())
        ids.simkl != null -> mapOf("simkl" to ids.simkl.toString())
        else -> return null
    }
    return mapOf("to" to "simkl") + idParams
}

/**
 * The record path (`/movies/472214`) and Simkl id named by a redirect's `Location`
 * (`https://simkl.com/movies/472214/inception`), or null when it names no catalog page (a search page, a missing header).
 */
internal fun simklCatalogPath(baseUrl: String, location: String?): Pair<String, Long>? =
    catalogSegments(baseUrl, location)?.let { segments -> "/${segments[0]}/${segments[1]}" to segments[1].toLong() }

/**
 * `simkl.com/<kind>/<id>[/<slug>]` from a redirect's `Location`, always on simkl.com (whatever host the header names), or
 * null when it names no catalog page.
 */
internal fun simklPageAddress(baseUrl: String, location: String?): String? =
    catalogSegments(baseUrl, location)?.let { segments -> (listOf("simkl.com") + segments.take(3)).joinToString("/") }

/** The `Location` path from its catalog kind on (kind, positive id, then any slug), or null when it names no catalog page. */
private fun catalogSegments(baseUrl: String, location: String?): List<String>? {
    val segments = location?.let { baseUrl.toHttpUrl().resolve(it) }?.pathSegments?.filter(String::isNotEmpty) ?: return null
    val at = segments.indexOfFirst { it in CATALOG_KINDS }
    if (at < 0 || segments.getOrNull(at + 1)?.toLongOrNull()?.takeIf { it > 0 } == null) return null
    return segments.drop(at)
}

private val CATALOG_KINDS = setOf("movies", "tv", "anime")

/**
 * A title record's `users_recommendations` then `similar`, as More like this cards: deduped (by Simkl id, and by
 * content id within films or series: `tmdb:N` names a film and a show), the title itself ([selfSimklId], [selfItemId])
 * left out, at most [maxItems]. An item Nuvio cannot name or title is skipped, as is one that does not decode (the rest
 * still show), before it can hide a usable duplicate. A record that is not an object throws [IOException].
 */
internal fun simklRelatedTitles(
    body: String,
    selfSimklId: Long,
    selfItemId: String,
    anime: SimklAnimeSeasons,
    maxItems: Int
): List<MetaPreview> {
    val record = try {
        simklJson.decodeFromString(SimklRelatedRecord.serializer(), body)
    } catch (failure: SerializationException) {
        throw SimklException("Simkl record unreadable", failure)
    } catch (failure: IllegalArgumentException) {
        throw SimklException("Simkl record unreadable", failure)
    }
    val seenSimkl = mutableSetOf(selfSimklId)
    val seenIds = mutableSetOf<String>()
    return (record.recommended.orEmpty() + record.similar.orEmpty())
        .asSequence()
        .mapNotNull(::decodeItem)
        .mapNotNull { item -> item.toPreview(anime)?.let { preview -> item.media.simklId to preview } }
        .filter { (simklId, preview) ->
            preview.id != selfItemId && (simklId == null || simklId !in seenSimkl) && seenIds.add("${preview.type}|${preview.id}")
        }
        .onEach { (simklId, _) -> simklId?.let(seenSimkl::add) }
        .map { (_, preview) -> preview }
        .take(maxItems)
        .toList()
}

private fun decodeItem(element: JsonElement): SimklRelatedItem? = try {
    simklJson.decodeFromJsonElement(SimklRelatedItem.serializer(), element)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun SimklRelatedItem.toPreview(anime: SimklAnimeSeasons): MetaPreview? {
    val kind = type?.trim()?.lowercase()
    val isAnime = kind == "anime"
    val id = media.contentId(anime.takeIf { isAnime }) ?: return null
    val name = enTitle?.trim()?.takeIf(String::isNotEmpty) ?: title?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val isMovie = kind == "movie" || kind == "movies" || (isAnime && animeType?.trim().equals("movie", ignoreCase = true))
    val landscape = simklImageUrl("fanart", fanart, "_w") ?: simklImageUrl("posters", poster, "_w")
    val portrait = simklPosterUrl(poster)
    return MetaPreview(
        id = id,
        type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
        name = name,
        poster = landscape,
        posterShape = PosterShape.LANDSCAPE,
        background = landscape,
        logo = null,
        description = null,
        releaseInfo = year?.toString(),
        imdbRating = null,
        genres = emptyList(),
        imdbId = media.id("imdb")?.takeIf { it.startsWith("tt") },
        landscapePoster = landscape,
        rawPosterUrl = portrait
    )
}

/** The part of a `/movies|tv|anime/{id}` record More like this reads; items stay raw so one bad item drops alone. */
@Serializable
internal data class SimklRelatedRecord(
    @SerialName("users_recommendations") val recommended: List<JsonElement>? = null,
    @SerialName("similar") val similar: List<JsonElement>? = null
)

@Serializable
internal data class SimklRelatedItem(
    val title: String? = null,
    @SerialName("en_title") val enTitle: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val fanart: String? = null,
    /** `movie`, `tv` (`show` on some records) or `anime`. */
    val type: String? = null,
    /** Anime only: `tv`, `movie`, `ova`, `ona`, `special`, `music video`. */
    @SerialName("anime_type") val animeType: String? = null,
    val ids: Map<String, JsonElement>? = null
) {
    val media: SimklMedia get() = SimklMedia(title = title, year = year, ids = ids.orEmpty(), poster = poster)
}
