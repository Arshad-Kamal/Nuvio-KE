package com.nuvio.tv.livetv.data

import android.util.Log
import com.nuvio.tv.data.local.localStoreJson
import kotlinx.serialization.KSerializer
import java.io.File
import java.io.FileOutputStream

/**
 * One JSON value in one file (Spec 34 D11). Blocking: callers run it off main. A missing or
 * unreadable file reads as null (the caller falls back to empty and refetches); writes go to a
 * synced temp file renamed over the target, so a crash or power cut mid-write leaves the old file
 * (pattern of `ContinueWatchingEnrichmentCache.atomicWrite`, plus the fsync). [write] throws
 * IOException on failure.
 */
internal class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {

    fun read(): T? {
        if (!file.exists()) return null
        return try {
            val startNs = System.nanoTime()
            localStoreJson.decodeFromString(serializer, file.readText()).also {
                // Spec 12/S57: Shield decode time of the D11 caches (one line per cold read).
                Log.i(TAG, "Decoded ${file.name} (${file.length() / 1024} KiB) in ${(System.nanoTime() - startNs) / 1_000_000} ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable ${file.name}, starting empty: ${e.javaClass.simpleName}")
            null
        }
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(localStoreJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
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
    }
}
