package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.network.executeCancellable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

/** One raw HTTP exchange, any status. [headers] keys are lower-case. [body] is per-user data: [toString] leaves it out. */
data class MdbListRawResponse(val status: Int, val body: String, val headers: Map<String, String>) {
    override fun toString(): String = "MdbListRawResponse(status=$status)"
}

/**
 * The transport seam: OkHttp in the app, a fake in tests. Throws [IOException] when no answer came back. [url] carries
 * the key: an implementation must never log it or put it in an exception message.
 */
fun interface MdbListHttpEngine {
    suspend fun execute(method: MdbListHttpMethod, url: String, headers: Map<String, String>, body: String?): MdbListRawResponse
}

/** OkHttp transport. [executeCancellable] ties the call to the coroutine, so a closed screen really stops it. */
class OkHttpMdbListEngine(private val client: OkHttpClient) : MdbListHttpEngine {
    override suspend fun execute(
        method: MdbListHttpMethod,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): MdbListRawResponse {
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val requestBody = body?.let(::OneShotJsonBody)
        when (method) {
            MdbListHttpMethod.GET -> builder.get()
            MdbListHttpMethod.POST -> builder.post(requestBody ?: OneShotJsonBody(EMPTY_BODY))
            MdbListHttpMethod.DELETE -> builder.delete(requestBody)
        }
        return withContext(Dispatchers.IO) {
            client.newCall(builder.build()).executeCancellable { response ->
                MdbListRawResponse(
                    status = response.code,
                    body = readMdbListBody(response.body.source(), response.body.contentLength()),
                    headers = response.headers.toMultimap().entries.associate { (name, values) ->
                        name.lowercase() to values.joinToString(",")
                    }
                )
            }
        }
    }

    /**
     * A write body OkHttp may send only once: one-shot stops every replay inside OkHttp (connection-failure recovery, a
     * `503` with `Retry-After: 0`, a 307/308 redirect), so a write that may have landed is never repeated (Spec 105 S1a).
     */
    private class OneShotJsonBody(json: String) : RequestBody() {
        private val bytes = json.encodeToByteArray()
        override fun contentType(): MediaType = JSON_MEDIA_TYPE
        override fun contentLength(): Long = bytes.size.toLong()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val EMPTY_BODY = "{}"
    }
}

/**
 * Watched history and big lists come back whole (upstream capped read-only lists at 5,000 titles), so the cap is
 * generous; it guards against a broken or hostile answer, read in bounded chunks (KT-JSON-03).
 */
internal const val MDBLIST_MAX_BODY_BYTES = 32L * 1024L * 1024L

internal fun readMdbListBody(source: BufferedSource, contentLength: Long, maxBytes: Long = MDBLIST_MAX_BODY_BYTES): String {
    if (contentLength > maxBytes) throw MdbListReplyTooLargeException()
    val buffer = Buffer()
    while (true) {
        val read = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (read == -1L) break
        if (buffer.size > maxBytes) throw MdbListReplyTooLargeException()
    }
    return buffer.readUtf8()
}
