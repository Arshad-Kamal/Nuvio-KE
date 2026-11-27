package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.contentfilter.OwnTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** One Find a tag search's outcome (Spec 117 P9, M1 stage 4c). */
sealed interface OwnTagSearchResult {
    /** TMDB's first page of matches, in its order (may be empty: no match). */
    data class Found(val tags: List<OwnTag>) : OwnTagSearchResult

    /** No key, a refused key, a network or server failure: the search says it failed. */
    data object Failed : OwnTagSearchResult
}

/**
 * Own-pack tags by name from TMDB's `search/keyword` (P9: hidden without a key, so the key state gates the
 * Find a tag chip, [keyState]). One request per search; nothing is cached (each search is a person typing).
 */
@Singleton
class OwnTagSearch internal constructor(
    private val keys: StateFlow<TmdbKeyState?>,
    private val search: suspend (key: String, query: String) -> List<OwnTag>?
) {
    @Inject
    constructor(api: TmdbApi, keyProvider: TmdbApiKeyProvider) : this(
        keyProvider.state,
        { key, query ->
            val response = api.searchKeywords(key, query)
            if (response.isSuccessful) {
                response.body()?.results.orEmpty().mapNotNull { kw -> kw.name?.let { OwnTag(kw.id, it) } }
            } else {
                null
            }
        }
    )

    /** The TMDB key state (null until read): Find a tag shows only while it has a key. */
    val keyState: StateFlow<TmdbKeyState?> get() = keys

    suspend fun search(query: String): OwnTagSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return OwnTagSearchResult.Found(emptyList())
        val state = keys.filterNotNull().first()
        if (!state.hasKey) return OwnTagSearchResult.Failed
        return try {
            search(state.effectiveKey, trimmed)?.let { OwnTagSearchResult.Found(it) } ?: OwnTagSearchResult.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OwnTagSearchResult.Failed
        }
    }
}
