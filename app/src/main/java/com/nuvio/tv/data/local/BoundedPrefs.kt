package com.nuvio.tv.data.local

/**
 * Shared bound policy for preference-per-entry caches (the G3 unbounded-growth
 * debt). Each owning store groups its keys into logical entries, derives a
 * per-entry timestamp in its own format (JSON field or sidecar key), and calls
 * [planEviction] inside its existing edit{} block on the write path — the
 * stores keep their public APIs and own their formats; this object owns only
 * the eviction policy.
 */
internal object BoundedPrefs {
    const val DEFAULT_MAX_ENTRIES = 500

    /**
     * Ids to remove so at most [cap] entries remain. Eviction is
     * oldest-timestamp-first; missing or non-positive timestamps (legacy or
     * unparseable entries) sort oldest so unknown age evicts first. Equal
     * timestamps break ties by id for determinism.
     */
    internal fun planEviction(timestamps: Map<String, Long>, cap: Int = DEFAULT_MAX_ENTRIES): Set<String> {
        if (timestamps.size <= cap) return emptySet()
        return timestamps.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .take(timestamps.size - cap)
            .mapTo(mutableSetOf()) { it.key }
    }
}
