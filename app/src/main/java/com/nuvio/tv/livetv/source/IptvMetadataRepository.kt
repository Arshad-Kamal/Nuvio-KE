package com.nuvio.tv.livetv.source

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.livetv.data.IptvBlockDto
import com.nuvio.tv.livetv.data.IptvChannelDto
import com.nuvio.tv.livetv.data.IptvFeedDto
import com.nuvio.tv.livetv.data.IptvMetadata
import com.nuvio.tv.livetv.data.IptvMetadataBuilder
import com.nuvio.tv.livetv.data.JsonFile
import com.nuvio.tv.livetv.data.LiveCompact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iptv-org metadata (Spec 34 D3/D4, L5c): `channels.json`, `feeds.json` and `blocklist.json`
 * (~8 + 8 + 0.1 MB, 31,680 channels on 2026-10-19), fetched through [SourceFetcher] (32 MiB cap
 * each), stream-decoded element by element, joined into [IptvMetadata] and cached in
 * `filesDir/livetv/iptv-meta.json`. Refetched when older than [MAX_AGE_MS]; a failed fetch keeps
 * the previous metadata. D11: this decode is the expensive step, so it runs rarely and off main.
 * The held metadata shares equal values ([LiveCompact], Spec 143).
 */
@Singleton
class IptvMetadataRepository internal constructor(
    dir: File,
    private val fetcher: SourceFetcher,
    private val nowMs: () -> Long,
    private val io: CoroutineDispatcher,
    private val baseUrl: String = BASE_URL
) {
    @Inject
    constructor(@ApplicationContext context: Context, fetcher: SourceFetcher) :
        this(File(context.filesDir, "livetv"), fetcher, System::currentTimeMillis, Dispatchers.IO)

    private val file = JsonFile(File(dir, "iptv-meta.json"), IptvMetadata.serializer())
    // Two locks: a refresh holds its lock across three downloads, and load() (called by every
    // source operation) must not wait for that.
    private val loadMutex = Mutex()
    private val refreshMutex = Mutex()
    private var loaded = false
    /** Last failed refresh (in memory): a persistent failure is retried hourly, not on every open. */
    private var lastFailureMs: Long? = null

    private val _metadata = MutableStateFlow<IptvMetadata?>(null)
    val metadata: StateFlow<IptvMetadata?> = _metadata.asStateFlow()

    suspend fun load() = withContext(io) { loadMutex.withLock { ensureLoaded() } }

    /**
     * Refetches when missing or older than [MAX_AGE_MS] (not within [RETRY_MS] of a failure);
     * true when new metadata was stored.
     */
    suspend fun refreshIfStale(): Boolean = refreshMutex.withLock {
        load()
        val current = _metadata.value
        if (current != null && nowMs() - current.fetchedAtMs < MAX_AGE_MS) return@withLock false
        lastFailureMs?.let { if (nowMs() - it < RETRY_MS) return@withLock false }
        val stored = fetchAndStore()
        lastFailureMs = if (stored) null else nowMs()
        stored
    }

    private suspend fun fetchAndStore(): Boolean {
        try {
            val blocklist = fetchList("blocklist.json", IptvBlockDto.serializer())
            val feeds = fetchList("feeds.json", IptvFeedDto.serializer())
            // The largest list streams straight into the join instead of being held as DTOs.
            var rows = 0
            val built = fetchList("channels.json", IptvChannelDto.serializer()) { channels ->
                IptvMetadataBuilder.build(channels.onEach { rows++ }, feeds.asSequence(), blocklist.asSequence(), nowMs())
            }
            // Counted rows, not built.channels: nsfw blocklist stubs would make an empty list look full.
            if (rows == 0) return false
            // Both off main: refreshIfStale runs on its caller's dispatcher (the screen's, Main).
            _metadata.value = withContext(io) {
                file.write(built)
                LiveCompact.metadata(built)
            }
            return true
        } catch (e: IOException) {
            Log.w(TAG, "iptv-org metadata refresh failed: ${e.javaClass.simpleName}")
        } catch (e: SerializationException) {
            Log.w(TAG, "iptv-org metadata rejected (shape): ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "iptv-org metadata rejected: ${e.javaClass.simpleName}")
        }
        return false
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T, R> fetchList(
        name: String,
        serializer: KSerializer<T>,
        consume: (Sequence<T>) -> R
    ): R = fetcher.fetch(baseUrl + name) { _, body: InputStream ->
        consume(json.decodeToSequence(body, serializer, DecodeSequenceMode.ARRAY_WRAPPED))
    }

    private suspend fun <T> fetchList(name: String, serializer: KSerializer<T>): List<T> =
        fetchList(name, serializer) { it.toList() }

    /**
     * Spec 146: lets the held metadata go; [load] reads it from disk again. A refresh running meanwhile
     * would publish over it, so the caller keeps refreshes out ([LiveSourceManager.unload]).
     */
    internal suspend fun unload() = withContext(io) {
        loadMutex.withLock {
            loaded = false
            _metadata.value = null
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        _metadata.value = file.read()?.let(LiveCompact::metadata)
        loaded = true
    }

    companion object {
        const val BASE_URL = "https://iptv-org.github.io/api/"
        const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        const val RETRY_MS = 60L * 60 * 1000
        private const val TAG = "IptvMetadata"
        private val json = NetworkModule.provideWireJson()
    }
}
