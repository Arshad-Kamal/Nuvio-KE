package com.nuvio.tv.ui.components

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the [RowFocusRequesterRegistry.restorerTarget] resolution policy and the
 * requester map lifecycle (G6 consolidation home for the focus-restoring-row
 * idiom).
 */
class RowFocusRequesterRegistryTest {

    private fun registryOf(vararg keys: String): Pair<RowFocusRequesterRegistry, Map<String, FocusRequester>> {
        val registry = RowFocusRequesterRegistry()
        val created = keys.associateWith { registry.requesterFor(it) }
        return registry to created
    }

    @Test
    fun `requesterFor caches one instance per key`() {
        val (registry, created) = registryOf("a", "b")
        assertSame(created["a"], registry.requesterFor("a"))
        assertNotSame(created["a"], registry.requesterFor("b"))
    }

    @Test
    fun `retainKeys drops only stale requesters`() {
        val (registry, created) = registryOf("a", "b", "c")
        registry.retainKeys(setOf("a", "c"))
        assertSame(created["a"], registry.requesterFor("a"))
        assertNotSame(created["b"], registry.requesterFor("b"))
        assertSame(created["c"], registry.requesterFor("c"))
    }

    @Test
    fun `restorerTarget resolves preferred index when visible`() {
        val (registry, created) = registryOf("k0", "k1", "k2")
        val target = registry.restorerTarget(
            visibleIndices = listOf(1, 2),
            keyAt = { idx -> "k$idx" },
            preferredIndex = 2,
            fallbackToFirstVisible = true
        )
        assertSame(created["k2"], target)
    }

    @Test
    fun `restorerTarget falls back to first visible when preferred not visible`() {
        val (registry, created) = registryOf("k0", "k1", "k2")
        val target = registry.restorerTarget(
            visibleIndices = listOf(1, 2),
            keyAt = { idx -> "k$idx" },
            preferredIndex = 0,
            fallbackToFirstVisible = true
        )
        assertSame(created["k1"], target)
    }

    @Test
    fun `strict policy returns Default when preferred not visible`() {
        val (registry, _) = registryOf("k0", "k1")
        val target = registry.restorerTarget(
            visibleIndices = listOf(1),
            keyAt = { idx -> "k$idx" },
            preferredIndex = 0,
            fallbackToFirstVisible = false
        )
        assertSame(FocusRequester.Default, target)
    }

    @Test
    fun `strict policy resolves preferred zero when visible`() {
        val (registry, created) = registryOf("k0", "k1")
        val target = registry.restorerTarget(
            visibleIndices = listOf(0),
            keyAt = { idx -> "k$idx" },
            preferredIndex = 0,
            fallbackToFirstVisible = false
        )
        assertSame(created["k0"], target)
    }

    @Test
    fun `negative or null preferred index never resolves`() {
        val (registry, _) = registryOf("k0", "k1")
        listOf(null, -1).forEach { preferred ->
            val strict = registry.restorerTarget(
                visibleIndices = listOf(0),
                keyAt = { idx -> "k$idx" },
                preferredIndex = preferred,
                fallbackToFirstVisible = false
            )
            assertSame("preferred=$preferred", FocusRequester.Default, strict)
        }
    }

    @Test
    fun `empty visible list returns Default under both policies`() {
        val (registry, _) = registryOf("k0")
        assertSame(
            FocusRequester.Default,
            registry.restorerTarget(emptyList(), { "k$it" }, 0, fallbackToFirstVisible = true)
        )
        assertSame(
            FocusRequester.Default,
            registry.restorerTarget(emptyList(), { "k$it" }, 0, fallbackToFirstVisible = false)
        )
    }

    @Test
    fun `keyAt returning null falls to Default instead of crashing`() {
        val (registry, _) = registryOf("k0")
        val target = registry.restorerTarget(
            visibleIndices = listOf(0),
            keyAt = { null }, // index maps to no item (e.g. divider or trailing card)
            preferredIndex = 0,
            fallbackToFirstVisible = true
        )
        assertSame(FocusRequester.Default, target)
    }

    @Test
    fun `restorerTarget creates requester lazily for composed item`() {
        val (registry, _) = registryOf()
        val target = registry.restorerTarget(
            visibleIndices = listOf(0),
            keyAt = { "fresh" },
            preferredIndex = 0,
            fallbackToFirstVisible = true
        )
        assertNotSame(FocusRequester.Default, target)
        // The created instance is the one composition will attach.
        assertSame(target, registry.requesterFor("fresh"))
    }
}
