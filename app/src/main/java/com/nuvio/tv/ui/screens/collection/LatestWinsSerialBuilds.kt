package com.nuvio.tv.ui.screens.collection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Latest-wins scheduling for background builds that share non-thread-safe state (B19).
 *
 * Each [launch] cancels the previous build. Builds run on a single-parallelism view of the base
 * dispatcher, so no two builds execute at the same time (a cancelled build that is still inside
 * non-suspending work keeps the worker until it returns). Only the most recently launched build
 * may publish: the identity check runs under the same lock that installs a newer build, so a
 * superseded build never publishes and an older snapshot cannot overwrite a newer one.
 */
internal class LatestWinsSerialBuilds(
    private val scope: CoroutineScope,
    baseDispatcher: CoroutineDispatcher
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = baseDispatcher.limitedParallelism(1)
    private val lock = Any()
    private var job: Job? = null

    /**
     * [build] returning null publishes nothing. [publish] runs under the internal lock, so it
     * must be non-suspending and quick. Callable from any thread.
     */
    fun <T : Any> launch(build: suspend () -> T?, publish: (T) -> Unit): Job = synchronized(lock) {
        job?.cancel()
        // LAZY so `job` is installed before the body can reach the identity check, even on an
        // immediate dispatcher.
        val next = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val result = build() ?: return@launch
            ensureActive()
            synchronized(lock) {
                if (job === coroutineContext[Job]) publish(result)
            }
        }
        job = next
        next.start()
        next
    }
}
