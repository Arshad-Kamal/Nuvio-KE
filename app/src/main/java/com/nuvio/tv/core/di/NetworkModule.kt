package com.nuvio.tv.core.di

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.ParentalGuideApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import com.nuvio.tv.core.tmdb.TmdbNoKeyInterceptor
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.LocaleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.IOException
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import okio.ForwardingSource
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.nuvio.tv.core.network.IPv4FirstDns
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Builds the value sent in the `Accept-Language` request header so addons can
 * serve localized payloads (catalog names, descriptions, etc.). Falls back to
 * the system locale when the user has not overridden the app language. The
 * primary tag is given the highest q-weight; English is appended at q=0.7 as
 * a sensible fallback for addons that don't support the requested locale.
 */
private fun buildAcceptLanguageHeader(): String {
    val explicit = LocaleCache.localeTag
        .takeIf { it.isNotBlank() && it != LocaleCache.UNSET }
    val primaryTag = (explicit ?: java.util.Locale.getDefault().toLanguageTag())
        .takeIf { it.isNotBlank() && it != "und" }
        ?: "en"
    return if (primaryTag.equals("en", ignoreCase = true) ||
        primaryTag.startsWith("en-", ignoreCase = true)
    ) {
        primaryTag
    } else {
        "$primaryTag,en;q=0.7"
    }
}

/**
 * Query parameter names whose values are credentials.
 *
 * Live debug exposures today: Stremio addon keys embedded in URL PATHS
 * (`/<key>/manifest.json` on the @Api client's request lines) — unredactable
 * without destroying log utility; release logs NOTHING, which is the control
 * for that class. `apikey`/`api_key`/`token` in queries are masked here:
 * `api_key` (TMDB) rides the loggerless @Validated client and `token` has no
 * live sender; both are covered defensively. The `apikey` HEADER (Supabase
 * auth) is redacted at the header level in [redactingLoggingInterceptor].
 */
private val CREDENTIAL_QUERY_PATTERNS = listOf("apikey", "api_key", "token").map { param ->
    Regex("([?&]" + param + "=)[^& ]*", RegexOption.IGNORE_CASE)
}

/**
 * Masks credential values in a line about to be logged, preserving the rest
 * of the URL so logs stay useful. Matches on exact parameter names rather
 * than a substring test, so non-credential params that merely contain "key"
 * (`with_keywords`) are left alone.
 */
internal fun redactCredentialQueryParams(message: String): String =
    CREDENTIAL_QUERY_PATTERNS.fold(message) { acc, pattern ->
        pattern.replace(acc) { match -> match.groupValues[1] + "REDACTED" }
    }

/** Trust-all X509TrustManager — one home for the @Api/@Download compatibility TLS. */
private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun trustAllSslContext(manager: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(manager), SecureRandom())
    }

private fun appVersionTag(): String = BuildConfig.VERSION_NAME.ifBlank { "dev" }

/** The app's own User-Agent, sent by [identityHeadersInterceptor] when a request names none. */
internal fun appUserAgent(): String = "Nuvio/${appVersionTag()}"

/**
 * User-Agent for stream requests to an AIOStreams addon (X1, QU5-1): AIOStreams adds its
 * per-stream `streamData` block only for a UA starting with "AIO". Still names this client
 * rather than posing as AIOStreams' own `AIOStreams/<v>`.
 */
internal fun aioStreamDataUserAgent(): String = "AIO-Nuvio/${appVersionTag()}"

/**
 * Identity headers every API-shaped client sends. One home (W2.1): the
 * User-Agent version tag and the localized Accept-Language fallback chain
 * must not drift between clients. A User-Agent the request already carries
 * (the AIOStreams stream fetch, X1) is kept, so only app constants may set
 * one on these clients, never addon-supplied headers.
 */
internal fun identityHeadersInterceptor(): Interceptor = Interceptor { chain ->
    val request = chain.request()
    chain.proceed(
        request.newBuilder()
            .apply { if (request.header("User-Agent") == null) header("User-Agent", appUserAgent()) }
            .header("Accept-Language", buildAcceptLanguageHeader())
            .build()
    )
}

