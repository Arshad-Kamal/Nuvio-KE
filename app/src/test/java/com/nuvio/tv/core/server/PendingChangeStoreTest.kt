package com.nuvio.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PendingChangeStoreTest {

    private class Change(var status: String = "PENDING")

    @Test
    fun `get returns stored values by id`() {
        val store = PendingChangeStore<Change>(cap = 3)
        store.put("a", Change())
        assertNotNull(store["a"])
        assertNull(store["missing"])
    }

    @Test
    fun `overflow evicts the oldest inserted ids first`() {
        val store = PendingChangeStore<Change>(cap = 3)
        store.put("a", Change())
        store.put("b", Change())
        store.put("c", Change())
        store.put("d", Change())
        assertNull(store["a"])
        assertNotNull(store["b"])
        assertNotNull(store["c"])
        assertNotNull(store["d"])
    }

    @Test
    fun `re-putting an id refreshes its position`() {
        val store = PendingChangeStore<Change>(cap = 3)
        store.put("a", Change())
        store.put("b", Change())
        store.put("c", Change())
        store.put("a", Change()) // refresh a; b is now the oldest
        store.put("d", Change())
        assertNull(store["b"])
        assertNotNull(store["a"])
        assertNotNull(store["c"])
        assertNotNull(store["d"])
    }

    @Test
    fun `put with rejectPending rejects earlier pending records atomically`() {
        val store = PendingChangeStore<Change>(cap = 3)
        store.put("a", Change())
        store.put("b", Change(status = "CONFIRMED"))
        store.put("c", Change()) { record ->
            if (record.status == "PENDING") record.status = "REJECTED"
        }
        assertEquals("REJECTED", store["a"]?.status) // pending before the insert: rejected
        assertEquals("CONFIRMED", store["b"]?.status) // terminal records are untouched
        assertEquals("PENDING", store["c"]?.status) // the new record stays pending
    }
}
