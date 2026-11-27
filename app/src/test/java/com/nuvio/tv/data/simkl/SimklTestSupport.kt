package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A recorded call to [FakeSimklEngine]. */
data class SentCall(val method: SimklHttpMethod, val url: String, val headers: Map<String, String>, val body: String?) {
    val path: String get() = url.toHttpUrl().encodedPath
    fun query(name: String): String? = url.toHttpUrl().queryParameter(name)
}

/** Answers from a queue (a [Throwable] entry is thrown as the transport failure) and records every call. */
class FakeSimklEngine : SimklHttpEngine {
    val calls = mutableListOf<SentCall>()
    private val answers = ArrayDeque<Any>()

    /** When set, every call suspends on it before answering (to hold a call in flight). */
    var hold: CompletableDeferred<Unit>? = null
    private var inFlight = 0
    var maxInFlight = 0
        private set

    fun reply(status: Int, body: String = "{}", headers: Map<String, String> = emptyMap()) {
        answers += SimklRawResponse(status, body, headers.mapKeys { it.key.lowercase() })
    }

    fun fail() {
        answers += IOException("connection reset")
    }

    /** Answers for one method + path, used before the queue (for calls whose order is not fixed). */
    private val routed = mutableMapOf<Pair<SimklHttpMethod, String>, ArrayDeque<SimklRawResponse>>()

    fun replyTo(method: SimklHttpMethod, path: String, status: Int, body: String = "{}") {
        routed.getOrPut(method to path) { ArrayDeque() } += SimklRawResponse(status, body, emptyMap())
    }

    override suspend fun execute(method: SimklHttpMethod, url: String, headers: Map<String, String>, body: String?): SimklRawResponse {
        calls += SentCall(method, url, headers, body)
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            hold?.await()
        } finally {
            inFlight--
        }
        routed[method to url.toHttpUrl().encodedPath]?.removeFirstOrNull()?.let { return it }
        return when (val next = answers.removeFirstOrNull() ?: error("no answer queued for $method $url")) {
            is Throwable -> throw next
            else -> next as SimklRawResponse
        }
    }
}

/** A manual clock; [sleep] advances it. */
class FakeClock(var now: Long = 1_763_000_000_000L) {
    val sleeps = mutableListOf<Long>()
    val sleep: suspend (Long) -> Unit = { sleeps += it; now += it }
}

val TEST_CONFIG = SimklApiConfiguration(clientId = { "client-v2" }, appVersion = "1.3.0-ke100")

fun accountStore(): TrackingAccountStore {
    val stores = mutableMapOf<Pair<Int, String>, TestPreferencesDataStore>()
    val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg()) { TestPreferencesDataStore() } }
    }
    val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    return TrackingAccountStore(factory, profileManager)
}

fun simklClient(engine: FakeSimklEngine, clock: FakeClock) =
    SimklClient(engine, TEST_CONFIG, monotonicMs = { clock.now }, sleep = clock.sleep, jitterMs = { 0L })

/** assertThrows whose block may suspend (inlined into the runTest body). */
inline fun <reified T : Throwable> assertFailsSuspend(block: () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw AssertionError("expected ${T::class.simpleName}, got ${failure::class.simpleName}", failure)
    }
    throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
}
