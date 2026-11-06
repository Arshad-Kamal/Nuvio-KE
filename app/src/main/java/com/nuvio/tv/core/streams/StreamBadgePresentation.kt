package com.nuvio.tv.core.streams

import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Badge regexes come from packs the owner imports. StreamBadgeRulesParser caps each pattern at
 * MAX_BADGE_PATTERN_LENGTH, which bounds pattern size, not backtracking: matching runs in
 * Android's native ICU regex engine on a String copy and cannot be interrupted once started (no
 * coroutine timeout or deadline CharSequence reaches it), so a pathological pattern costs the
 * caller its full match time.
 */
@Singleton
class StreamBadgePresentation @Inject constructor(
    private val dataStore: StreamBadgeSettingsDataStore
) {
    /**
     * One published snapshot: [rules] is the last instance seen (identity fast path),
     * [normalized] its normalized form, and [pool] the canonical badge per dedupe key built from
     * [filters]. Read-only after build, so concurrent apply() callers share it safely.
     */
    private class BadgeFilterSnapshot(
        val rules: StreamBadgeRules,
        val normalized: StreamBadgeRules,
        val filters: List<CompiledStreamBadgeFilter>,
        val pool: Map<String, StreamBadge>
    )

    private val snapshot = AtomicReference<BadgeFilterSnapshot?>()

    suspend fun apply(groups: List<AddonStreams>): List<AddonStreams> {
        return withContext(Dispatchers.Default) {
            apply(groups, dataStore.settings.first().rules)
        }
    }

    fun apply(groups: List<AddonStreams>, rules: StreamBadgeRules): List<AddonStreams> {
        val current = snapshotFor(rules)
        if (current.filters.isEmpty()) return groups
        return groups.map { group ->
            group.copy(
                streams = group.streams.map { stream ->
                    val matchedBadges = StreamBadgeMatcher.matchedBadges(stream, current.filters, current.pool)
                    if (matchedBadges.isEmpty()) {
                        stream
                    } else {
                        stream.copy(badges = mergeBadges(stream.badges, matchedBadges, current.pool))
                    }
                }
            )
        }
    }

    suspend fun badgesFor(stream: Stream): List<StreamBadge> {
        val current = snapshotFor(dataStore.settings.first().rules)
        if (current.filters.isEmpty()) return emptyList()
        return StreamBadgeMatcher.matchedBadges(stream, current.filters, current.pool)
    }

    private fun snapshotFor(rules: StreamBadgeRules): BadgeFilterSnapshot {
        val cached = snapshot.get()
        if (cached != null && cached.rules === rules) return cached
        val normalized = rules.normalized()
        if (cached != null && cached.normalized == normalized) {
            // Same rules in a new instance: remember it so the next call takes the identity path.
            return BadgeFilterSnapshot(rules, normalized, cached.filters, cached.pool)
                .also { snapshot.set(it) }
        }
        val compiled = StreamBadgeMatcher.compile(normalized)
        // Canonical instance per dedupe key so all matching reuses the compiled badges.
        val pool = HashMap<String, StreamBadge>(compiled.size * 2)
        compiled.forEach { filter -> pool.putIfAbsent(filter.badge.dedupeKey(), filter.badge) }
        return BadgeFilterSnapshot(rules, normalized, compiled, pool).also { snapshot.set(it) }
    }

    private fun mergeBadges(
        existing: List<StreamBadge>,
        matched: List<StreamBadge>,
        pool: Map<String, StreamBadge>
    ): List<StreamBadge> {
        // Fast path: no existing badges, just return matched (already pooled).
        if (existing.isEmpty()) return matched

        val capacity = existing.size + matched.size
        val merged = LinkedHashMap<String, StreamBadge>(capacity)
        existing.forEach { badge ->
            val key = badge.dedupeKey()
            if (key !in merged) merged[key] = pool[key] ?: badge
        }
        matched.forEach { badge ->
            val key = badge.dedupeKey()
            if (key !in merged) merged[key] = badge // already pooled
        }
        return merged.values.toList()
    }
}
