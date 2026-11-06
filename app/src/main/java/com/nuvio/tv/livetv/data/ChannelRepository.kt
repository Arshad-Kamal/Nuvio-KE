package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class ChannelCache(val version: Int = 1, val channels: List<Channel> = emptyList())

/**
 * The Live TV channel list (Spec 34 D6/D9/D11): every source's channels in one JSON file cache,
 * and the user's EPG overrides in a separate small file (toggling user state never rewrites the
 * cache). Device-wide, not profile-scoped: sources are a household list (O2 own addon URL list);
 * per-person favorites and recents live in their own store (L8).
 *
 * Bound (data/local AGENTS.md): each source is capped by its parser (M3U 100k entries) and a
 * channel a source stops listing is kept, hidden as "Source lost", for [LOST_RETENTION_MS], then
 * pruned. Both files decode on [io], never on main.
 */
@Singleton
class ChannelRepository internal constructor(
    dir: File,
    private val nowMs: () -> Long,
    private val io: CoroutineDispatcher
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, "livetv"), System::currentTimeMillis, Dispatchers.IO)

    private val cacheFile = JsonFile(File(dir, "channels.json"), ChannelCache.serializer())
    private val overridesFile = JsonFile(
        File(dir, "epg-overrides.json"),
        MapSerializer(String.serializer(), EpgOverride.serializer())
    )
    private val mutex = Mutex()
    private var loaded = false

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())

    /** All cached channels, lost ones included (they carry [Channel.lostAtMs]); see [visible]. */
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _overrides = MutableStateFlow<Map<String, EpgOverride>>(emptyMap())

    /** User EPG overrides by channel key; they win over every automatic match ([EpgMatcher]). */
    val overrides: StateFlow<Map<String, EpgOverride>> = _overrides.asStateFlow()

    /** Reads both files once; later calls return at once. */
    suspend fun load() = withContext(io) { mutex.withLock { ensureLoaded() } }

    // Every mutation writes first and publishes after, so memory never runs ahead of disk; a
    // failed write (IOException, e.g. disk full) reaches the caller with both left as they were.

    /**
     * Replaces [sourceId]'s channels with a fresh listing and persists the cache; overrides of
     * channels pruned by this merge go too (bound on the overrides file).
     */
    suspend fun replaceSource(sourceId: String, fresh: List<Channel>) = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val before = _channels.value
            val merged = mergeSource(before, sourceId, fresh, nowMs())
            cacheFile.write(ChannelCache(channels = merged))
            _channels.value = merged
            val mergedKeys = merged.mapTo(HashSet()) { it.key }
            dropOverrides(before.filter { it.sourceId == sourceId && it.key !in mergedKeys })
        }
    }

    /** Drops a removed source's channels and their overrides; other sources' overrides stay. */
    suspend fun removeSource(sourceId: String) = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val (gone, kept) = _channels.value.partition { it.sourceId == sourceId }
            cacheFile.write(ChannelCache(channels = kept))
            _channels.value = kept
            dropOverrides(gone)
        }
    }

    /** Sets (or with null clears) the user's guide choice for channel [key]. */
    suspend fun setOverride(key: String, override: EpgOverride?) = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val updated = if (override == null) _overrides.value - key else _overrides.value + (key to override)
            overridesFile.write(updated)
            _overrides.value = updated
        }
    }

    /** Clears every override that picks a guide of the removed EPG source [epgSourceId]. */
    suspend fun clearOverridesTo(epgSourceId: String) = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val updated = _overrides.value.filterValues { it.ref?.epgSourceId != epgSourceId }
            if (updated.size == _overrides.value.size) return@withLock
            overridesFile.write(updated)
            _overrides.value = updated
        }
    }

    private fun dropOverrides(channels: List<Channel>) {
        if (channels.isEmpty()) return
        val keys = channels.mapTo(HashSet()) { it.key }
        val updated = _overrides.value.filterKeys { it !in keys }
        if (updated.size == _overrides.value.size) return
        overridesFile.write(updated)
        _overrides.value = updated
    }

    private fun ensureLoaded() {
        if (loaded) return
        _channels.value = cacheFile.read()?.channels.orEmpty()
        _overrides.value = overridesFile.read().orEmpty()
        loaded = true
    }

    companion object {
        const val LOST_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** Channels a viewer can see: not lost. */
        fun visible(channels: List<Channel>): List<Channel> = channels.filter { it.lostAtMs == null }

        /**
         * [current] with [sourceId]'s block replaced by [fresh] (fresh order, first of any repeated
         * key, lost flags cleared),
         * followed by the source's channels [fresh] no longer lists, marked lost at [nowMs] (or
         * keeping their first lost time). The block stays where the source was; a new source goes
         * last. Lost channels past [LOST_RETENTION_MS] are pruned from every source.
         */
        internal fun mergeSource(
            current: List<Channel>,
            sourceId: String,
            fresh: List<Channel>,
            nowMs: Long
        ): List<Channel> {
            // Keys are unique: an untrusted listing that repeats one keeps its first entry.
            val unique = fresh.distinctBy { it.key }
            val freshKeys = unique.mapTo(HashSet()) { it.key }
            val block = unique.map { if (it.lostAtMs != null) it.copy(lostAtMs = null) else it } +
                current.filter { it.sourceId == sourceId && it.key !in freshKeys }
                    .map { if (it.lostAtMs == null) it.copy(lostAtMs = nowMs) else it }
            val out = ArrayList<Channel>(current.size + fresh.size)
            var placed = false
            for (ch in current) {
                if (ch.sourceId == sourceId) {
                    if (!placed) {
                        out += block
                        placed = true
                    }
                } else {
                    out += ch
                }
            }
            if (!placed) out += block
            return out.filter { it.lostAtMs == null || nowMs - it.lostAtMs < LOST_RETENTION_MS }
        }
    }
}
