package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wave C S4 C4-1: eager/lazy split and the row-publish debounce table. */
class HomeCatalogScheduleTest {

    @Test
    fun `eager follows display order, skips collections, drops unknown keys, remainder in manifest order`() {
        val candidates = listOf("m1" to "M1", "m2" to "M2", "m3" to "M3", "m4" to "M4")
        val (eager, lazy) = splitEagerLazy(
            candidates = candidates,
            displayOrderKeys = listOf("m3", "collection_x", "gone", "m1"),
            eagerCount = 3
        )
        assertEquals(listOf("M3", "M1", "M2"), eager)
        assertEquals(listOf("M4"), lazy)
    }

    @Test
    fun `split at eagerCount, fewer candidates are all eager`() {
        val candidates = (1..10).map { "k$it" to it }
        val (eager, lazy) = splitEagerLazy(candidates, emptyList(), eagerCount = 8)
        assertEquals((1..8).toList(), eager)
        assertEquals(listOf(9, 10), lazy)
        val (smallEager, smallLazy) = splitEagerLazy(candidates.take(2), listOf("k2"), eagerCount = 8)
        assertEquals(listOf(2, 1), smallEager)
        assertEquals(emptyList<Int>(), smallLazy)
    }

    @Test
    fun `debounce table`() {
        assertEquals(150L, catalogUpdateDebounceMs(firstRender = true, pending = 20))
        assertEquals(300L, catalogUpdateDebounceMs(firstRender = false, pending = 9))
        assertEquals(250L, catalogUpdateDebounceMs(firstRender = false, pending = 8))
        assertEquals(250L, catalogUpdateDebounceMs(firstRender = false, pending = 4))
        assertEquals(200L, catalogUpdateDebounceMs(firstRender = false, pending = 3))
        assertEquals(200L, catalogUpdateDebounceMs(firstRender = false, pending = 1))
        assertEquals(80L, catalogUpdateDebounceMs(firstRender = false, pending = 0))
    }
}
