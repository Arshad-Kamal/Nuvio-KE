package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.NonUsRatingBoards
import com.nuvio.tv.domain.model.isRatingAllowed
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 64 K1b: drops the titles a capped profile may not see from an addon catalog row, on the
 * row's catalog rating (`app_extras.certification`, mapped by CatalogMapper), else its non-US
 * ratings (Spec 102, [isRatingAllowed]). Unknown ratings are hidden (D1). Only [CatalogRow.items] change: paging fields stay the addon's, so a filtered page
 * can be empty while the catalog goes on (CatalogRepositoryImpl handles that case).
 *
 * Search results get one rescue (K0: search series carry no catalog rating, and the addon's own
 * meta has one for about half of them): series without a US rating are looked up through
 * [MetaRepository.getMeta] on the row's addon, whose `ageRating` is the same addon field, before
 * the filter runs (Spec 102 R8): a meta with a US rating replaces the row's ratings; a meta without
 * one adds its non-US ratings to the row's; a lookup that does not complete leaves the title with no
 * non-US ratings, so the row's own never place it (hidden). At most [LOOKUP_CONCURRENCY] lookups wait at once across all rows, and one
 * row's batch stops at [LOOKUP_BUDGET_MS], time queued for a permit included; whatever is still
 * unresolved then is hidden. A lookup given up on may finish in MetaRepository's shared fetch
 * and land in its cache, so the next search gets it.
 */
@Singleton
class CatalogRatingFilter @Inject constructor(
    private val metaRepository: MetaRepository
) {
    private val lookupPermits = Semaphore(LOOKUP_CONCURRENCY)

    /** [row] as profile [cap] may see it; null [cap] returns [row] unchanged. */
    suspend fun filter(row: CatalogRow, cap: ContentRatingCap?, lookUpUnratedSeries: Boolean): CatalogRow {
        if (cap == null) return row
        val items = if (lookUpUnratedSeries) withLookedUpRatings(row) else row.items
        return row.copy(items = items.filter { isRatingAllowed(it.ageRating, cap, it.nonUsRatings) })
    }

    private suspend fun withLookedUpRatings(row: CatalogRow): List<MetaPreview> {
        val noUsRating = row.items.filter {
            it.type == ContentType.SERIES && ContentRatingCap.usTierOf(it.ageRating, it.nonUsRatings) == null
        }
        if (noUsRating.isEmpty()) return row.items
        val found = ConcurrentHashMap<String, Meta>()
        withTimeoutOrNull(LOOKUP_BUDGET_MS) {
            coroutineScope {
                noUsRating.forEach { item ->
                    launch {
                        lookupPermits.withPermit {
                            lookUpMeta(row.addonBaseUrl, item)?.let { found[item.id] = it }
                        }
                    }
                }
            }
        }
        if (found.size < noUsRating.size) {
            Log.d(TAG, "Search rating lookup catalogId=${row.catalogId}: ${found.size}/${noUsRating.size} resolved")
        }
        val lookedUpIds = noUsRating.mapTo(HashSet()) { it.id }
        return row.items.map { item ->
            if (item.id !in lookedUpIds) item
            else found[item.id]?.let { meta -> item.withLookedUp(meta) } ?: item.copy(nonUsRatings = emptyMap())
        }
    }

    /** R8: [meta]'s US rating replaces this row's; without one, both maps count together. */
    private fun MetaPreview.withLookedUp(meta: Meta): MetaPreview =
        if (ContentRatingCap.usTierOf(meta.ageRating, meta.nonUsRatings) != null) {
            copy(ageRating = meta.ageRating, nonUsRatings = meta.nonUsRatings)
        } else {
            copy(ageRating = meta.ageRating ?: ageRating, nonUsRatings = NonUsRatingBoards.union(nonUsRatings, meta.nonUsRatings))
        }

    /** The addon meta; null on a miss or any failure (the title then gets no non-US ratings: hidden). */
    private suspend fun lookUpMeta(addonBaseUrl: String, item: MetaPreview): Meta? = try {
        val result = metaRepository.getMeta(addonBaseUrl, item.apiType, item.id)
            .first { it !is NetworkResult.Loading }
        (result as? NetworkResult.Success)?.data
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Search rating lookup failed: ${e::class.simpleName}")
        null
    }

    private companion object {
        const val TAG = "CatalogRatingFilter"
        const val LOOKUP_CONCURRENCY = 6
        const val LOOKUP_BUDGET_MS = 6_000L
    }
}
