package com.nuvio.tv.livetv.source

import android.content.Context
import com.nuvio.tv.livetv.data.JsonFile
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class SourceList(val version: Int = 1, val sources: List<LiveSource> = emptyList())

/**
 * The household's Live TV source list (`filesDir/livetv/sources.json`, Spec 34 D11): a small file
 * apart from the channel cache, written atomically ([JsonFile]). Order = the order sources were
 * added. Like `ChannelRepository`, every mutation writes first and publishes after.
 */
@Singleton
class SourceStore internal constructor(dir: File, private val io: CoroutineDispatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "livetv"), Dispatchers.IO)

    private val file = JsonFile(File(dir, "sources.json"), SourceList.serializer())
    private val mutex = Mutex()
    private var loaded = false

    private val _sources = MutableStateFlow<List<LiveSource>>(emptyList())
    val sources: StateFlow<List<LiveSource>> = _sources.asStateFlow()

    suspend fun load() = withContext(io) { mutex.withLock { ensureLoaded() } }

    suspend fun get(id: String): LiveSource? = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            _sources.value.firstOrNull { it.id == id }
        }
    }

    /** Replaces the source with [source]'s id in place, or appends it. */
    suspend fun upsert(source: LiveSource) = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val current = _sources.value
            val index = current.indexOfFirst { it.id == source.id }
            val updated = if (index < 0) current + source else current.toMutableList().also { it[index] = source }
            write(updated)
        }
    }

    suspend fun remove(id: String) = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val updated = _sources.value.filterNot { it.id == id }
            if (updated.size != _sources.value.size) write(updated)
        }
    }

    private fun write(updated: List<LiveSource>) {
        file.write(SourceList(sources = updated))
        _sources.value = updated
    }

    private fun ensureLoaded() {
        if (loaded) return
        _sources.value = file.read()?.sources.orEmpty()
        loaded = true
    }
}
