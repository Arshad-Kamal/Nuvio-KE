package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.NonUsRatingBoards
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.core.util.safeHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import com.nuvio.tv.core.util.encodeUrlArg
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Addon catalog rows behind three cache tiers, each with its own time rule:
 * 1. Memory LRU (64 entries, 90 s): a hit within the TTL serves and SKIPS the network
 *    (deduplicates reloads within a session).
 * 2. Disk first-paint (first page only: skip == 0, no extra args; 128 entries, 7-day ceiling):
 *    stale-while-revalidate. A hit is served at once and the network ALWAYS revalidates; an
 *    identical fresh row is not re-emitted (see Spec 117 below for the one exception), and a
 *    network error keeps the served disk row.
 * 3. OkHttp `http_cache_addon` (NetworkModule `ADDON_HTTP_CACHE_DIR`): standard Cache-Control from the addon.
 * [clearCaches] clears tiers 1 and 2; the Advanced "clear cache" action also evicts the OkHttp
 * caches and fires the Home refresh signal.
 *
 * Spec 64 K1b: tiers 1 and 2 hold the addon's full row; every emitted row passes
 * [CatalogRatingFilter] for the active profile's cap, read once per collection. A disk row
 * written before K1a has no ratings, so under a cap it emits empty until revalidation. Paging
 * fields stay the addon's; a load-more page the cap empties is followed by up to
 * [CatalogContentFilter.MAX_HIDDEN_PAGE_ADVANCE] more pages, and a row that is still empty after them is what
 * CatalogRow.mergeCatalogPage then reads as the end of the catalog.
 *
 * Spec 117 P5: with `packGate` on (every caller but Home, which filters when it draws, P5b) each
 * capped row then passes [CatalogContentFilter], one emission per row: a disk row is gated without
 * waiting, the rest wait for their titles' tags within one budget per call. A load-more page the
 * packs empty advances like a capped one. A fresh row identical to the disk row is still emitted
 * when the disk emission held titles whose tags had not come and the gated row now differs; when the
 * revalidation fails, the disk row itself is re-gated with the wait instead.
 */
