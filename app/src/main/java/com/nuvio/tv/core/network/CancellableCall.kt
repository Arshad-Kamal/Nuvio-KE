package com.nuvio.tv.core.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/**
 * Runs the blocking [Call.execute] + [block] so that cancelling the calling coroutine
 * (withTimeout, scope cancel, ViewModel clear) cancels the call: OkHttp's blocking
 * connect/read ignore coroutine cancellation and thread interrupts, but [Call.cancel]
 * aborts them with an IOException, which is rethrown as the CancellationException when
 * the coroutine was cancelled. UNDISPATCHED so the canceller is armed before execute()
 * starts. Call from a blocking-friendly dispatcher (IO); [block] runs there too.
 */
internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T {
    val call = this
    return coroutineScope {
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { block(it) }
        } catch (e: IOException) {
            ensureActive()
            throw e
        } finally {
            canceller.cancel()
        }
    }
}
