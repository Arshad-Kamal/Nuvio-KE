package com.nuvio.tv.core.torrent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class TorrServerFile(
    val id: Int,
    val path: String,
    val length: Long
)

data class TorrServerStats(
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val peers: Int,
    val seeds: Int,
    val preloadedBytes: Long,
    val loadedSize: Long,
    val torrentSize: Long,
    val files: List<TorrServerFile>
) {
    /** Fraction (0..1) of the torrent downloaded; 0 until TorrServer knows the size. */
    val totalProgress: Float
        get() = if (torrentSize <= 0L) 0f else (loadedSize.toFloat() / torrentSize).coerceIn(0f, 1f)
}

@Singleton
class TorrServerApi @Inject constructor(
    private val binary: TorrServerBinary
) {
    companion object {
        private const val TAG = "TorrServerApi"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    private val client = binary.httpClient

    private val baseUrl: String get() = binary.baseUrl

    suspend fun addTorrent(magnetLink: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "add")
            put("link", magnetLink)
            put("save_to_db", false)
        }

        val request = Request.Builder()
            .url("$baseUrl/torrents")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "addTorrent failed: ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(response.body.string())
                val hash = json.optString("hash", "")
                Log.d(TAG, "Torrent added: $hash")
                hash.ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "addTorrent error", e)
            null
        }
    }

    suspend fun getTorrentStats(hash: String): TorrServerStats? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "get")
            put("hash", hash)
        }

        val request = Request.Builder()
            .url("$baseUrl/torrents")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body.string())

                val files = mutableListOf<TorrServerFile>()
                val fileList = json.optJSONArray("file_stats") ?: JSONArray()
                for (i in 0 until fileList.length()) {
                    val f = fileList.getJSONObject(i)
                    files.add(TorrServerFile(
                        id = f.optInt("id", i + 1),
                        path = f.optString("path", ""),
                        length = f.optLong("length", 0)
                    ))
                }

                TorrServerStats(
                    downloadSpeed = json.optLong("download_speed", 0),
                    uploadSpeed = json.optLong("upload_speed", 0),
                    peers = json.optInt("active_peers", 0),
                    seeds = json.optInt("connected_seeders", 0),
                    preloadedBytes = json.optLong("preloaded_bytes", 0),
                    loadedSize = json.optLong("loaded_size", 0),
                    torrentSize = json.optLong("torrent_size", 0),
                    files = files
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTorrentStats error", e)
            null
        }
    }

    suspend fun dropTorrent(hash: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "drop")
            put("hash", hash)
        }

        val request = Request.Builder()
            .url("$baseUrl/torrents")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        try {
            client.newCall(request).execute().close()
            Log.d(TAG, "Torrent dropped: $hash")
        } catch (e: Exception) {
            Log.w(TAG, "dropTorrent error", e)
        }
    }

    fun getStreamUrl(magnetLink: String, fileIdx: Int): String {
        val encodedLink = URLEncoder.encode(magnetLink, "UTF-8")
        return "$baseUrl/stream?link=$encodedLink&index=$fileIdx&play"
    }
}
