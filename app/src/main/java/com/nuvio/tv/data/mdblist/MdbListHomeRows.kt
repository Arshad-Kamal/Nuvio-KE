package com.nuvio.tv.data.mdblist

import android.util.Log
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.HomeCatalogOrdering
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Home's MDBList rows: every key Home may order (the watchlist, then the index), and the rows read so far by key. */
data class MdbListHomeRowsState(val keys: List<String> = emptyList(), val rows: Map<String, CatalogRow> = emptyMap())

/** Home's MDBList rows for ordering: the watchlist ([watchlistTitle]), then every list of the index, hidden ones too. */
data class MdbListHomeIndex(val watchlistTitle: String, val lists: List<MdbListHomeList>)

/** The addon id an MDBList Home row carries (its base URL is empty, its catalog id its order key). */
const val MDBLIST_HOME_ADDON_ID = "mdblist"

/** An MDBList Home row, never an addon catalog: no addon has an empty base URL, nor a catalog id in MDBList's key forms. */
fun isMdbListHomeRow(addonId: String?, addonBaseUrl: String?, catalogId: String?): Boolean =
    addonId == MDBLIST_HOME_ADDON_ID && addonBaseUrl.isNullOrEmpty() && catalogId != null && HomeCatalogOrdering.isMdbListKey(catalogId)

fun CatalogRow.isMdbListHomeRow(): Boolean = isMdbListHomeRow(addonId, addonBaseUrl, catalogId)

/**
 * The active profile's MDBList Home rows (Spec 105 S5b1, D2/D4/D5; mockup v3 D1), shown while a working key is
 * connected, none on a rating-capped profile (MDBList titles carry no age rating: Spec 64 D5, as TMDB list rows; no
 * request either). The watchlist row is the S3a view (0 requests); list rows come from [MdbListHomeLists]: on [refresh]
 * (Home on top) the index, then the first page of every shown list (that class's floors decide what is sent). Keys
 * first seen are admitted into Home's order before their rows show (D2: first, those past 10 at once hidden); hidden
 * lists are not read. Paused at the daily limit (D5): what was read stays, nothing new is sent. Later pages come on
 * [loadMore] and are kept here only while the list's first page stays the same.
 */