@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val healthStore: AddonHealthStore,
    private val wireJson: Json,
    private val activeRatingCap: ActiveRatingCap,
    private val ratingFilter: CatalogRatingFilter,
    private val contentFilter: CatalogContentFilter
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"

        // Tier 1 (class KDoc), keyed on the fully resolved catalog URL.
        private const val CATALOG_CACHE_TTL_MS = 90_000L
        private const val CATALOG_CACHE_MAX_ENTRIES = 64

        // Tier 2 (class KDoc). The ceiling only caps how old a blob may be before it is not
        // served at all; freshness comes from the always-on revalidation.
        private const val DISK_CACHE_FILE_NAME = "catalog_rows_v1.json"
        private const val DISK_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        private const val DISK_CACHE_MAX_ENTRIES = 128

        private data class CacheEntry(val row: CatalogRow, val storedAtMs: Long)
        private data class DiskEntry(val dtoJson: String, val storedAtMs: Long)

        /** Which tier a full row came from: [getCatalog] gates a disk row without waiting. */
        private enum class RowSource { MEMORY, DISK, NETWORK }
        private data class SourcedRow(val row: CatalogRow, val source: RowSource)
    }

    // Access-ordered LinkedHashMap wrapped for LRU eviction; synchronized because catalog
    // fetches run concurrently across rows. Per instance (the repository is a @Singleton).
    private val catalogCache: MutableMap<String, CacheEntry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean =
                    size > CATALOG_CACHE_MAX_ENTRIES
            }
        )

    // wireJson doubles as the disk-cache codec: old Moshi-written blobs (all
    // fields, explicit nulls, arbitrary Any? values) decode identically; failures
    // degrade to a cache miss by design.

    private val diskFile: File by lazy { File(context.cacheDir, DISK_CACHE_FILE_NAME) }
    private val diskCache = ConcurrentHashMap<String, DiskEntry>()
    private val diskMutex = Mutex()
    @Volatile private var diskLoaded = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Warm the disk mirror off the critical path so it is ready before the first
        // getCatalog collection; the guard in ensureDiskLoaded() covers the race.
        ioScope.launch { runCatching { ensureDiskLoaded() } }
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        packGate: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)
        val cap = activeRatingCap.current()
        val gate = if (packGate) contentFilter.open() else null
        val isSearch = "search" in extraArgs
        fun fetchPage(pageSkip: Int) = fetchCatalog(
            addonBaseUrl, addonId, addonName, catalogId, catalogName, type, pageSkip, skipStep, extraArgs, supportsSkip
        )
        // The cap first (a title it removes never reaches the packs), then the packs.
        suspend fun visibleOf(page: CatalogRow, wait: Boolean): CatalogContentFilter.Gated {
            val capped = ratingFilter.filter(page, cap, lookUpUnratedSeries = isSearch)
            return gate?.apply(capped, wait) ?: CatalogContentFilter.Gated(capped, heldUndecided = false)
        }
        // The disk row served first (painted without waiting) and what its gate showed.
        var diskRow: CatalogRow? = null
        var diskShown: CatalogContentFilter.Gated? = null
        var networkRowCame = false
        fetchPage(skip).collect { result ->
            if (result !is NetworkResult.Success) {
                if (result is NetworkResult.Error) emit(result)
                return@collect
            }
            val source = result.data.source
            var page = result.data.row
            if (source == RowSource.NETWORK) networkRowCame = true
            // A fresh row identical to the disk row: dropped as before (no needless row swap), unless
            // the disk emission held titles still waiting for tags and this pass shows them differently.
            val heldDisk = diskShown.takeIf { source == RowSource.NETWORK && page == diskRow }
            if (heldDisk != null && !heldDisk.heldUndecided) return@collect
            var visible = visibleOf(page, wait = source != RowSource.DISK)
            if (heldDisk != null && visible.showsSameAs(heldDisk)) return@collect
            if (source == RowSource.DISK) {
                diskRow = page
                diskShown = visible
            }
            // A load-more page the cap or the packs empty gets no new focus target, so the UI never
            // asks for the next one: fetch on, bounded, until a page shows something or the
            // catalog ends (a raw-empty page has hasMore = false, so it ends the loop and
            // is emitted as the last page). Page 1 is not advanced (its row is simply hidden).
            // A previewed hit is kept, so it never empties a page.
            var advanced = 0
            while ((cap != null || gate != null) && skip > 0 && visible.row.items.isEmpty() && page.hasMore &&
                advanced < CatalogContentFilter.MAX_HIDDEN_PAGE_ADVANCE
            ) {
                val next = fetchPage(page.nextSkip).first { it !is NetworkResult.Loading }
                if (next is NetworkResult.Error) {
                    // Surface it as this load-more's failure (retryable), not as an empty last page.
                    emit(next)
                    return@collect
                }
                if (next !is NetworkResult.Success) break
                page = next.data.row
                visible = visibleOf(page, wait = next.data.source != RowSource.DISK)
                advanced++
            }
            emit(NetworkResult.Success(visible.row))
        }
        // The revalidation failed (fetchCatalog keeps the disk row on screen): titles the disk row
        // held still get this load's wait, so they appear without a reload.
        val held = diskShown
        val heldRow = diskRow
        if (!networkRowCame && held != null && heldRow != null && held.heldUndecided) {
            val visible = visibleOf(heldRow, wait = true)
            if (!visible.showsSameAs(held)) emit(NetworkResult.Success(visible.row))
        }
    }.flowOn(Dispatchers.IO)

    /** The addon's full rows through the cache tiers (class KDoc), each tagged with its tier; [getCatalog] filters them. */
    private fun fetchCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<SourcedRow>> = flow {
        // Runs on IO (flowOn below): the disk-row decode, row building and cache-blob
        // encode must not run on the collecting (Main) thread.
        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)

        // 1) In-memory freshness cache hit within TTL: serve and skip the network.
        val cached = catalogCache[url]
        if (cached != null) {
            if (System.currentTimeMillis() - cached.storedAtMs <= CATALOG_CACHE_TTL_MS) {
                Log.d(TAG, "Catalog cache hit type=$type catalogId=$catalogId skip=$skip host=${url.safeHost()}")
                emit(NetworkResult.Success(SourcedRow(cached.row, RowSource.MEMORY)))
                return@flow
            } else {
                catalogCache.remove(url)
            }
        }

        // 2) Disk first-paint cache (first page of a home row only). Serve stale
        //    immediately, then fall through to revalidate over the network.
        val diskEligible = skip == 0 && extraArgs.isEmpty()
        var servedDiskRow: CatalogRow? = null
        if (diskEligible) {
            val diskRow = try {
                readDiskRow(
                    url = url,
                    addonBaseUrl = addonBaseUrl,
                    addonId = addonId,
                    addonName = addonName,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = type,
                    skip = skip,
                    skipStep = skipStep,
                    supportsSkip = supportsSkip
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // a broken disk row is a cache miss
            }
            if (diskRow != null) {
                Log.d(TAG, "Catalog disk hit (serving stale, revalidating) type=$type catalogId=$catalogId host=${url.safeHost()}")
                servedDiskRow = diskRow
                emit(NetworkResult.Success(SourcedRow(diskRow, RowSource.DISK)))
            }
        }

        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip host=${url.safeHost()}"
        )

        val healthT0 = android.os.SystemClock.elapsedRealtime()
        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                healthStore.recordAddonOutcome(addonBaseUrl, HealthOutcome.SUCCESS, android.os.SystemClock.elapsedRealtime() - healthT0)
                val catalogRow = buildCatalogRow(
                    data = result.data,
                    addonBaseUrl = addonBaseUrl,
                    addonId = addonId,
                    addonName = addonName,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = type,
                    skip = skip,
                    skipStep = skipStep,
                    supportsSkip = supportsSkip,
                    extraArgs = extraArgs
                )
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${catalogRow.items.size}"
                )
                catalogCache[url] = CacheEntry(catalogRow, System.currentTimeMillis())
                // Fire-and-forget: flushDisk rewrites the whole cache file under
                // diskMutex, so the fresh row must not wait behind it. Launched before
                // the emit so a collector cancelled mid-emit still persists the row.
                if (diskEligible) {
                    ioScope.launch {
                        try {
                            writeDiskRow(url, wireJson.encodeToString(result.data))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Catalog disk cache write failed catalogId=$catalogId: ${e::class.simpleName}")
                        }
                    }
                }

                // Always emitted: getCatalog decides whether a row identical to the disk row
                // served is dropped (class KDoc).
                emit(NetworkResult.Success(SourcedRow(catalogRow, RowSource.NETWORK)))
            }
            is NetworkResult.Error -> {
                healthStore.recordAddonOutcome(addonBaseUrl, HealthOutcome.FAILURE, android.os.SystemClock.elapsedRealtime() - healthT0)
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} host=${url.safeHost()}"
                )
                // If a disk row was already served, keep it on screen rather than
                // surfacing the error and blanking the row.
                if (servedDiskRow == null) {
                    emit(result)
                }
            }
            NetworkResult.Loading -> { /* getCatalog emits Loading */ }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildCatalogRow(
        data: CatalogResponseDto,
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        supportsSkip: Boolean,
        extraArgs: Map<String, String>
    ): CatalogRow {
        // Main review F17: on duplicate IDs keep the FIRST (catalogue order is
        // meaningful) but backfill visual/metadata fields the first copy lacks from
        // the dropped duplicate, so a richer poster/background/description is not lost.
        // Build 1 / 0.8.4 merge: metas may contain null elements and entries with
        // blank id/name (upstream malformed-entry guard). Skip those; toDomainOrNull
        // returns null for a blank id or name.
        val rawItemCount = data.metas.size
        val merged = LinkedHashMap<String, MetaPreview>(rawItemCount)
        data.metas.forEach { dto ->
            val item = dto?.toDomainOrNull(type, addonBaseUrl) ?: return@forEach
            val prior = merged[item.id]
            merged[item.id] = if (prior == null) {
                item
            } else {
                prior.copy(
                    poster = prior.poster ?: item.poster,
                    background = prior.background ?: item.background,
                    logo = prior.logo ?: item.logo,
                    description = prior.description ?: item.description,
                    releaseInfo = prior.releaseInfo ?: item.releaseInfo,
                    imdbRating = prior.imdbRating ?: item.imdbRating,
                    landscapePoster = prior.landscapePoster ?: item.landscapePoster,
                    behaviorHints = prior.behaviorHints ?: item.behaviorHints
                ).withRatingsMerged(item)
            }
        }
        val items = merged.values.toList()
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.fromString(type),
            rawType = type,
            items = items,
            isLoading = false,
            hasMore = supportsSkip && rawItemCount > 0,
            currentPage = if (skipStep > 0) skip / skipStep else 0,
            supportsSkip = supportsSkip,
            skipStep = skipStep,
            nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
            extraArgs = extraArgs
        )
    }

    // ---- disk first-paint cache -------------------------------------------------

    private suspend fun ensureDiskLoaded() {
        if (diskLoaded) return
        diskMutex.withLock {
            if (diskLoaded) return
            withContext(Dispatchers.IO) {
                runCatching {
                    if (diskFile.exists()) {
                        val root = JSONObject(diskFile.readText())
                        val now = System.currentTimeMillis()
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val obj = root.getJSONObject(key)
                            val storedAt = obj.getLong("t")
                            if (now - storedAt <= DISK_CACHE_MAX_AGE_MS) {
                                diskCache[key] = DiskEntry(obj.getString("b"), storedAt)
                            }
                        }
                    }
                }
            }
            diskLoaded = true
        }
    }

    private suspend fun readDiskRow(
        url: String,
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        supportsSkip: Boolean
    ): CatalogRow? {
        ensureDiskLoaded()
        val entry = diskCache[url] ?: return null
        if (System.currentTimeMillis() - entry.storedAtMs > DISK_CACHE_MAX_AGE_MS) {
            diskCache.remove(url)
            return null
        }
        val dto = runCatching { wireJson.decodeFromString<CatalogResponseDto>(entry.dtoJson) }.getOrNull() ?: return null
        return buildCatalogRow(
            data = dto,
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            addonName = addonName,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            skip = skip,
            skipStep = skipStep,
            supportsSkip = supportsSkip,
            extraArgs = emptyMap()
        )
    }

    private suspend fun writeDiskRow(url: String, dtoJson: String) {
        ensureDiskLoaded()
        diskCache[url] = DiskEntry(dtoJson, System.currentTimeMillis())
        if (diskCache.size > DISK_CACHE_MAX_ENTRIES) {
            diskCache.entries
                .sortedBy { it.value.storedAtMs }
                .take(diskCache.size - DISK_CACHE_MAX_ENTRIES)
                .forEach { diskCache.remove(it.key) }
        }
        flushDisk()
    }

    private suspend fun flushDisk() {
        diskMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = JSONObject()
                    diskCache.entries.forEach { (key, entry) ->
                        root.put(key, JSONObject().put("b", entry.dtoJson).put("t", entry.storedAtMs))
                    }
                    // Atomic tmp+rename (house pattern for plain-JSON file caches):
                    // a crash mid-write must not leave a truncated blob that would
                    // kill the whole first-paint cache on the next cold start.
                    val tmp = File(diskFile.parentFile, DISK_CACHE_FILE_NAME + ".tmp")
                    tmp.writeText(root.toString())
                    if (!tmp.renameTo(diskFile)) {
                        tmp.delete()
                    }
                }
            }
        }
    }

    override suspend fun clearCaches() {
        // In-memory freshness LRU: a synchronizedMap, so a single clear() is
        // atomic on its own.
        catalogCache.clear()
        // Disk first-paint cache: clear the map and delete the backing file
        // under the same mutex ensureDiskLoaded()/flushDisk() take, so a
        // concurrent flush cannot interleave with the delete. diskLoaded is
        // left true so a later read does not reload the file just deleted.
        // A writeDiskRow racing this clear simply repopulates afterwards,
        // which is a fresh fetch doing exactly what it should.
        diskMutex.withLock {
            diskCache.clear()
            withContext(Dispatchers.IO) {
                runCatching { if (diskFile.exists()) diskFile.delete() }
            }
            diskLoaded = true
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeUrlArg(key)}=${encodeUrlArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }
}

/**
 * Spec 102: the age ratings of two copies of one title in one page. The strictest US code either
 * copy knows (its rating or its non-US map's US entry) wins and the non-US ratings are dropped, as
 * the mappers do; with none, both copies' non-US ratings count together ([NonUsRatingBoards.union]),
 * so neither copy's stricter board is lost.
 */
internal fun MetaPreview.withRatingsMerged(duplicate: MetaPreview): MetaPreview {
    val nonUs = NonUsRatingBoards.union(nonUsRatings, duplicate.nonUsRatings)
    val usCode = listOfNotNull(
        ContentRatingCap.usRatingCode(ageRating),
        ContentRatingCap.usRatingCode(duplicate.ageRating),
        NonUsRatingBoards.usCodeOf(nonUs)
    ).maxByOrNull { ContentRatingCap.tierOf(it)?.ordinal ?: -1 }
    if (usCode != null) return copy(ageRating = usCode, nonUsRatings = emptyMap())
    return copy(ageRating = ageRating ?: duplicate.ageRating, nonUsRatings = nonUs)
}
