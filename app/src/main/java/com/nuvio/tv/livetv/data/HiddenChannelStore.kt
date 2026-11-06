package com.nuvio.tv.livetv.data

import android.content.Context
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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class HiddenFile(val version: Int = 1, val keys: Set<String> = emptySet())

/**
 * Channels the household hid (D16 "Hide channel"; unhidden from Sources > Hidden channels), by
 * channel key, in `filesDir/livetv/hidden.json`: a small store of its own so hiding never
 * rewrites the channel cache (D11).
 */
@Singleton
class HiddenChannelStore internal constructor(dir: File, private val io: CoroutineDispatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "livetv"), Dispatchers.IO)

    private val file = JsonFile(File(dir, "hidden.json"), HiddenFile.serializer())
    private val mutex = Mutex()
    private var loaded = false

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    suspend fun load() = withContext(io) { mutex.withLock { ensureLoaded() } }

    /** Hides or unhides channel [key]; false (nothing changed) when the file cannot be written. */
    suspend fun setHidden(key: String, hidden: Boolean): Boolean = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val updated = if (hidden) _keys.value + key else _keys.value - key
            if (updated == _keys.value) return@withLock true
            try {
                file.write(HiddenFile(keys = updated))
            } catch (_: IOException) {
                return@withLock false
            }
            _keys.value = updated
            true
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        _keys.value = file.read()?.keys.orEmpty()
        loaded = true
    }
}
