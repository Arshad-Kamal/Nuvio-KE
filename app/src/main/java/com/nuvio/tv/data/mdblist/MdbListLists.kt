package com.nuvio.tv.data.mdblist

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The account's own static lists for the detail Lists panel (Spec 105 S3b, D4; mockup v3 C1-C3). Membership comes from
 * MDBList's membership call (one GET answers every list), so no list is ever read whole or held in memory (the heap
 * limits of upstream's list sync never arise). Dynamic, liked and other people's lists are left out: only MDBList's own
 * filters change them. Every call goes through [MdbListSession], so the daily pause (D5) refuses it unsent.
 */
class MdbListLists(private val session: MdbListSession) {

    /** Per profile: the last lists read and the key they were read with (the panel's fallback while paused, D5). */
    private val lastRead = ConcurrentHashMap<Int, Pair<String, List<MdbListOwnedList>>>()

    /**
     * `GET /lists/user` (own lists only without `include_shared`), static ones only. Upstream's reading: a row counts
     * when `type` is `static` and `dynamic` is not true; the schema names neither field (unverified until a real
     * reply). A row without a positive id or a name is skipped; a reply that is not an array is a fault.
     */
    suspend fun ownedLists(profileId: Int): List<MdbListOwnedList> {
        val key = session.currentKey(profileId)
        val reply = session.call(profileId, MdbListRequest(MdbListHttpMethod.GET, USER_LISTS_PATH)).body
        val owned = parseMdbListOwnedLists(reply)
        // Kept only when the key did not change during the call (the answer is the old key's account otherwise).
        if (key != null && key == session.currentKey(profileId)) lastRead[profileId] = key to owned
        return owned
    }

    /** The last [ownedLists] answer while the profile still has the key it was read with; null otherwise. */
    suspend fun cachedOwnedLists(profileId: Int): List<MdbListOwnedList>? {
        val (key, owned) = lastRead[profileId] ?: return null
        return owned.takeIf { key == session.currentKey(profileId) }
    }

    /** `GET /lists/user/membership`: the ids of the own static lists holding the title. */
    suspend fun membership(profileId: Int, itemId: String, itemType: String): Set<Long> {
        val target = placeable(itemId, itemType)
        val query = buildMap {
            put("mediatype", if (target.isMovie) "movie" else "show")
            target.ids.imdb?.takeIf { it.startsWith("tt") }?.let { put("imdb", it) }
            target.ids.tmdb?.let { put("tmdb", it.toString()) }
            target.ids.tvdb?.toLongOrNull()?.takeIf { it > 0 }?.let { put("tvdb", it.toString()) }
        }
        val reply = session.call(profileId, MdbListRequest(MdbListHttpMethod.GET, MEMBERSHIP_PATH, query)).body
        return parseMdbListMembership(reply)
    }

    /**
     * `POST /lists/{id}/items/add|remove` for one title (flat ids, the schema's example; the watchlist nests `ids`).
     * MDBList's list writes are idempotent like the watchlist's, so the reply's `not_found` is the only failure read: an
     * add it could not place throws [MdbListUnmatchableException]; a removal of a title not on the list is the wanted
     * state. An unreadable reply counts as made (S3a's rule).
     */
    suspend fun setOnList(profileId: Int, listId: Long, itemId: String, itemType: String, add: Boolean) {
        require(listId > 0) { "MDBList list id" }
        val target = placeable(itemId, itemType)
        val ids = target.ids.toMdbListIds() ?: throw MdbListUnmatchableException()
        val body = buildJsonObject { put(if (target.isMovie) "movies" else "shows", JsonArray(listOf(ids))) }.toString()
        val path = "/lists/$listId/items/${if (add) "add" else "remove"}"
        val reply = session.call(profileId, MdbListRequest(MdbListHttpMethod.POST, path, jsonBody = body)).body
        if (add && mdbListMissingCount(reply) > 0) throw MdbListUnmatchableException()
    }

    private fun placeable(itemId: String, itemType: String): MdbListLibraryTarget {
        if (isMdbListUnmatchable(itemId)) throw MdbListUnmatchableException()
        return MdbListLibraryTarget.of(itemId, itemType)
    }

    internal companion object {
        const val USER_LISTS_PATH = "/lists/user"
        const val MEMBERSHIP_PATH = "/lists/user/membership"
    }
}

/** One of the account's own static lists. [movies] / [shows]: which titles it takes (a list may hold one type only). */
data class MdbListOwnedList(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val movies: Boolean = true,
    val shows: Boolean = true
) {
    fun takes(isMovie: Boolean): Boolean = if (isMovie) movies else shows
}

internal fun parseMdbListOwnedLists(reply: String): List<MdbListOwnedList> {
    val rows = parse(reply) as? JsonArray ?: throw MdbListException("MDBList reply not understood")
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        if (row["type"].text() != "static" || (row["dynamic"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
        val id = (row["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        val name = row["name"].text() ?: return@mapNotNull null
        val mediaType = row["mediatype"].text()?.lowercase()
        MdbListOwnedList(
            id = id,
            name = name,
            itemCount = (row["items"] as? JsonPrimitive)?.longOrNull?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            // "movie"/"show" (upstream also reads the plurals); any other type takes neither, so it never shows.
            movies = mediaType == null || mediaType == "movie" || mediaType == "movies",
            shows = mediaType == null || mediaType == "show" || mediaType == "shows"
        )
    }.distinctBy { it.id }
}

/** `{contains: [id, ...]}`; any other shape is a fault (never read as "on no list"). */
internal fun parseMdbListMembership(reply: String): Set<Long> {
    val contains = (parse(reply) as? JsonObject)?.get("contains") as? JsonArray
        ?: throw MdbListException("MDBList reply not understood")
    return contains.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { id -> id > 0 } }.toSet()
}

private fun parse(reply: String): JsonElement? = try {
    mdbListJson.parseToJsonElement(reply)
} catch (_: SerializationException) {
    null
}

private fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
