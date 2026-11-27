package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.network.executeCancellable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource

/** One raw HTTP exchange, any status. [headers] keys are lower-case. [body] may hold tokens: [toString] leaves it out. */
data class SimklRawResponse(val status: Int, val body: String, val headers: Map<String, String>) {
    override fun toString(): String = "SimklRawResponse(status=$status)"
}

/** The transport seam: OkHttp in the app, a fake in tests. Throws [IOException] when no answer came back. */
fun interface SimklHttpEngine {
    suspend fun execute(method: SimklHttpMethod, url: String, headers: Map<String, String>, body: String?): SimklRawResponse
}

/**
 * OkHttp transport. [executeCancellable] ties the call to the coroutine, so a timeout (the 10 s revoke) or a closed
 * screen really stops it, body read included, instead of waiting out OkHttp's own 30 s call timeout.
 */
class OkHttpSimklEngine(private val client: OkHttpClient) : SimklHttpEngine {
    override suspend fun execute(
        method: SimklHttpMethod,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): SimklRawResponse {
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        when (method) {
            SimklHttpMethod.GET -> builder.get()
            SimklHttpMethod.POST -> builder.post(requestBody ?: EMPTY_BODY.toRequestBody(JSON_MEDIA_TYPE))
            SimklHttpMethod.DELETE -> builder.delete(requestBody)
        }
        return withContext(Dispatchers.IO) {
            // The response is closed on every status, non-2xx included (upstream 7499cb0ac).
            client.newCall(builder.build()).executeCancellable { response ->
                SimklRawResponse(
                    status = response.code,
                    body = readSimklBody(response.body.source(), response.body.contentLength()),
                    headers = response.headers.toMultimap().entries.associate { (name, values) ->
                        name.lowercase() to values.joinToString(",")
                    }
                )
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val EMPTY_BODY = "{}"
    }
}

/**
 * Library bootstrap replies (`/sync/all-items`) can be several MB (upstream 7ecb53f02), so the cap is generous; it
 * guards against a broken or hostile answer, read in bounded chunks (KT-JSON-03).
 */
internal const val SIMKL_MAX_BODY_BYTES = 64L * 1024L * 1024L

internal fun readSimklBody(source: BufferedSource, contentLength: Long, maxBytes: Long = SIMKL_MAX_BODY_BYTES): String {
    if (contentLength > maxBytes) throw SimklReplyTooLargeException()
    val buffer = Buffer()
    while (true) {
        val read = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (read == -1L) break
        if (buffer.size > maxBytes) throw SimklReplyTooLargeException()
    }
    return buffer.readUtf8()
}