class MdbListHomeRows(
    private val lists: MdbListHomeLists,
    private val library: MdbListTrackingLibraryProvider,
    private val accounts: TrackingAccountStore,
    private val profileManager: ProfileManager,
    private val ratingCap: ActiveRatingCap,
    private val layout: LayoutPreferenceDataStore,
    private val scope: CoroutineScope,
    private val watchlistTitle: () -> String
) {
    /** One list as Home shows it: [first] as read, then the pages loaded after it ([seen] = their titles' home keys). */
    internal data class ListRow(
        val first: MdbListListPage,
        val items: List<MetaPreview>,
        val seen: Set<String>,
        val nextCursor: String?,
        val pages: Int,
        val loading: Boolean = false
    ) {
        fun appended(page: MdbListListPage) = copy(
            items = items + page.items.toHomePreviews(),
            seen = seen + page.items.map { it.homeKey },
            nextCursor = page.nextCursor,
            pages = pages + 1,
            loading = false
        )

        companion object {
            fun of(first: MdbListListPage) =
                ListRow(first, first.items.toHomePreviews(), first.items.mapTo(HashSet()) { it.homeKey }, first.nextCursor, pages = 1)
        }
    }

    /** What was read for [profileId] with [key]; [capped] = nothing (no request on a capped profile). */
    private data class Loaded(
        val profileId: Int,
        val key: String,
        val capped: Boolean,
        val lists: List<MdbListHomeList> = emptyList(),
        val rows: Map<String, ListRow> = emptyMap()
    )

    private val loaded = MutableStateFlow<Loaded?>(null)
    private val refreshLock = Mutex()

    /** The active profile's working key (a revoked one is none). */
    private val workingKey: Flow<String?> = accounts.account(TrackingProviderId.MDBLIST)
        .map { account -> account?.takeUnless { it.revoked }?.accessToken }
        .distinctUntilChanged()

    /** Called when Home comes on top: re-reads the key, the cap and the index; reads what is due. */
    fun refresh() {
        scope.launch {
            try {
                refreshLock.withLock { reload() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "MDBList rows not read: ${error.javaClass.simpleName}")
            }
        }
    }

    fun rows(): Flow<MdbListHomeRowsState> = combine(shownState(), library.items) { state, watchlist ->
        if (state == null) MdbListHomeRowsState() else mdbListHomeRowsState(state.lists, state.rows, watchlist, watchlistTitle())
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * The rows Home may order, hidden lists included, from the index last read (no request): Home catalog order's
     * MDBList entries (S5b2). Null while the profile has no MDBList rows (signed out, capped, not read yet).
     */
    fun index(): Flow<MdbListHomeIndex?> = shownState()
        .map { state -> state?.let { MdbListHomeIndex(watchlistTitle(), it.lists) } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** What was read for the active profile with its working key; null when that is nothing Home shows. */
    private fun shownState(): Flow<Loaded?> = combine(profileManager.activeProfileId, workingKey, loaded) { profileId, key, state ->
        state?.takeIf { key != null && it.profileId == profileId && it.key == key && !it.capped }
    }

    /** The next page of list row [rowKey], unless it is loading, ends, or is not shown. A failure leaves the row as it was. */
    fun loadMore(rowKey: String) {
        var claimed: Triple<Loaded, ListRow, MdbListHomeList>? = null
        loaded.update { state ->
            claimed = null
            val row = state?.rows?.get(rowKey)
            val list = state?.lists?.firstOrNull { it.key == rowKey }
            if (state == null || row == null || list == null || row.loading || row.nextCursor == null) {
                state
            } else {
                val loading = row.copy(loading = true)
                claimed = Triple(state, loading, list)
                state.copy(rows = state.rows + (rowKey to loading))
            }
        }
        val (state, row, list) = claimed ?: return
        val cursor = checkNotNull(row.nextCursor)
        scope.launch {
            val page = try {
                lists.nextPage(state.profileId, list, cursor, row.seen)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            loaded.update { current ->
                val now = current?.rows?.get(rowKey)
                // A refresh that replaced the row (another first page, another key or profile) wins.
                if (current == null || current.profileId != state.profileId || current.key != state.key ||
                    now == null || now.first !== row.first || now.nextCursor != cursor
                ) {
                    current
                } else {
                    current.copy(rows = current.rows + (rowKey to (page?.let(now::appended) ?: now.copy(loading = false))))
                }
            }
        }
    }

    private suspend fun reload() {
        profileManager.activeProfileReady.first { it }
        val profileId = profileManager.activeProfileId.value
        val key = workingKey.first()
        if (key == null) {
            loaded.value = null
            return
        }
        val capped = ratingCap.current() != null
        // A switch while reading: that key (or that cap) may be the other profile's.
        if (profileManager.activeProfileId.value != profileId) return
        if (capped) {
            loaded.value = Loaded(profileId, key, capped = true)
            return
        }
        val index = try {
            lists.lists(profileId)
        } catch (signedOut: MdbListSignedOutException) {
            return
        } catch (failure: IOException) {
            emptyList() // MdbListHomeLists already served what it held: none
        }
        val keys = listOf(MdbListHomeList.WATCHLIST_KEY) + index.map { it.key }
        val hidden = layout.admitMdbListKeys(profileId, keys)
        if (profileManager.activeProfileId.value != profileId) return
        val shown = index.filter { it.key !in hidden }
        val shownKeys = shown.mapTo(HashSet()) { it.key }
        // In one update: a page loadMore appends meanwhile is kept, never overwritten by an older copy.
        loaded.update { current ->
            val held = current?.takeIf { it.profileId == profileId && it.key == key }
            Loaded(profileId, key, capped = false, lists = index, rows = held?.rows.orEmpty().filterKeys { it in shownKeys })
        }
        for (list in shown) {
            if (profileManager.activeProfileId.value != profileId) return
            val page = try {
                lists.firstPage(profileId, list)
            } catch (signedOut: MdbListSignedOutException) {
                return
            } catch (failure: IOException) {
                continue // a row already shown keeps what it shows
            }
            loaded.update { current ->
                val row = current?.rows?.get(list.key)
                when {
                    current == null || current.profileId != profileId || current.key != key -> current
                    row != null && row.first == page -> current
                    else -> current.copy(rows = current.rows + (list.key to ListRow.of(page)))
                }
            }
        }
    }

    private companion object {
        const val TAG = "MdbListHomeRows"
    }
}

/** The watchlist row, then every list with a page read, as Home catalog rows (addon "MDBList", order key as catalog id). */
internal fun mdbListHomeRowsState(
    lists: List<MdbListHomeList>,
    rows: Map<String, MdbListHomeRows.ListRow>,
    watchlist: List<LibraryEntry>,
    watchlistTitle: String
): MdbListHomeRowsState {
    val built = LinkedHashMap<String, CatalogRow>()
    built[MdbListHomeList.WATCHLIST_KEY] = mdbListHomeCatalogRow(
        MdbListHomeList.WATCHLIST_KEY, watchlistTitle, watchlist.map(LibraryEntry::toMetaPreview),
        hasMore = false, isLoading = false, page = 0
    )
    lists.forEach { list ->
        val row = rows[list.key] ?: return@forEach
        built[list.key] = mdbListHomeCatalogRow(list.key, list.name, row.items, row.nextCursor != null, row.loading, row.pages)
    }
    return MdbListHomeRowsState(listOf(MdbListHomeList.WATCHLIST_KEY) + lists.map { it.key }, built)
}

private fun mdbListHomeCatalogRow(
    key: String,
    name: String,
    items: List<MetaPreview>,
    hasMore: Boolean,
    isLoading: Boolean,
    page: Int
) = CatalogRow(
    addonId = MDBLIST_HOME_ADDON_ID,
    addonName = MDBLIST_BRAND,
    addonBaseUrl = "",
    catalogId = key,
    catalogName = name,
    type = ContentType.UNKNOWN, // films and shows mixed, as Scout's row
    items = items,
    supportsSkip = true, // Home observes load-more only on paging rows; hasMore says whether a page follows
    isLoading = isLoading,
    hasMore = hasMore,
    currentPage = page
)

/** A list page's titles as Home cards, built as the Library builds the watchlist's. */
private fun List<MdbListWatchlistItem>.toHomePreviews(): List<MetaPreview> =
    mdbListLibraryRows(this).map { it.listed.toMetaPreview() }

/** MDBList's name (a brand, untranslated): the tag after every MDBList row title (D1), the source of its order entries. */
internal const val MDBLIST_BRAND = "MDBList"
