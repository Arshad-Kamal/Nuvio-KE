package com.nuvio.tv.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * Returns the request already in flight under [key], or starts [block] in [scope] as
 * the one shared request for it.
 *
 * The async is created LAZY and started only after it wins the insert, so concurrent
 * callers never fire duplicate requests and the body can never finish (and clean up)
 * before it is in the map. Cleanup removes the entry only while it still maps to this
 * Deferred, so a finishing request never evicts a newer one under the same key.
 */
internal fun <K : Any, V> ConcurrentHashMap<K, Deferred<V>>.getOrStartShared(
    scope: CoroutineScope,
    key: K,
    block: suspend CoroutineScope.() -> V
): Deferred<V> {
    lateinit var created: Deferred<V>
    created = scope.async(start = CoroutineStart.LAZY) {
        try {
            block()
        } finally {
            remove(key, created)
        }
    }
    putIfAbsent(key, created)?.let { existing ->
        created.cancel()
        return existing
    }
    created.start()
    return created
}
