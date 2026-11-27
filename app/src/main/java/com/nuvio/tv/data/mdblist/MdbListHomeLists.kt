package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.sync.HomeCatalogOrdering
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Where a Home list comes from: the account's own lists, lists it liked, or lists it imported from elsewhere. */
enum class MdbListHomeListKind { OWN, LIKED, EXTERNAL }

/**
 * One list Home can show as a row (Spec 105 S5, D4/D5; mockup v3 D1). [key] is its Home catalog order key: external
 * lists have their own id space, so they carry their own prefix. [dynamic]: an own list MDBList fills by its filters
 * (D30; only own lists carry it, D31).
 */
data class MdbListHomeList(
    val kind: MdbListHomeListKind,
    val id: Long,
    val name: String,
    val itemCount: Int,
    val dynamic: Boolean = false
) {
    val key: String get() = if (kind == MdbListHomeListKind.EXTERNAL) "$EXTERNAL_KEY_PREFIX$id" else "$KEY_PREFIX$id"

    internal val itemsPath: String
        get() = if (kind == MdbListHomeListKind.EXTERNAL) "/external/lists/$id/items" else "/lists/$id/items"

    companion object {
        /** Every MDBList Home key starts with this (the watchlist's, lists', external lists'). */
        const val KEY_PREFIX = HomeCatalogOrdering.MDBLIST_KEY_PREFIX
        const val EXTERNAL_KEY_PREFIX = "${KEY_PREFIX}ext_"
        const val WATCHLIST_KEY = "${KEY_PREFIX}watchlist"
    }
}

/** One page of a list's titles in list order, and the cursor of the next (null = the list ends here for Home). */
data class MdbListListPage(val items: List<MdbListWatchlistItem>, val nextCursor: String?)

/**
 * The account's lists for Home rows and their pages (Spec 105 S5a). The watchlist row is not read here: it is the S3a
 * snapshot (0 requests). Per profile and key session (a new key starts over; an answer read with a key that changed
 * meanwhile is dropped, never served):
 * - the index (`/lists/user`, `/lists/liked`, `/external/lists/user`) is read again only after [INDEX_FLOOR_MS]; own
 *   lists are required; a liked or external call that fails keeps that kind's lists held before (none on a first
 *   read), and the whole index is read again after [RETRY_MS];
 * - a list's first page is kept and reused while the index names the same title count and it is younger than
 *   [FIRST_PAGE_TTL_MS] (MDBList's index carries no update stamp; the count moves with adds and removals);
 * - a failed read (paused at the daily limit, D5, included) is not tried again for [RETRY_MS]: meanwhile the last
 *   answer is served, or the call fails unsent. Signed out never serves the old key's lists.
 * Later pages ([nextPage]) are never kept: Home holds what it showed. A list stops at [MAX_TITLES] titles or at a page
 * that adds no new title (upstream 0262b51cb, 739848ac3: endless dynamic lists filled the heap).
 */
