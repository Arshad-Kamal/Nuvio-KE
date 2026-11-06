package com.nuvio.tv.core.server

import java.util.concurrent.ConcurrentHashMap

/** At most one web-config proposal is ever PENDING, so 50 records is far
 *  beyond any real session's needs. */
internal const val MAX_PENDING_CHANGES = 50

/**
 * Bounded store for AddonConfigServer's proposal records, shared between
 * NanoHTTPD threads and the UI. [put] with [rejectPending] rejects every
 * currently-PENDING record as part of the same lock-held insert, so two
 * overlapping POSTs can never leave two PENDING records; once [cap] is
 * exceeded the oldest inserted records are dropped, which therefore only ever
 * discards terminal (confirmed/rejected) records whose /api/status poll has
 * long since concluded.
 */
internal class PendingChangeStore<V : Any>(private val cap: Int = MAX_PENDING_CHANGES) {
    private val lock = Any()
    private val map = ConcurrentHashMap<String, V>()
    private val insertionOrder = ArrayDeque<String>()

    operator fun get(id: String): V? = map[id]

    fun put(id: String, value: V, rejectPending: ((V) -> Unit)? = null) {
        synchronized(lock) {
            if (rejectPending != null) {
                map.values.forEach(rejectPending)
            }
            map[id] = value
            insertionOrder.remove(id)
            insertionOrder.addLast(id)
            while (insertionOrder.size > cap) {
                map.remove(insertionOrder.removeFirst())
            }
        }
    }
}
