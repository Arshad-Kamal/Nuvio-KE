package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A recorded call to [FakeMdbListEngine]. */
data class SentCall(val method: MdbListHttpMethod, val url: String, val headers: Map<String, String>, val body: String?) {
    val path: String get() = url.toHttpUrl().encodedPath
    fun query(name: String): String? = url.toHttpUrl().queryParameter(name)
}

/** Answers from a queue (a [Throwable] entry is thrown as the transport failure) and records every call. */
class FakeMdbListEngine : MdbListHttpEngine {
    val calls = mutableListOf<SentCall>()
    private val answers = ArrayDeque<Any>()

    fun reply(status: Int, body: String = "{}", headers: Map<String, String> = emptyMap()) {
        answers += MdbListRawResponse(status, body, headers.mapKeys { it.key.lowercase() })
    }

    /** A dropped connection whose message carries the URL, as OkHttp's sometimes do. */
    fun fail() {
        answers += IOException("unexpected end of stream on https://api.mdblist.com/user?apikey=secret-key")
    }

    /** Runs inside each call before it answers (to hold a call in flight or change state mid-call). */
    var onCall: suspend () -> Unit = {}

    /** Answers for one method + path, used before the queue (for calls whose order is not fixed). */
    private val routed = mutableMapOf<Pair<MdbListHttpMethod, String>, ArrayDeque<MdbListRawResponse>>()

    fun replyTo(method: MdbListHttpMethod, path: String, status: Int, body: String = "{}") {
        routed.getOrPut(method to path) { ArrayDeque() } += MdbListRawResponse(status, body, emptyMap())
    }

    /**
     * The answer to a watchlist read nothing was routed for (S3a: every first sync pulls the watchlist); null = take it
     * from the queue like any other call.
     */
    var watchlistDefault: String? = "{}"

    override suspend fun execute(method: MdbListHttpMethod, url: String, headers: Map<String, String>, body: String?): MdbListRawResponse {
        calls += SentCall(method, url, headers, body)
        onCall()
        val path = url.toHttpUrl().encodedPath
        routed[method to path]?.removeFirstOrNull()?.let { return it }
        if (method == MdbListHttpMethod.GET && path == "/watchlist/items") watchlistDefault?.let { return MdbListRawResponse(200, it, emptyMap()) }
        return when (val next = answers.removeFirstOrNull() ?: error("no answer queued for $method")) {
            is Throwable -> throw next
            else -> next as MdbListRawResponse
        }
    }
}

/** A manual clock; [sleep] advances it. */
class FakeClock(var now: Long = 1_763_000_000_000L) {
    val sleeps = mutableListOf<Long>()
    val sleep: suspend (Long) -> Unit = { sleeps += it; now += it }
}

val TEST_CONFIG = MdbListApiConfiguration(appVersion = "1.3.0-ke100")

const val DAILY_LIMIT_BODY = """{"error":"Daily API limit exceeded!"}"""
const val RATE_LIMIT_BODY = """{"error":"API rate limit exceeded!"}"""

fun accountStore(): TrackingAccountStore {
    val stores = mutableMapOf<Pair<Int, String>, TestPreferencesDataStore>()
    val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg()) { TestPreferencesDataStore() } }
    }
    val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    return TrackingAccountStore(factory, profileManager)
}

fun mdbListClient(engine: FakeMdbListEngine, clock: FakeClock) =
    MdbListClient(engine, TEST_CONFIG, sleep = clock.sleep, jitterMs = { 0L })

/** The exception [block] throws, which must be a [T]. */
suspend inline fun <reified T : Throwable> failsWith(block: suspend () -> Unit): T {
    val thrown = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }
    return thrown as? T ?: throw AssertionError("expected ${T::class.simpleName}, got $thrown")
}

/** Writes as the references come (no TVDB numbering step). */
val IDENTITY_NUMBERING: MdbListNumbering = { _, reference, _ -> MdbListNumbered(reference) }

/** A real MDBList reply from `src/test/resources/mdblist-tvdbmap/` (see its README). */
fun tvdbMapFixture(name: String): String =
    FakeMdbListEngine::class.java.classLoader!!.getResource("mdblist-tvdbmap/$name")!!.readText()

fun tvdbMapStore(file: File, scope: CoroutineScope) = MdbListTvdbMapStore(file, Dispatchers.Unconfined, scope)

/** Detective Conan: IMDb, TMDb ids; TVDB S34E35 is TMDb S1E1221 in the fixture map. */
const val CONAN_IMDB = "tt0131179"
const val CONAN_TMDB = 30983L
