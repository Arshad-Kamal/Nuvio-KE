package com.nuvio.tv.core.report

import android.content.Context
import com.nuvio.tv.core.player.PlaybackFailure
import com.nuvio.tv.livetv.data.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException

/**
 * Spec 116 D4b: one failure box the main player showed. Never the stream URL, name or description:
 * [host] is the URL's host only (`hostOf`); the report scrubs every field again at send.
 */
@Serializable
data class PlaybackErrorRecord(
    val timeMs: Long,
    val cause: String,
    val httpCode: Int? = null,
    val playerError: String? = null,
    val engine: String? = null,
    val host: String? = null,
    val addonName: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val measuredMbps: Double? = null,
    val neededMbps: Double? = null,
)

/** D4b's fields of a shown failure: never its stream URL, name or description. */
fun playbackErrorRecord(failure: PlaybackFailure, engine: String, nowMs: Long): PlaybackErrorRecord {
    val facts = failure.facts
    return PlaybackErrorRecord(
        timeMs = nowMs,
        cause = failure.cause.name,
        httpCode = facts.httpCode,
        playerError = facts.playerError,
        engine = engine,
        host = facts.host,
        addonName = facts.addon,
        fileName = facts.fileName,
        sizeBytes = facts.fileSizeBytes ?: facts.listedSizeBytes,
        measuredMbps = facts.measuredMbps,
        neededMbps = facts.neededMbps,
    )
}

/**
 * Spec 116 D4b: the last [KEEP] failure boxes, at most [ReportStorage.MAX_AGE_MS] old, in
 * `filesDir/report/playback-errors.json`. A process-wide object (no DI, so `PlayerViewModel` stays
 * untouched); blocking, so callers run it on `Dispatchers.IO`. A failed write is dropped: a
 * diagnostic must never break playback.
 */
object PlaybackErrorLog {
    const val KEEP = 10
    private const val FILE_NAME = "playback-errors.json"
    private val lock = Any()

    fun append(context: Context, record: PlaybackErrorRecord) = append(ReportStorage.dir(context), record)

    fun records(context: Context, now: Long): List<PlaybackErrorRecord> = records(ReportStorage.dir(context), now)

    internal fun append(dir: File, record: PlaybackErrorRecord) = synchronized(lock) {
        val kept = (listOf(record) + read(dir)).filter { record.timeMs - it.timeMs <= ReportStorage.MAX_AGE_MS }.take(KEEP)
        try {
            file(dir).write(kept)
        } catch (_: IOException) {
        }
    }

    /** Newest first, within [ReportStorage.MAX_AGE_MS] of [now]; older records are deleted from the file too. */
    internal fun records(dir: File, now: Long): List<PlaybackErrorRecord> = synchronized(lock) {
        val all = read(dir)
        val kept = all.filter { now - it.timeMs <= ReportStorage.MAX_AGE_MS }
        if (kept.size != all.size) {
            try {
                file(dir).write(kept)
            } catch (_: IOException) {
            }
        }
        kept
    }

    private fun read(dir: File): List<PlaybackErrorRecord> = file(dir).read().orEmpty()

    private fun file(dir: File) = JsonFile(File(dir, FILE_NAME), ListSerializer(PlaybackErrorRecord.serializer()))

}