/**
 * OkHttp would otherwise cache 4xx/5xx responses and keep serving a stale
 * failure after the host recovers. Network layer so it sees what was actually
 * served (post-redirect, post-cache).
 */
internal fun noStoreOnErrorNetworkInterceptor(): Interceptor = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (!response.isSuccessful) {
        response.newBuilder()
            .header("Cache-Control", "no-store")
            .build()
    } else {
        response
    }
}

/**
 * Max bytes accepted in a JSON response from an untrusted (addon) host,
 * counting DECOMPRESSED bytes. Sibling precedent: the LAN config servers'
 * [com.nuvio.tv.core.server.MAX_POST_BODY_BYTES] — same CWE-400 class, different
 * surface, deliberately not merged (each surface owns its cap fact).
 */
internal const val UNTRUSTED_JSON_BODY_CAP_BYTES = 2_000_000L

/**
 * Max array/object nesting accepted in a JSON body from an untrusted host (KT-JSON-03).
 * kotlinx.serialization 1.8.1 reads a `JsonElement` tree recursively and only goes
 * stackless for deep OBJECTS (JsonTreeReader, depth 200): deep ARRAYS overflow the stack,
 * and a StackOverflowError is an Error no fetch path catches. Addon JSON nests well under
 * 20 levels.
 */
internal const val UNTRUSTED_JSON_MAX_DEPTH = 64

/**
 * Byte-wise JSON nesting counter that ignores brackets inside strings. It sees what the
 * decoder sees only for UTF-8 or a single-byte ASCII superset (quote and backslash bytes
 * never occur inside a character): the guard rejects any other declared charset, and this
 * counter rejects 0x00/0xFE/0xFF, which never occur in UTF-8 JSON but begin every
 * UTF-16/32 byte-order mark ResponseBody.string() would otherwise decode by.
 */
internal class JsonNestingDepthCounter(private val maxDepth: Int) {
    private var depth = 0
    private var inString = false
    private var escaped = false

    /** Feeds one byte; false once the nesting exceeds [maxDepth] or the byte is not UTF-8 JSON. */
    fun accept(byte: Byte): Boolean {
        if (byte == NUL || byte == UTF_INVALID_FE || byte == UTF_INVALID_FF) return false
        val c = byte.toInt().toChar()
        when {
            inString -> when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '[' || c == '{' -> if (++depth > maxDepth) return false
            c == ']' || c == '}' -> depth = (depth - 1).coerceAtLeast(0)
        }
        return true
    }

    private companion object {
        const val NUL: Byte = 0x00
        const val UTF_INVALID_FE: Byte = 0xFE.toByte()
        const val UTF_INVALID_FF: Byte = 0xFF.toByte()
    }
}

/** Charsets whose bytes the depth counter reads like the decoder does (null = UTF-8 default). */
private val DEPTH_SAFE_CHARSETS = setOf(Charsets.UTF_8, Charsets.US_ASCII, Charsets.ISO_8859_1)

private fun isJsonContentType(mediaType: MediaType): Boolean {
    if (mediaType.type == "application") {
        return mediaType.subtype == "json" || mediaType.subtype.endsWith("+json")
    }
    return mediaType.type == "text" && mediaType.subtype == "json"
}

/**
 * W2.2 untrusted-response guard (application layer on the @Api client —
 * sees post-redirect, decompressed, cache-hit bodies):
 *
 * 1. Successful responses must be JSON-family; an HTML/whatever error page
 *    fails fast as an IOException instead of reaching the JSON decoder.
 * 2. EVERY response body — success or error — is wrapped in a counting
 *    source that throws past [maxBytes]. This is the error-body fix: Retrofit
 *    2.x heap-buffers non-2xx bodies UNCAPPED before app code runs
 *    (Utils.buffer), so a hostile host answering 500 + huge would balloon
 *    the heap before any catch. The cap aborts that mid-read.
 * 3. JSON-family (or untyped) bodies also throw past [maxDepth] nesting, before
 *    the decoder can recurse into it ([UNTRUSTED_JSON_MAX_DEPTH]).
 *
 * Null content-type or null body passes (204s, hosts that omit the header).
 */
