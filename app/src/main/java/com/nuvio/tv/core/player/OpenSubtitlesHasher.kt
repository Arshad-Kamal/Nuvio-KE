package com.nuvio.tv.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.InputStream

/**
 * Calculates the OpenSubtitles file hash for a video stream.
 * Algorithm: hash = fileSize + sum of first 64KB longs + sum of last 64KB longs (little-endian)
 * https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes
 */
object OpenSubtitlesHasher {

    private const val CHUNK_SIZE = 65536L // 64 KB
    private const val LONG_SIZE = 8

    data class Result(val hash: String, val fileSize: Long)

    suspend fun compute(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient = PlayerPlaybackNetworking.createHttpClient(headers)
    ): Result? = withContext(Dispatchers.IO) {
        try {
            val fileSize = getContentLength(url, headers, client) ?: return@withContext null
            if (fileSize < CHUNK_SIZE * 2) return@withContext null

            var hash = fileSize
            hash += readChunkSum(url, headers, offset = 0, length = CHUNK_SIZE, client = client)
            hash += readChunkSum(url, headers, offset = fileSize - CHUNK_SIZE, length = CHUNK_SIZE, client = client)

            Result(
                hash = "%016x".format(hash),
                fileSize = fileSize
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getContentLength(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient
    ): Long? {
        return try {
            client.newCall(probeRequestBuilder(url, headers).head().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val lenStr = response.header("Content-Length")
                val len = lenStr?.toLongOrNull() ?: response.body.contentLength()
                if (len > 0) len else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readChunkSum(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        length: Long,
        client: OkHttpClient
    ): Long {
        val request = probeRequestBuilder(url, headers)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            // Only a genuine 206 validates the requested window: a source ignoring Range
            // replies 200 with the HEAD of the file, silently producing an invalid OS hash
            // (wrong-movie subtitle matches). Fail loudly — compute() maps this to null
            // instead of storing a degraded value — and short reads are fatal too, so a
            // failed chunk can never masquerade as a zero-sum chunk.
            if (response.code != 206) {
                throw java.io.IOException("OpenSubtitlesHasher: expected 206 partial content, got ${response.code}")
            }
            val stream: InputStream = response.body.byteStream()
            val buf = ByteArray(LONG_SIZE)
            var sum = 0L
            var remaining = length
            while (remaining >= LONG_SIZE) {
                var read = 0
                while (read < LONG_SIZE) {
                    val n = stream.read(buf, read, LONG_SIZE - read)
                    if (n < 0) throw java.io.IOException("OpenSubtitlesHasher: short chunk")
                    read += n
                }
                sum += buf.toLongLE()
                remaining -= LONG_SIZE
            }
            return sum
        }
    }

    private fun ByteArray.toLongLE(): Long {
        var v = 0L
        for (i in 0 until LONG_SIZE) v = v or ((this[i].toLong() and 0xFF) shl (i * 8))
        return v
    }
}
