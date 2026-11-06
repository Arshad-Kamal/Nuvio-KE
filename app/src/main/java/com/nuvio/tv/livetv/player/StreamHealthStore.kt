package com.nuvio.tv.livetv.player

import android.content.Context
import com.nuvio.tv.livetv.data.JsonFile
import com.nuvio.tv.livetv.model.LiveStream
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
internal data class HealthFile(val version: Int = 1, val records: Map<String, StreamHealthRecord> = emptyMap())

/**
 * Per-stream health records (`filesDir/livetv/health.json`, Spec 34 D8/D11), held in memory and
 * written atomically. Check-on-play only: the player records each attempt, nothing polls. At most
 * [maxRecords] streams are kept (the oldest by last activity go first), so churning addon stream
 * URLs cannot grow the file without bound. Health is telemetry: a failed write costs that record
 * on the next start, never the play.
 */
@Singleton
class StreamHealthStore internal constructor(
    dir: File,
    private val io: CoroutineDispatcher,
    private val maxRecords: Int = MAX_RECORDS
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "livetv"), Dispatchers.IO)

    private val file = JsonFile(File(dir, "health.json"), HealthFile.serializer())
    private val mutex = Mutex()
    private var loaded = false

    private val _records = MutableStateFlow<Map<String, StreamHealthRecord>>(emptyMap())
    val records: StateFlow<Map<String, StreamHealthRecord>> = _records.asStateFlow()

    /** The records as last read from or written to the file (the skip-write rule checks these). */
    private var persisted: Map<String, StreamHealthRecord> = emptyMap()

    suspend fun load() = withContext(io) { mutex.withLock { ensureLoaded() } }

    /**
     * A good play. When the file already holds a healthy play of this stream from the last
     * [PLAYED_WRITE_INTERVAL_MS], the play is kept in memory only, so zapping does not write the
     * file on every tune; the file's play time is then at most that interval old (the 24 h rules
     * cannot tell the difference).
     */
    suspend fun recordPlayed(stream: LiveStream, nowMs: Long) = update(stream) { key, _ ->
        val onFile = persisted[key]
        val fresh = onFile != null && onFile.lastFailure == null &&
            onFile.lastPlayedMs?.let { nowMs - it in 0 until PLAYED_WRITE_INTERVAL_MS } == true
        StreamHealth.played(nowMs) to !fresh
    }

    suspend fun recordFailed(stream: LiveStream, kind: FailureKind, nowMs: Long) = update(stream) { _, current ->
        StreamHealth.failed(current, kind, nowMs) to true
    }

    private suspend fun update(
        stream: LiveStream,
        change: (key: String, current: StreamHealthRecord?) -> Pair<StreamHealthRecord, Boolean>
    ) = withContext(io) {
        mutex.withLock {
            ensureLoaded()
            val key = StreamHealth.key(stream)
            val (record, write) = change(key, _records.value[key])
            val updated = bounded(_records.value + (key to record))
            _records.value = updated
            if (write) {
                try {
                    file.write(HealthFile(records = updated))
                    persisted = updated
                } catch (_: IOException) {
                    // Telemetry: keep the in-memory record; the next record retries the write.
                }
            }
        }
    }

    private fun bounded(records: Map<String, StreamHealthRecord>): Map<String, StreamHealthRecord> {
        if (records.size <= maxRecords) return records
        return records.entries
            .sortedByDescending { it.value.lastAtMs }
            .take(maxRecords)
            .associate { it.key to it.value }
    }

    private fun ensureLoaded() {
        if (loaded) return
        persisted = file.read()?.records.orEmpty()
        _records.value = persisted
        loaded = true
    }

    internal companion object {
        const val MAX_RECORDS = 2_000
        const val PLAYED_WRITE_INTERVAL_MS = 60L * 60 * 1000
    }
}
