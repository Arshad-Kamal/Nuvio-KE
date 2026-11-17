package com.nuvio.tv.livetv.data

import android.util.Log
import com.nuvio.tv.data.local.localStoreJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream

/**
 * One JSON value in one file (Spec 34 D11). Blocking: callers run it off main. A missing or
 * unreadable file reads as null (the caller falls back to empty and refetches); writes go to a
 * synced temp file renamed over the target, so a crash or power cut mid-write leaves the old file
 * (pattern of `ContinueWatchingEnrichmentCache.atomicWrite`, plus the fsync). [write] throws
 * IOException on failure.
 *
 * Both directions stream (Spec 115 I7): the file is never held whole as a String or byte array
 * beside its decoded value, which on a 1 GB TV added ~3x a 15-40 MiB cache to the peak.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {

    fun read(): T? {
        if (!file.exists()) return null
        return try {
            val startNs = System.nanoTime()
            val value = file.inputStream().buffered(BUFFER_BYTES).use { localStoreJson.decodeFromStream(serializer, it) }
            // Spec 12/S57: Shield decode time of the D11 caches (one line per cold read).
            Log.i(TAG, "Decoded ${file.name} (${file.length() / 1024} KiB) in ${(System.nanoTime() - startNs) / 1_000_000} ms")
            value
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable ${file.name}, starting empty: ${e.javaClass.simpleName}")
            null
        }
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            val buffered = out.buffered(BUFFER_BYTES)
            localStoreJson.encodeToStream(serializer, value, buffered)
            buffered.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // renameTo can fail on some filesystems; fall back to copy+delete.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "LiveTvJsonFile"
        const val BUFFER_BYTES = 64 * 1024
    }
}