class MdbListHomeLists(
    private val session: MdbListSession,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    /**
     * A kept answer, read (or tried) at [readAt], not read again for [wait] (the floor after a success, [RETRY_MS] after
     * a failure); [failure] is what a wait with no value throws (the last read's own failure, nothing sent).
     */
    private class Held<T>(
        val value: T?,
        val readAt: Long,
        val wait: Long,
        val itemCount: Int = 0,
        val failure: IOException? = null
    )

    private class KeySession(val key: String) {
        var index: Held<List<MdbListHomeList>>? = null
        val firstPages = HashMap<String, Held<MdbListListPage>>()
    }

    private val sessions = ConcurrentHashMap<Int, KeySession>()
    private val locks = ConcurrentHashMap<Int, Mutex>()

    /** The account's lists in index order (own, then liked, then external; each list once). */
    suspend fun lists(profileId: Int): List<MdbListHomeList> = lockOf(profileId).withLock {
        val held = sessionFor(profileId) ?: throw MdbListSignedOutException()
        val kept = held.index
        if (kept != null && waiting(kept)) return@withLock kept.value ?: throw kept.failure!!
        val now = nowEpochMs()
        try {
            val (read, failedKinds) = readIndex(profileId)
            requireSameKey(profileId, held)
            // A kind that failed keeps what was held for it, so its rows do not vanish until the retry.
            val index = (read + kept?.value.orEmpty().filter { it.kind in failedKinds })
                .sortedBy { it.kind.ordinal }
                .distinctBy { it.key }
            held.index = Held(index, now, if (failedKinds.isEmpty()) INDEX_FLOOR_MS else RETRY_MS)
            index
        } catch (failure: IOException) {
            if (failure is MdbListSignedOutException) throw failure
            requireSameKey(profileId, held)
            held.index = Held(kept?.value, now, RETRY_MS, failure = failure)
            kept?.value ?: throw failure
        }
    }

    /** [list]'s first page: kept per key session (see the class rules). */
    suspend fun firstPage(profileId: Int, list: MdbListHomeList): MdbListListPage = lockOf(profileId).withLock {
        val held = sessionFor(profileId) ?: throw MdbListSignedOutException()
        val kept = held.firstPages[list.key]
        if (kept != null && waiting(kept) && (kept.value == null || kept.itemCount == list.itemCount)) {
            return@withLock kept.value ?: throw kept.failure!!
        }
        val now = nowEpochMs()
        try {
            val page = readPage(profileId, list, cursor = null, seen = emptySet())
            requireSameKey(profileId, held)
            held.firstPages[list.key] = Held(page, now, FIRST_PAGE_TTL_MS, list.itemCount)
            page
        } catch (failure: IOException) {
            if (failure is MdbListSignedOutException) throw failure
            requireSameKey(profileId, held)
            // The new count: the retry wait holds even though the page served is the older one.
            held.firstPages[list.key] = Held(kept?.value, now, RETRY_MS, list.itemCount, failure)
            kept?.value ?: throw failure
        }
    }

    /**
     * The page at [cursor], keeping only titles whose [homeKey] is not in [seen] (what Home already shows for the row).
     * No cursor after it once nothing new came, the cursor repeats or [MAX_TITLES] is reached; titles past the cap are
     * cut.
     */
    suspend fun nextPage(profileId: Int, list: MdbListHomeList, cursor: String, seen: Set<String>): MdbListListPage {
        val held = sessionFor(profileId) ?: throw MdbListSignedOutException()
        return readPage(profileId, list, cursor, seen).also { requireSameKey(profileId, held) }
    }

    /** Within its wait (a clock set back before [Held.readAt] counts as due). */
    private fun waiting(kept: Held<*>): Boolean = nowEpochMs() - kept.readAt in 0 until kept.wait

    /** The answer was read with [held]'s key: a key changed or removed meanwhile drops it (nothing kept or served). */
    private suspend fun requireSameKey(profileId: Int, held: KeySession) {
        if (sessionFor(profileId) !== held) throw MdbListSignedOutException()
    }

    /** The index read, and the kinds that could not be read (liked, external). */
    private suspend fun readIndex(profileId: Int): Pair<List<MdbListHomeList>, Set<MdbListHomeListKind>> {
        val own = parseMdbListHomeLists(get(profileId, USER_LISTS_PATH).body, MdbListHomeListKind.OWN)
        val failed = mutableSetOf<MdbListHomeListKind>()
        val liked = optional({ failed += MdbListHomeListKind.LIKED }) {
            buildList {
                for (pageIndex in 0 until MAX_LIKED_PAGES) {
                    val offset = (pageIndex * LIKED_PAGE_SIZE).toString()
                    val reply = get(profileId, LIKED_LISTS_PATH, mapOf("limit" to LIKED_PAGE_SIZE.toString(), "offset" to offset))
                    val page = parseMdbListLikedPage(reply.body)
                    addAll(page.lists)
                    if (!page.hasMore || page.lists.isEmpty()) break
                }
            }
        }
        val external = optional({ failed += MdbListHomeListKind.EXTERNAL }) {
            parseMdbListHomeLists(get(profileId, EXTERNAL_LISTS_PATH).body, MdbListHomeListKind.EXTERNAL)
        }
        return (own + liked + external).distinctBy { it.key } to failed
    }

    /** [read]'s lists, or none when it fails for a reason that leaves the own lists good; signed out or paused end the whole read. */
    private suspend fun optional(onFailed: () -> Unit, read: suspend () -> List<MdbListHomeList>): List<MdbListHomeList> = try {
        read()
    } catch (failure: IOException) {
        if (failure is MdbListSignedOutException || failure is MdbListQuotaExceededException ||
            (failure as? MdbListApiException)?.dailyLimit == true
        ) {
            throw failure
        }
        onFailed()
        emptyList()
    }

    private suspend fun readPage(profileId: Int, list: MdbListHomeList, cursor: String?, seen: Set<String>): MdbListListPage {
        val query = buildMap {
            put("limit", PAGE_SIZE.toString())
            put("append_to_response", "poster,genres")
            cursor?.let { put("cursor", it) }
        }
        val response = get(profileId, list.itemsPath, query)
        val page = decodeMdbList(MdbListWatchlistPage.serializer(), response.body)
        val fresh = mdbListListItems(page).filter { it.homeKey !in seen }.distinctBy { it.homeKey }
        val room = (MAX_TITLES - seen.size).coerceAtLeast(0)
        val kept = fresh.take(room)
        val next = (page.pagination?.nextCursor ?: response.headers["x-next-cursor"])?.takeIf(String::isNotBlank)
        val ends = next == null || next == cursor || kept.isEmpty() || seen.size + kept.size >= MAX_TITLES
        return MdbListListPage(kept, if (ends) null else next)
    }

    private suspend fun get(profileId: Int, path: String, query: Map<String, String> = emptyMap()): MdbListResponse =
        session.call(profileId, MdbListRequest(MdbListHttpMethod.GET, path, query))

    /** The profile's key session; a changed key starts a new one, no key = null. */
    private suspend fun sessionFor(profileId: Int): KeySession? {
        val key = session.currentKey(profileId) ?: return null
        // Atomic: nextPage calls this outside the profile lock.
        return sessions.compute(profileId) { _, held -> if (held != null && held.key == key) held else KeySession(key) }
    }

    private fun lockOf(profileId: Int): Mutex = locks.computeIfAbsent(profileId) { Mutex() }

    internal companion object {
        const val USER_LISTS_PATH = "/lists/user"
        const val LIKED_LISTS_PATH = "/lists/liked"
        const val EXTERNAL_LISTS_PATH = "/external/lists/user"
        const val PAGE_SIZE = 100
        const val LIKED_PAGE_SIZE = 100
        const val MAX_LIKED_PAGES = 5
        const val MAX_TITLES = 5_000
        const val INDEX_FLOOR_MS = 30L * 60 * 1_000
        const val FIRST_PAGE_TTL_MS = 6L * 60 * 60 * 1_000
        const val RETRY_MS = 5L * 60 * 1_000
    }
}