internal fun untrustedResponseGuardInterceptor(
    maxBytes: Long = UNTRUSTED_JSON_BODY_CAP_BYTES,
    maxDepth: Int = UNTRUSTED_JSON_MAX_DEPTH
): Interceptor = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    val body = response.body
    val contentType = body.contentType()
    if (response.isSuccessful && contentType != null && !isJsonContentType(contentType)) {
        body.close()
        throw IOException("Rejected non-JSON response from untrusted host: $contentType")
    }
    val depthCounter = if (contentType == null || isJsonContentType(contentType)) {
        val charset = contentType?.charset()
        if (charset != null && charset !in DEPTH_SAFE_CHARSETS) {
            body.close()
            throw IOException("Rejected JSON in charset $charset from untrusted host")
        }
        JsonNestingDepthCounter(maxDepth)
    } else {
        null
    }
    val countingSource = object : ForwardingSource(body.source()) {
        var read = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n == -1L) return -1L
            read += n
            // Close the delegate before throwing: the caller may be Retrofit's
            // Utils.buffer on an error path with uncertain close discipline;
            // closing here bounds the held exchange deterministically instead
            // of waiting for the 30s callTimeout watchdog.
            if (read > maxBytes) {
                runCatching { body.close() }
                throw IOException("Response body exceeds ${maxBytes / 1_000_000}MB cap from untrusted host")
            }
            if (depthCounter != null) {
                val newBytes = Buffer()
                sink.copyTo(newBytes, sink.size - n, n)
                while (!newBytes.exhausted()) {
                    if (!depthCounter.accept(newBytes.readByte())) {
                        runCatching { body.close() }
                        throw IOException("Rejected JSON from untrusted host: nests deeper than $maxDepth levels or is not UTF-8")
                    }
                }
            }
            return n
        }
    }
    val bufferedCountingSource = countingSource.buffer()
    val cappedBody = object : ResponseBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = body.contentLength()
        // OkHttp's own bodies hand out ONE stable source; returning a fresh
        // buffer per call would interleave two private buffers over one stream.
        override fun source(): okio.BufferedSource = bufferedCountingSource
    }
    response.newBuilder()
        .body(cappedBody)
        .build()
}

/**
 * W2.4 logging discipline, one home: release logs NOTHING (addon config keys
 * ride URLs and the logging-interceptor 4.12 artifact has no redactQueryParams);
 * debug is capped at HEADERS (never BODY) with credential-bearing headers
 * redacted (Authorization, Cookie, Set-Cookie, Supabase `apikey`) and
 * credential query params masked by [redactCredentialQueryParams].
 */
internal fun httpLogLevel(isDebug: Boolean): HttpLoggingInterceptor.Level =
    if (isDebug) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE

internal fun redactingLoggingInterceptor(): HttpLoggingInterceptor =
    HttpLoggingInterceptor { message ->
        HttpLoggingInterceptor.Logger.DEFAULT.log(redactCredentialQueryParams(message))
    }.apply {
        level = httpLogLevel(BuildConfig.DEBUG)
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
        redactHeader("apikey")
    }

/**
 * Cache instrument: an APPLICATION interceptor sees cache hits; a network
 * interceptor never does. Answers whether http_cache serves anything and
 * whether addons declare anything cacheable. One line per API call, not per
 * media chunk. Installed in debug builds only since the ke8 CACHE_SIZES
 * readout replaced it as the release-side observability seam.
 */
private fun cacheInstrumentInterceptor(): Interceptor = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    val served = when {
        response.cacheResponse != null && response.networkResponse != null -> "validated"
        response.cacheResponse != null -> "cache"
        else -> "network"
    }
    Log.i(
        "NuvioCache",
        "HTTP_CACHE served=$served code=${response.code} " +
            "cc=${response.header("Cache-Control") ?: "none"} " +
            "age=${response.header("Age") ?: "none"} host=${chain.request().url.host}"
    )
    response
}

/** Retrofit requires a trailing '/'; blank BuildConfig URLs fall back to an inert host. */
internal fun baseUrlOr(raw: String, fallback: String): String = when {
    raw.isBlank() -> fallback
    raw.endsWith('/') -> raw
    else -> "$raw/"
}

