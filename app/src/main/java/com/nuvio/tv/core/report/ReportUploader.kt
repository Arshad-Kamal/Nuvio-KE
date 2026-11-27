package com.nuvio.tv.core.report

import android.util.Log
import com.nuvio.tv.core.di.appUserAgent
import com.nuvio.tv.core.network.dns.AppDns
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Spec 116 E1: what one upload came to; the panel maps each to its line. */
sealed interface UploadResult {
    data class Sent(val code: String) : UploadResult
    /** The call failed while the TV had no validated network: "this TV isn't connected to the internet". */
    data object NoInternet : UploadResult
    /** Online but failed (timeout, DNS, 5xx, a redirect, a reply without a valid code): "didn't answer"; Try again shown. */
    data object ServerUnavailable : UploadResult
    /** 429 (D5: the per-minute limit or the daily cap, A13): "Try again later." */
    data object RateLimited : UploadResult
    /** Any other 4xx: the same bytes would be refused again, so no Try again. */
    data object Refused : UploadResult
}

/**
 * Spec 116 D2/D5 client side: posts one report's bytes to `<REPORT_URL>/v1/reports` and returns the
 * code the Worker named it. Send and Try again pass the same bytes (built once, D2). The client is
 * its own (never a DI client: `@Validated` carries the access-header and identity interceptors,
 * which must not reach this host) and resolves through [AppDns] (Spec 97 D1). Redirects are not
 * followed, so the bytes reach only the configured host. Cancelling the caller cancels the call.
 * [isOnline] is asked when a call fails: an exception type cannot tell offline from a failing
 * Private DNS provider or a gone Worker host (all `UnknownHostException`), the network state can.
 */
class ReportUploader(
    baseUrl: String,
    private val isOnline: () -> Boolean,
    private val client: OkHttpClient = defaultClient(),
) {
    private val endpoint = baseUrl.trimEnd('/') + PATH

    /**
     * [onProgress] runs on an OkHttp thread (post to thread-safe state) and counts bytes handed to
     * the socket, not bytes received; it stops once the call is cancelled. It must not throw: OkHttp
     * rethrows a non-IOException on its own thread.
     */
    suspend fun upload(bytes: ByteArray, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): UploadResult {
        lateinit var call: Call
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", appUserAgent())
            .post(ProgressBody(bytes) { sent, total -> if (!call.isCanceled()) onProgress(sent, total) })
            .build()
        call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // The exception's message names the host; log its type only.
                    Log.w(TAG, "upload failed: ${e.javaClass.simpleName}")
                    cont.resume(if (isOnline()) UploadResult.ServerUnavailable else UploadResult.NoInternet)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { resultOf(it) }
                    } catch (e: IOException) {
                        Log.w(TAG, "reply read failed: ${e.javaClass.simpleName}")
                        UploadResult.ServerUnavailable
                    }
                    cont.resume(result)
                }
            })
        }
    }

    private class ProgressBody(
        private val bytes: ByteArray,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = MEDIA_TYPE
        override fun contentLength(): Long = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            val total = bytes.size.toLong()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(CHUNK, bytes.size - offset)
                sink.write(bytes, offset, count)
                sink.flush()
                offset += count
                onProgress(offset.toLong(), total)
            }
        }
    }

    companion object {
        private const val TAG = "ReportUpload"
        private const val PATH = "/v1/reports"
        private const val CHUNK = 8 * 1024
        private const val MAX_REPLY_BYTES = 1024L
        private val MEDIA_TYPE = "application/x-nuvio-report".toMediaType()
        private val CODE = Regex("KE-[0-9A-HJKMNP-TV-Z]{6}")
        private val replyJson = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(AppDns)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private fun resultOf(response: Response): UploadResult = when (response.code) {
            201 -> codeOf(response)?.let { UploadResult.Sent(it) } ?: UploadResult.ServerUnavailable
            429 -> UploadResult.RateLimited
            in 400..499 -> UploadResult.Refused
            else -> UploadResult.ServerUnavailable
        }

        /** The reply's code, or null for an oversized, malformed or off-alphabet reply. */
        private fun codeOf(response: Response): String? {
            val source = response.body.source()
            if (source.request(MAX_REPLY_BYTES + 1)) return null
            val text = source.buffer.readUtf8()
            val code = try {
                (replyJson.parseToJsonElement(text) as? JsonObject)?.get("code") as? JsonPrimitive
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            return code?.takeIf { it.isString }?.content?.takeIf { CODE.matches(it) }
        }
    }
}
