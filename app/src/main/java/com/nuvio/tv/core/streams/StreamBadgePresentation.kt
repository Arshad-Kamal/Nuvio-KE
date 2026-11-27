package com.nuvio.tv.core.streams

import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Badge regexes come from packs the owner imports, with no per-pattern length or filter-count cap
 * (Spec 130 D1). Matching runs in
 * Android's native ICU regex engine on a String copy and cannot be interrupted once started (no
 * coroutine timeout or deadline CharSequence reaches it), so a pathological pattern costs the
 * caller its full match time.
 */
@Singleton
class StreamBadgePresentation @Inject constructor(
    private val dataStore: StreamBadgeSettingsDataStore,
    private val screensaverController: ScreensaverController
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

    /**
     * Serializes snapshot builds: a big pack compiles for seconds on a TV (Spec 130: FR Badges'
     * 219 patterns take ~0.7 s in desktop ICU), and concurrent first callers (stream list chunks,
     * the prefetch) would each compile it. The identity fast path stays lock-free.
     */
    private val buildLock = Any()

    /**
     * Spec 130 round 2 warm: compiles an imported pack ahead of its first use, so the first
     * Details page does not pay for it. It starts [WARM_DELAY_MS] after this singleton is built
     * (Home's ViewModel builds it, so Home's first load is past), runs only while nothing plays
     * ([ScreensaverController.playbackActive]: the player's playing or buffering), on one
     * lowest-priority thread, and stops within one pattern when playback starts or the rules
     * change; it retries once playback ends. It builds outside [buildLock] and publishes only
     * when no other build landed meanwhile, so a foreground caller never waits on it.
     */
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warmDispatcher by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "badge-warm").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()
    }

    init {
        warmScope.launch {
            delay(WARM_DELAY_MS)
            try {
                dataStore.settings
                    .map { it.rules }
                    // The store returns one instance per stored JSON, so identity marks a change.
                    .distinctUntilChanged { old, new -> old === new }
                    .collectLatest { rules -> warm(rules) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A settings read failed (disk, migration): no warm this process; first use compiles.
                android.util.Log.w(TAG, "Badge warm stopped: ${e::class.java.simpleName}")
            }
        }
    }

    /** The warm's step for one rules value; internal so tests drive it without the start delay. */
    internal suspend fun warm(rules: StreamBadgeRules) {
        if (!rules.hasImport) return
        try {
            while (true) {
                screensaverController.playbackActive.first { !it }
                val done = withContext(warmDispatcher) {
                    val context = coroutineContext
                    buildIfAbsent(rules) { context.isActive && !screensaverController.playbackActive.value }
                }
                if (done) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An optional warm: the first use compiles instead.
            android.util.Log.w(TAG, "Badge warm failed: ${e::class.java.simpleName}")
        }
    }

    /**
     * True when the warm is done: a snapshot for [rules] exists or was published, or another
     * build landed meanwhile (that caller's snapshot stands). False when playback or
     * cancellation stopped the compile, so the warm retries.
     */
    private fun buildIfAbsent(rules: StreamBadgeRules, keepGoing: () -> Boolean): Boolean {
        val seen = snapshot.get()
        val normalized = rules.normalized()
        if (seen != null && seen.normalized == normalized) return true
        val compiled = StreamBadgeMatcher.compileWhile(normalized) { keepGoing() && snapshot.get() === seen }
            ?: return snapshot.get() !== seen
        snapshot.compareAndSet(seen, newSnapshot(rules, normalized, compiled))
        return true
    }

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
        snapshot.get()?.let { if (it.rules === rules) return it }
        return synchronized(buildLock) { buildSnapshot(rules) }
    }

    private fun buildSnapshot(rules: StreamBadgeRules): BadgeFilterSnapshot {
        val cached = snapshot.get()
        if (cached != null && cached.rules === rules) return cached
        val normalized = rules.normalized()
        if (cached != null && cached.normalized == normalized) {
            // Same rules in a new instance: remember it so the next call takes the identity path.
            return BadgeFilterSnapshot(rules, normalized, cached.filters, cached.pool)
                .also { snapshot.set(it) }
        }
        return newSnapshot(rules, normalized, StreamBadgeMatcher.compile(normalized)).also { snapshot.set(it) }
    }

    private fun newSnapshot(
        rules: StreamBadgeRules,
        normalized: StreamBadgeRules,
        compiled: List<CompiledStreamBadgeFilter>
    ): BadgeFilterSnapshot {
        // Canonical instance per dedupe key so all matching reuses the compiled badges.
        val pool = HashMap<String, StreamBadge>(compiled.size * 2)
        compiled.forEach { filter -> pool.putIfAbsent(filter.badge.dedupeKey(), filter.badge) }
        return BadgeFilterSnapshot(rules, normalized, compiled, pool)
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

    private companion object {
        const val TAG = "StreamBadgePresentation"

        /** Past Home's first load; a Details page opened sooner compiles on its own, as before. */
        const val WARM_DELAY_MS = 30_000L
    }
}
