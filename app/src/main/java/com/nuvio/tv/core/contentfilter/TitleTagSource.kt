package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/** One fetch's outcome, as [TitleTagLookup] acts on it (Spec 117 P4). */
internal sealed interface Fetch<out T> {
    data class Ok<T>(val value: T) : Fetch<T>

    /** The title (or a match of the asked kind) is not on TMDB: words + genres only. */
    data object NotFound : Fetch<Nothing>

    /** 401: the key is refused. */
    data object Unauthorized : Fetch<Nothing>
    data class RateLimited(val retryAfterMs: Long) : Fetch<Nothing>

    /** Anything else; [io] marks a network/timeout failure, the kind the circuit breaker counts. */
    data class Failed(val io: Boolean) : Fetch<Nothing>
}

internal data class FetchedTags(
    val genreIds: List<Int>,
    val keywords: List<Pair<Int, String>>,
    val originalLanguage: String?,
    val adult: Boolean
)

/** Where tags come from. TMDB today; MDBList joins in S9 (D13). */
internal interface TitleTagSource {
    suspend fun tags(kind: TitleKind, tmdbId: Int, apiKey: String): Fetch<FetchedTags>

    /** The TMDB id of [imdbId] in [kind]'s namespace; NotFound when TMDB has none of that kind. */
    suspend fun find(imdbId: String, kind: TitleKind, apiKey: String): Fetch<Int>

    /** Whether TMDB accepts [apiKey] now (the 401 re-probe). */
    suspend fun probe(apiKey: String): Fetch<Unit>
}

internal class TmdbTitleTagSource(private val api: TmdbApi) : TitleTagSource {

    override suspend fun tags(kind: TitleKind, tmdbId: Int, apiKey: String): Fetch<FetchedTags> = call {
        when (kind) {
            TitleKind.MOVIE -> api.getMovieTags(tmdbId, apiKey)
            TitleKind.TV -> api.getTvTags(tmdbId, apiKey)
        }
    }.map { body ->
        FetchedTags(
            genreIds = body.genres.orEmpty().map { it.id },
            keywords = body.keywords?.all.orEmpty().mapNotNull { kw -> kw.name?.let { kw.id to it } },
            originalLanguage = body.originalLanguage,
            adult = body.adult == true
        )
    }

    override suspend fun find(imdbId: String, kind: TitleKind, apiKey: String): Fetch<Int> =
        call { api.findByExternalId(imdbId, apiKey, "imdb_id") }
            .map { body ->
                when (kind) {
                    TitleKind.MOVIE -> body.movieResults
                    TitleKind.TV -> body.tvResults
                }?.firstOrNull()?.id
            }
            .okOrNotFound()

    override suspend fun probe(apiKey: String): Fetch<Unit> =
        call(allowEmptyBody = true) { api.checkApiKey(apiKey) }

    private suspend fun <T : Any> call(
        allowEmptyBody: Boolean = false,
        request: suspend () -> Response<T>
    ): Fetch<T> = try {
        classify(request(), allowEmptyBody)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Fetch.Failed(io = true)
    } catch (e: Exception) {
        // A malformed body (serialization) is not the network, so the breaker does not count it.
        Fetch.Failed(io = false)
    }
}

/** Maps a TMDB response to a [Fetch] (401, 404, 429 + Retry-After, the rest Failed). */
internal fun <T : Any> classify(response: Response<T>, allowEmptyBody: Boolean): Fetch<T> {
    val code = response.code()
    return when {
        response.isSuccessful -> {
            val body = response.body()
            when {
                body != null -> Fetch.Ok(body)
                allowEmptyBody -> @Suppress("UNCHECKED_CAST") (Fetch.Ok(Unit) as Fetch<T>)
                else -> Fetch.Failed(io = false)
            }
        }
        code == 401 -> Fetch.Unauthorized
        code == 404 -> Fetch.NotFound
        code == 429 -> Fetch.RateLimited(retryAfterMs(response.headers()["Retry-After"]))
        else -> Fetch.Failed(io = false)
    }
}

internal const val DEFAULT_RETRY_AFTER_MS = 10_000L
private const val MAX_RETRY_AFTER_MS = 120_000L

/** Retry-After in seconds (TMDB sends seconds), clamped to 1..120 s; absent or unreadable = 10 s. */
internal fun retryAfterMs(header: String?): Long =
    header?.trim()?.toLongOrNull()?.let { (it * 1_000).coerceIn(1_000, MAX_RETRY_AFTER_MS) } ?: DEFAULT_RETRY_AFTER_MS

private fun <T : Any> Fetch<T?>.okOrNotFound(): Fetch<T> = when (this) {
    is Fetch.Ok -> value?.let { Fetch.Ok(it) } ?: Fetch.NotFound
    Fetch.NotFound -> Fetch.NotFound
    Fetch.Unauthorized -> Fetch.Unauthorized
    is Fetch.RateLimited -> this
    is Fetch.Failed -> this
}

private inline fun <T, R> Fetch<T>.map(transform: (T) -> R): Fetch<R> = when (this) {
    is Fetch.Ok -> Fetch.Ok(transform(value))
    Fetch.NotFound -> Fetch.NotFound
    Fetch.Unauthorized -> Fetch.Unauthorized
    is Fetch.RateLimited -> this
    is Fetch.Failed -> this
}