private fun retrofit(baseUrl: String, client: OkHttpClient, converter: Converter.Factory): Retrofit =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(converter)
        .build()

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Wire-format JSON for every kotlinx-serialised Retrofit service (Moshi retirement,
     * phase M1+). Configured for Moshi-codegen parity: unknown keys ignored, absent
     * nullable fields read as null, defaults encoded. Deliberately strict elsewhere —
     * unknown enum values and unquoted scalars must throw so SafeApiCall.isDecodeGarbage
     * (W2.3) keeps classifying them as garbage. One home for every Retrofit service.
     */
    @Provides
    @Singleton
    fun provideWireJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** One home for the wire converter expression; every Retrofit builder below uses it. */
    @Provides
    @Singleton
    fun provideWireConverterFactory(json: Json): Converter.Factory =
        json.asConverterFactory("application/json".toMediaType())

    @Provides
    @Singleton
    @Api
    fun provideApiOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val trustAllManager = trustAllManager()
        val sslContext = trustAllSslContext(trustAllManager)
        // Trust-all is a compatibility measure for self-hosted addons and
        // stale TV trust stores; fixed commercial endpoints use @Validated
        // instead (main review F20).
        return OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024 * 1024)) // 50 MB disk cache
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // W2.1 API shape: many-small-hosts addon JSON must not hang a call
            // forever (a slow-drip server defeats per-read timeouts).
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(identityHeadersInterceptor())
            .addInterceptor(untrustedResponseGuardInterceptor())
            .apply { if (BuildConfig.DEBUG) addInterceptor(cacheInstrumentInterceptor()) }
            .addNetworkInterceptor(noStoreOnErrorNetworkInterceptor())
            .addInterceptor(redactingLoggingInterceptor())
            .build()
    }

    /**
     * Streaming downloads (subtitles) from untrusted hosts.
     * No callTimeout — it spans body reads. No response guard: consumers
     * stream to disk, and W2.2's JSON gate/cap would break binary payloads
     * (heap-buffering downloads must not ride [Api]).
     */
    @Provides
    @Singleton
    @Download
    fun provideDownloadOkHttpClient(): OkHttpClient {
        // Trust-all like @Api: subtitle URLs are addon-supplied and point back
        // at the same self-hosted-host class @Api's rationale covers (review
        // 2026-08-29-075635 major — dropping it silently broke self-signed
        // subtitle hosts).
        val trustAllManager = trustAllManager()
        val sslContext = trustAllSslContext(trustAllManager)
        return OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(identityHeadersInterceptor())
            .build()
    }

    /**
     * AuthManager's auth client for every backend (default and custom). Validated
     * TLS: credentials and tokens ride these calls, and the Supabase SDK already
     * reaches the same backend with default validation (SupabaseModule).
     */
    @Provides
    @Singleton
    @BackendAuth
    fun provideBackendAuthHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // W2.1 parity — bounds the whole call incl. the DNS phase.
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(identityHeadersInterceptor())
        .addInterceptor(redactingLoggingInterceptor())
        .build()

    /**
     * TLS-validating client for fixed commercial endpoints (TMDB et al.).
     * The shared client above disables certificate/hostname validation as a
     * compatibility measure for self-hosted addons and stale TV trust stores;
     * api.themoviedb.org has valid certs and no self-hosting variability, so it
     * should not inherit that bypass (main review F20).
     */
    @Provides
    @Singleton
    @Validated
    fun provideValidatedOkHttpClient(@ApplicationContext context: Context): OkHttpClient =
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .cache(Cache(File(context.cacheDir, "http_cache_validated"), 20L * 1024 * 1024))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // W2.1 parity — bounds the whole call incl. the DNS phase.
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(identityHeadersInterceptor())
            .addNetworkInterceptor(noStoreOnErrorNetworkInterceptor())
            .build()

    /**
     * Coil's callFactory (W2.1 @Image, relocated from NuvioApplication).
     * No OkHttp Cache on purpose: Coil 3.3.0 never reads one — its own disk
     * cache is the image cache. Short timeouts plus one socket-timeout retry
     * keep poster grids responsive on slow hosts; the dedicated dispatcher
     * (32/16) keeps image bursts from starving API calls.
     */
    @Provides
    @Singleton
    @Image
    fun provideImageOkHttpClient(): OkHttpClient {
        val imageDispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        }
        return OkHttpClient.Builder()
            .dispatcher(imageDispatcher)
            .dns(IPv4FirstDns())
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                try {
                    chain.proceed(chain.request())
                } catch (e: java.net.SocketTimeoutException) {
                    chain.withConnectTimeout(3, TimeUnit.SECONDS)
                        .withReadTimeout(4, TimeUnit.SECONDS)
                        .proceed(chain.request())
                }
            }
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(@Api okHttpClient: OkHttpClient, wireConverterFactory: Converter.Factory): Retrofit =
        retrofit("https://placeholder.nuvio.tv/", okHttpClient, wireConverterFactory)

    /** The validated client (shared pool and cache) plus the no-key guard (Spec 52 P1). */
    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbRetrofit(
        @Validated okHttpClient: OkHttpClient,
        wireConverterFactory: Converter.Factory
    ): Retrofit = retrofit(
        "https://api.themoviedb.org/3/",
        okHttpClient.newBuilder().addInterceptor(TmdbNoKeyInterceptor()).build(),
        wireConverterFactory
    )

    @Provides
    @Singleton
    fun provideAddonApi(retrofit: Retrofit): AddonApi =
        retrofit.create(AddonApi::class.java)

    @Provides
    @Singleton
    fun provideTmdbApi(@Named("tmdb") retrofit: Retrofit): TmdbApi =
        retrofit.create(TmdbApi::class.java)

    @Provides
    @Singleton
    @Named("parentalGuide")
    fun provideParentalGuideRetrofit(@Validated okHttpClient: OkHttpClient, wireConverterFactory: Converter.Factory): Retrofit =
        retrofit("https://api.tiffara.com/", okHttpClient, wireConverterFactory)

    @Provides
    @Singleton
    fun provideParentalGuideApi(@Named("parentalGuide") retrofit: Retrofit): ParentalGuideApi =
        retrofit.create(ParentalGuideApi::class.java)

    // --- Skip Intro APIs ---

    @Provides
    @Singleton
    @Named("introDb")
    fun provideIntroDbRetrofit(@Validated okHttpClient: OkHttpClient, wireConverterFactory: Converter.Factory): Retrofit =
        retrofit(baseUrlOr(BuildConfig.INTRODB_API_URL, "https://localhost/"), okHttpClient, wireConverterFactory)

    @Provides
    @Singleton
    fun provideIntroDbApi(@Named("introDb") retrofit: Retrofit): IntroDbApi =
        retrofit.create(IntroDbApi::class.java)

    // --- SeriesGraph API ---

    @Provides
    @Singleton
    @Named("seriesGraph")
    fun provideSeriesGraphRetrofit(@Validated okHttpClient: OkHttpClient, wireConverterFactory: Converter.Factory): Retrofit =
        retrofit(baseUrlOr(BuildConfig.IMDB_RATINGS_API_BASE_URL, "http://localhost/"), okHttpClient, wireConverterFactory)

    @Provides
    @Singleton
    fun provideSeriesGraphApi(@Named("seriesGraph") retrofit: Retrofit): SeriesGraphApi =
        retrofit.create(SeriesGraphApi::class.java)

    @Provides
    @Singleton
    @Named("imdbTapframe")
    fun provideImdbTapframeRetrofit(@Validated okHttpClient: OkHttpClient, wireConverterFactory: Converter.Factory): Retrofit =
        retrofit(baseUrlOr(BuildConfig.IMDB_TAPFRAME_API_BASE_URL, "http://localhost/"), okHttpClient, wireConverterFactory)

    @Provides
    @Singleton
    fun provideImdbTapframeApi(@Named("imdbTapframe") retrofit: Retrofit): ImdbTapframeApi =
        retrofit.create(ImdbTapframeApi::class.java)

    // --- GitHub Releases (update check, Spec 52 P6; a fixed commercial endpoint, like TMDB) ---

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(@Validated okHttpClient: OkHttpClient, wireConverterFactory: Converter.Factory): Retrofit =
        retrofit("https://api.github.com/", okHttpClient, wireConverterFactory)

    @Provides
    @Singleton
    fun provideGitHubReleaseApi(@Named("github") retrofit: Retrofit): GitHubReleaseApi =
        retrofit.create(GitHubReleaseApi::class.java)
}
