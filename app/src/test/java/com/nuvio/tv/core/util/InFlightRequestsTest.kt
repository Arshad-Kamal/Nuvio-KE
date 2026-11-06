package com.nuvio.tv.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the shared in-flight request helper used by MetaRepositoryImpl and the TMDB
 * services (L8-21/54/70): one started request per key, no stale completed entry, and
 * a finishing request never evicting a newer one.
 */
class InFlightRequestsTest {

    @Test
    fun `concurrent callers share one started request`() = runTest {
        val map = ConcurrentHashMap<String, Deferred<Int>>()
        val gate = CompletableDeferred<Unit>()
        var starts = 0

        val first = map.getOrStartShared(this, "k") { starts++; gate.await(); 1 }
        val second = map.getOrStartShared(this, "k") { starts++; 2 }
        gate.complete(Unit)

        assertSame(first, second)
        assertEquals(1, second.await())
        assertEquals(1, starts)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `an instantly completing request leaves no stale entry`() = runTest {
        val map = ConcurrentHashMap<String, Deferred<Int>>()

        val result = map.getOrStartShared(this, "k") { 7 }.await()

        assertEquals(7, result)
        assertNull(map["k"])
    }

    @Test
    fun `a finishing request does not evict a newer one under the same key`() = runTest {
        val map = ConcurrentHashMap<String, Deferred<Int>>()
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        val old = map.getOrStartShared(this, "k") { oldGate.await(); 1 }
        map.clear() // clearCache() while the old request is still running
        val newer = map.getOrStartShared(this, "k") { newGate.await(); 2 }

        oldGate.complete(Unit)
        assertEquals(1, old.await())

        assertSame(newer, map["k"])
        newGate.complete(Unit)
        assertEquals(2, newer.await())
        assertTrue(map.isEmpty())
    }
}
