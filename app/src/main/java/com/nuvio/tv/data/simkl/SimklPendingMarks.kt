package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Watched marks and unmarks Simkl could not take while a profile's daily allowance was spent (Spec 106 S2b, E5),
 * kept per profile in `pending_<profileId>.json` under [directory] and sent after the reset by
 * [SimklHistoryWriter.replayPending], in call order.
 *
 * One mark per key (title + season + episode): a later mark or unmark of a key replaces the earlier one, so only the
 * user's last word on it is sent. At most [CAP] are kept; past that the oldest go (the count is logged). The marks
 * belong to the grant they were made under: [clear] runs with the snapshot's (sign-in approval, disconnect).
 */
class SimklPendingMarks(
    private val directory: File,
    private val io: CoroutineDispatcher,
    private val logDropped: (Int) -> Unit = { Log.w(TAG, "Dropped $it pending Simkl marks over the cap") }
) {
    private val mutex = Mutex()

    // Read and written under [mutex]; a missing key means the file was not read yet.
    private val marks = mutableMapOf<Int, List<SimklPendingMark>>()

    suspend fun add(profileId: Int, items: Collection<TrackingHistoryItem>) =
        append(profileId, items.map { it.toPendingMark(SimklPendingOp.ADD) })

    suspend fun remove(profileId: Int, items: Collection<TrackingMediaReference>) =
        append(profileId, items.map { TrackingHistoryItem(it).toPendingMark(SimklPendingOp.REMOVE) })

    suspend fun get(profileId: Int): List<SimklPendingMark> = mutex.withLock { loaded(profileId) }

    /** The oldest run of marks with one op (what one write call sends); empty when nothing is pending. */
    suspend fun firstBatch(profileId: Int): List<SimklPendingMark> = mutex.withLock {
        val all = loaded(profileId)
        val op = all.firstOrNull()?.op ?: return@withLock emptyList()
        all.takeWhile { it.op == op }
    }

    /** Drops these marks (sent, or refused for good); marks replaced or added meanwhile stay. */
    suspend fun drop(profileId: Int, sent: Collection<SimklPendingMark>) {
        if (sent.isEmpty()) return
        val gone = sent.toSet()
        mutex.withLock { save(profileId, loaded(profileId).filterNot { it in gone }) }
    }

    /** Drops the marks of these keys ([onlyOp]'s alone when given): a write sent now is the user's later word on them. */
    suspend fun supersede(profileId: Int, items: Collection<TrackingMediaReference>, onlyOp: SimklPendingOp? = null) {
        if (items.isEmpty()) return
        val keys = items.mapTo(HashSet()) { TrackingHistoryItem(it).toPendingMark(SimklPendingOp.ADD).key }
        val gone: (SimklPendingMark) -> Boolean = { it.key in keys && (onlyOp == null || it.op == onlyOp) }
        mutex.withLock {
            val current = loaded(profileId)
            if (current.any(gone)) save(profileId, current.filterNot(gone))
        }
    }

    suspend fun clear(profileId: Int) {
        mutex.withLock {
            marks[profileId] = emptyList()
            withContext(io + NonCancellable) {
                file(profileId).delete()
                tempFile(profileId).delete()
            }
        }
    }

    private suspend fun append(profileId: Int, added: List<SimklPendingMark>) {
        if (added.isEmpty()) return
        mutex.withLock {
            // The last of each key wins, within the call too.
            val newest = added.associateBy(SimklPendingMark::key)
            val merged = loaded(profileId).filterNot { it.key in newest } + newest.values
            val over = merged.size - CAP
            if (over > 0) logDropped(over)
            save(profileId, if (over > 0) merged.drop(over) else merged)
        }
    }

    private suspend fun loaded(profileId: Int): List<SimklPendingMark> =
        marks[profileId] ?: withContext(io) { read(profileId) }.also { marks[profileId] = it }

    /** Not cancellable: the file and the copy in memory always change together. */
    private suspend fun save(profileId: Int, next: List<SimklPendingMark>) {
        withContext(io + NonCancellable) {
            if (next.isEmpty()) {
                file(profileId).delete()
            } else {
                write(profileId, simklJson.encodeToString(ListSerializer(SimklPendingMark.serializer()), next))
            }
            marks[profileId] = next
        }
    }

    /** An unreadable file holds nothing: marks are a best effort, the user can mark again. */
    private fun read(profileId: Int): List<SimklPendingMark> {
        val file = file(profileId)
        if (!file.isFile) return emptyList()
        return try {
            simklJson.decodeFromString(ListSerializer(SimklPendingMark.serializer()), file.readText())
        } catch (_: IOException) {
            emptyList()
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    /** Temp file then rename, so a kill mid-write leaves the previous list whole. */
    private fun write(profileId: Int, text: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the Simkl sync folder")
        val temp = tempFile(profileId)
        temp.writeText(text)
        if (!temp.renameTo(file(profileId))) {
            temp.delete()
            throw IOException("Cannot replace the Simkl pending marks")
        }
    }

    private fun file(profileId: Int) = File(directory, "pending_$profileId.json")

    private fun tempFile(profileId: Int) = File(directory, "pending_$profileId.json.tmp")

    companion object {
        const val CAP = 500
        private const val TAG = "SimklPending"
    }
}

@Serializable
enum class SimklPendingOp {
    ADD,
    REMOVE;

    val opposite: SimklPendingOp get() = if (this == ADD) REMOVE else ADD
}

/**
 * One stored mark: what a `/sync/history` body needs ([toHistoryItem]). Of the catalog data only the episode's video id
 * ([videoId]: `entryEpisode` decides the flat path from it, D17; null in files from before it, which then replay by ids).
 */
@Serializable
data class SimklPendingMark(
    val op: SimklPendingOp,
    val kind: TrackingMediaKind,
    val title: String? = null,
    val year: Int? = null,
    val ids: SimklPendingIds = SimklPendingIds(),
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAtEpochMs: Long? = null,
    val videoId: String? = null
) {
    val key: String get() = "${toHistoryItem().media.stableKey}|${season ?: ""}|${episode ?: ""}"

    fun toHistoryItem(): TrackingHistoryItem = TrackingHistoryItem(
        media = TrackingMediaReference(
            kind = kind,
            title = title,
            year = year,
            ids = ids.toExternalIds(),
            episode = episode?.let { TrackingEpisode(season = season, number = it) },
            catalog = videoId?.let { TrackingCatalogReference(contentId = "", contentType = "", videoId = it) }
        ),
        watchedAtEpochMs = watchedAtEpochMs
    )
}

@Serializable
data class SimklPendingIds(
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
    fun toExternalIds() = TrackingExternalIds(imdb, tmdb, tvdb, simkl, mal, anidb, anilist, kitsu, mdblist)
}

private fun TrackingExternalIds.toPendingIds() = SimklPendingIds(imdb, tmdb, tvdb, simkl, mal, anidb, anilist, kitsu, mdblist)

private fun TrackingHistoryItem.toPendingMark(op: SimklPendingOp) = SimklPendingMark(
    op = op,
    kind = media.kind,
    title = media.title,
    year = media.year,
    ids = media.ids.toPendingIds(),
    season = media.episode?.season,
    episode = media.episode?.number,
    watchedAtEpochMs = watchedAtEpochMs.takeIf { op == SimklPendingOp.ADD },
    videoId = media.catalog?.videoId
)
