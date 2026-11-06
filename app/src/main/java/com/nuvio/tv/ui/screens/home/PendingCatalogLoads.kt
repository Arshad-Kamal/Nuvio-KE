package com.nuvio.tv.ui.screens.home

/**
 * Catalog loads dispatched in the current Home catalog generation and not yet settled
 * (eager + hero top-up + scroll-requested + sweep loads share it). Main-confined like every
 * writer. Reset at each generation bump, so an early-return reload cannot leave the previous
 * generation's count behind. Read by the row-publish debounce and the sweep's yield check.
 */
internal class PendingCatalogLoads {
    var count: Int = 0
        private set

    fun reset(n: Int = 0) {
        count = n
    }

    fun add(n: Int = 1) {
        count += n
    }

    /** One load settled. Each job settles at most once per generation, so the floor is defensive. */
    fun settle() {
        count = (count - 1).coerceAtLeast(0)
    }
}
