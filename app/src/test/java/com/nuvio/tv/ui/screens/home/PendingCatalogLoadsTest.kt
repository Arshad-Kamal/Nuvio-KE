package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wave C S4 C4-2: the per-generation pending catalog-load count. */
class PendingCatalogLoadsTest {

    @Test
    fun `generation reset, eager count, top-ups and settles drain to zero`() {
        val pending = PendingCatalogLoads()
        pending.add(3) // a previous generation still in flight
        pending.reset() // generation bump: an early-return reload leaves nothing behind
        assertEquals(0, pending.count)
        pending.reset(8) // eager dispatch
        pending.add(2) // hero top-up
        pending.add() // scroll-requested or sweep load
        assertEquals(11, pending.count)
        repeat(11) { pending.settle() }
        assertEquals(0, pending.count)
    }

    @Test
    fun `settle never goes below zero`() {
        val pending = PendingCatalogLoads()
        pending.settle()
        assertEquals(0, pending.count)
    }
}