/** A title's identity on a Home list row: type and content id (a TMDB-only film and show may share the number). */
val MdbListWatchlistItem.homeKey: String
    get() = "${if (isMovie) "movie" else "show"}:${media.contentId}"

/**
 * A list page's titles in list order: films and shows come in their own arrays, so they are merged by `rank` (rows
 * without one keep their place after the ranked ones). Rows naming no IMDb or TMDB id are left out.
 */
internal fun mdbListListItems(page: MdbListWatchlistPage): List<MdbListWatchlistItem> {
    val rows = page.movies.orEmpty().map { it to true } + page.shows.orEmpty().map { it to false }
    return rows.withIndex()
        .sortedWith(compareBy({ it.value.first.rank.rankOrNull() ?: Long.MAX_VALUE }, { it.index }))
        .mapNotNull { (_, pair) -> pair.first.toWatchlistItem(isMovie = pair.second) }
}

/**
 * `/lists/user` or `/external/lists/user`: an array of `{id, name, items}`; any other shape is a fault. An own list is
 * dynamic when `dynamic` is true or `type` is `dynamic` (D30: S3b's and upstream's reading; the schema names neither
 * field for `/lists/user`, unverified until a real reply).
 */
internal fun parseMdbListHomeLists(reply: String, kind: MdbListHomeListKind): List<MdbListHomeList> {
    val rows = parseJson(reply) as? JsonArray ?: throw MdbListException("MDBList reply not understood")
    return rows.mapNotNull { (it as? JsonObject)?.toHomeList(kind) }
}

internal class MdbListLikedPage(val lists: List<MdbListHomeList>, val hasMore: Boolean)

/** `/lists/liked`: `{lists: [...], pagination: {has_more}}`; any other shape is a fault. */
internal fun parseMdbListLikedPage(reply: String): MdbListLikedPage {
    val root = parseJson(reply) as? JsonObject ?: throw MdbListException("MDBList reply not understood")
    val rows = root["lists"] as? JsonArray ?: throw MdbListException("MDBList reply not understood")
    val hasMore = ((root["pagination"] as? JsonObject)?.get("has_more") as? JsonPrimitive)?.booleanOrNull == true
    return MdbListLikedPage(rows.mapNotNull { (it as? JsonObject)?.toHomeList(MdbListHomeListKind.LIKED) }, hasMore)
}

private fun JsonObject.toHomeList(kind: MdbListHomeListKind): MdbListHomeList? {
    val id = (this["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull?.takeIf { it > 0 } ?: return null
    val name = (this["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.trim()
        ?.takeIf(String::isNotEmpty) ?: return null
    val count = (this["items"] as? JsonPrimitive)?.longOrNull?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    val dynamic = kind == MdbListHomeListKind.OWN && (
        (this["dynamic"] as? JsonPrimitive)?.booleanOrNull == true ||
            (this["type"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content == "dynamic"
        )
    return MdbListHomeList(kind, id, name, count, dynamic)
}

private fun JsonElement?.rankOrNull(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

private fun parseJson(reply: String): JsonElement? = try {
    mdbListJson.parseToJsonElement(reply)
} catch (_: SerializationException) {
    null
}
