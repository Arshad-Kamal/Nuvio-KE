package com.nuvio.tv.ui.screens.home

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Adjacent-card meta preloads (upstream-sync T28 P29, upstream `99c948c80`). A settled card
 * preloads both neighbours; one shared job slot let the second request cancel the first, so the
 * card in the travel direction was never preloaded. Per-id jobs with a two-job budget: a repeat
 * request for a running id shares its work, and a third id evicts the oldest. Main-thread only
 * (called from the focus callbacks).
 */
internal class AdjacentPrefetchJobs {
    private val jobs = LinkedHashMap<String, Job>()

    fun launch(
        scope: CoroutineScope,
        id: String,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ) {
        jobs.entries.removeAll { !it.value.isActive }
        // Touch a still-relevant neighbour so rapid navigation evicts stale work first.
        jobs.remove(id)?.let { jobs[id] = it; return }
        if (jobs.size >= 2) {
            val oldest = jobs.entries.first()
            oldest.value.cancel()
            jobs.remove(oldest.key)
        }
        jobs[id] = scope.launch(context, block = block)
    }
}
