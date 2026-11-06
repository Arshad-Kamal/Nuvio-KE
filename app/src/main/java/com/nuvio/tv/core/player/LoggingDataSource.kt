package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

@OptIn(UnstableApi::class)
class LoggingDataSource(
    private val upstream: DataSource,
    private val site: String
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val t0 = SystemClock.elapsedRealtime()
        val uriName = dataSpec.uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "stream"
        return try {
            val len = upstream.open(dataSpec)
            val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)
            val line = "[$site] file=$uriName pos=${dataSpec.position} reqLen=${dataSpec.length} -> len=$len ms=$elapsed"
            Log.i("DS_OPEN", line)
            len
        } catch (e: Exception) {
            val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)
            val line = "[$site] file=$uriName pos=${dataSpec.position} FAILED ${e.javaClass.simpleName}: ${e.message} ms=$elapsed"
            Log.w("DS_OPEN", line)
            throw e
        }
    }
    override fun close() = upstream.close()
}

@OptIn(UnstableApi::class)
class LoggingDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val site: String
) : DataSource.Factory {
    override fun createDataSource(): DataSource = LoggingDataSource(upstream.createDataSource(), site)
}
