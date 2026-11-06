package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Starts a debounced prefetch for each new [targets] value, but only while [onTop] (upstream-sync
 * T27). Leaving the screen cancels a pending start; a target that changed while away starts on
 * return; one already started does not start again. A null target clears that memory, so a later
 * return of the same target starts again (as plain distinctUntilChanged would).
 */
internal suspend fun <T : Any> collectPrefetchTargetsWhileOnTop(
    targets: Flow<T?>,
    onTop: Flow<Boolean>,
    debounceMs: Long,
    start: suspend (T) -> Unit
) {
    var started: T? = null
    combine(targets.distinctUntilChanged(), onTop.distinctUntilChanged(), ::Pair)
        .collectLatest { (target, isOnTop) ->
            if (target == null) {
                started = null
                return@collectLatest
            }
            if (!isOnTop || target == started) return@collectLatest
            delay(debounceMs)
            start(target)
            started = target
        }
}
