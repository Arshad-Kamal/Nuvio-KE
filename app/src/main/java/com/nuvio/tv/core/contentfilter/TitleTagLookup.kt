package com.nuvio.tv.core.contentfilter

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 117 P4: fetches each title's TMDB tags into [TitleTagCache] through one prioritised queue
 * (NOW > PAGE > DRAW > BACKGROUND, FIFO within a level), [WORKERS] at a time. No per-caller tracking:
 * callers read [state] and redraw on [changes]; gates wait with [awaitDecided].
 *
 * Failure rules: a failed title leaves the queue and is not re-queued for 5 min; 5 IO failures in a row
 * open a 60 s circuit breaker (gates stop waiting); a 429 pauses every worker for its Retry-After; a 401
 * means "no key" (words + genres only, nothing queued) until the key changes or a re-probe every 15 min
 * succeeds. With no key at all nothing is fetched or held.
 */
@Singleton
class TitleTagLookup internal constructor(
    private val source: TitleTagSource,
    private val keyState: StateFlow<TmdbKeyState?>,
    private val cache: TitleTagCache,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    workerCount: Int = WORKERS,
    private val queueCap: Int = QUEUE_CAP
) {
    @Inject
    constructor(api: TmdbApi, keys: TmdbApiKeyProvider, cache: TitleTagCache) : this(
        TmdbTitleTagSource(api),
        keys.state,
        cache,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        SystemClock::elapsedRealtime
    )

    private val lock = Any()

    // Guarded by lock.
    private val levels = Array(TagPriority.entries.size) { LinkedHashSet<TitleRef>() }
    private val queued = HashMap<TitleRef, TagPriority>()
    private val inFlight = HashMap<TitleRef, TagPriority>()
    private val failedUntil = HashMap<TitleRef, Long>()
    private var consecutiveIoFailures = 0
    private var backoffJob: Job? = null
    private var reprobeJob: Job? = null

    @Volatile
    private var unauthorized = false

    @Volatile
    private var breakerUntil = 0L

    @Volatile
    private var rateLimitUntil = 0L

    // One token per queued entry (promotions add none); a worker takes a token, then the best entry.
    private val tokens = Channel<Unit>(Channel.UNLIMITED)

    // Every state change, for awaitDecided; ticks are the subset callers redraw on.
    private val version = MutableStateFlow(0L)
    private val tickCount = MutableStateFlow(0L)

    /**
     * Ticks when tags are stored, when the breaker or a 401 period clears, when the key state changes
     * and when a failed title's backoff ends: at most one tick a second (R7-m4), the latest kept.
     */
    val changes: Flow<Unit> = tickCount.drop(1).conflate().transform {
        emit(Unit)
        delay(TICK_INTERVAL_MS)
    }

    init {
        scope.launch {
            cache.ensureLoaded()
            tick()
        }
        // Every value, the first included: a redundant first call only ticks once (R-S3 m1: skipping it
        // could drop the null -> known change when the store is read before this subscribes).
        scope.launch { keyState.collect(::onKeyStateChanged) }
        repeat(workerCount) { scope.launch { workerLoop() } }
    }

    fun status(): TagSourceStatus {
        val key = keyState.value ?: return TagSourceStatus.KEY_UNKNOWN
        return when {
            !key.hasKey || unauthorized -> TagSourceStatus.NO_KEY
            nowMs() < breakerUntil -> TagSourceStatus.PAUSED
            else -> TagSourceStatus.READY
        }
    }

    /** A map read: safe from any thread, cheap enough for every draw pass. */
    fun state(ref: TitleRef): TagState = when (val cached = cache.read(ref)) {
        is CachedTags.Tags -> TagState.Tagged(cached.tags)
        CachedTags.WordsOnly -> TagState.WordsOnly
        CachedTags.Missing ->
            if (ref.target() == TmdbTarget.Unresolvable || status() == TagSourceStatus.NO_KEY) TagState.WordsOnly
            else TagState.Undecided
    }

    /**
     * `movie:<tmdbId>` / `tv:<tmdbId>` for the allowlist (117/D30): direct for a `tmdb:` id, through the
     * cached alias for an IMDb id; null when unknown, so an id TMDB cannot resolve is never exempt.
     */
    fun tmdbKey(ref: TitleRef): String? = when (val target = ref.target()) {
        is TmdbTarget.ById -> TitleTagCache.tagKey(ref.kind, target.tmdbId)
        is TmdbTarget.ByImdb -> cache.aliasFor(ref.kind, target.imdbId)?.let { TitleTagCache.tagKey(ref.kind, it) }
        TmdbTarget.Unresolvable -> null
    }

    /** Queues [ref] unless it is decided, queued (a higher [priority] promotes it), in flight or backing off. */
    fun request(ref: TitleRef, priority: TagPriority) {
        if (ref.target() == TmdbTarget.Unresolvable) return
        if (cache.isLoaded && cache.read(ref) != CachedTags.Missing) return
        if (status() == TagSourceStatus.NO_KEY) return
        synchronized(lock) {
            // Re-checked under the lock: a 401 may have cleared the queue since the check above.
            if (unauthorized) return
            inFlight[ref]?.let { current ->
                // Being fetched: keep the higher level for a possible re-queue (429, stale 401).
                if (priority < current) inFlight[ref] = priority
                return
            }
            failedUntil[ref]?.let { if (nowMs() < it) return }
            val current = queued[ref]
            if (current != null) {
                if (priority < current) {
                    levels[current.ordinal].remove(ref)
                    levels[priority.ordinal].add(ref)
                    queued[ref] = priority
                }
                return
            }
            // R8-m5: at the cap only BACKGROUND work is refused; gates and Detail still get in.
            if (priority == TagPriority.BACKGROUND && queued.size >= queueCap) return
            enqueue(ref, priority)
        }
        tokens.trySend(Unit)
    }

    /**
     * A person asked for [ref] (every Detail open, Retry included, 117/P8): forgets its failed-fetch backoff, so the
     * next [request] fetches it now. The breaker and the no-key state still hold (they protect TMDB).
     */
    fun retry(ref: TitleRef) {
        synchronized(lock) { failedUntil.remove(ref) }
    }

    /**
     * Queues [refs] at [priority] and waits up to [budgetMs] until each is decided or no longer pending
     * (failed), returning early when the source is paused or has no key.
     */
    suspend fun awaitDecided(refs: Collection<TitleRef>, priority: TagPriority, budgetMs: Long) {
        refs.forEach { request(it, priority) }
        if (budgetMs <= 0) return
        withTimeoutOrNull(budgetMs) {
            version.first {
                val status = status()
                status == TagSourceStatus.PAUSED || status == TagSourceStatus.NO_KEY ||
                    refs.all { ref -> state(ref) != TagState.Undecided || !isPending(ref) }
            }
        }
    }

    private fun isPending(ref: TitleRef): Boolean = synchronized(lock) { ref in queued || ref in inFlight }

    private suspend fun workerLoop() {
        while (true) {
            tokens.receive()
            cache.ensureLoaded()
            waitOutPauses()
            val key = keyState.filterNotNull().first()
            if (!key.hasKey || unauthorized) {
                // No key means nothing queued: drop what slipped in, so no entry outlives its token.
                synchronized(lock) { clearQueue() }
                continue
            }
            val (ref, priority) = synchronized(lock) { takeNext() } ?: continue
            var retry = false
            try {
                retry = process(ref, key.effectiveKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A fetcher bug must not end this worker: count it as a non-IO failure.
                Log.w(TAG, "tag lookup failed: ${e.javaClass.simpleName}")
                onFailed(ref, io = false)
            } finally {
                // Leaving inFlight and re-queueing in one step: a request racing this sees one state (R2 m4).
                val requeued = synchronized(lock) {
                    val level = inFlight.remove(ref) ?: priority
                    (retry && !unauthorized && ref !in queued).also { if (it) enqueue(ref, level) }
                }
                if (requeued) tokens.trySend(Unit)
                bumpVersion()
            }
        }
    }

    private fun takeNext(): Pair<TitleRef, TagPriority>? {
        for (priority in TagPriority.entries) {
            val level = levels[priority.ordinal]
            val ref = level.firstOrNull() ?: continue
            level.remove(ref)
            queued.remove(ref)
            inFlight[ref] = priority
            return ref to priority
        }
        return null
    }

    private suspend fun waitOutPauses() {
        while (true) {
            val wait = maxOf(breakerUntil, rateLimitUntil) - nowMs()
            if (wait <= 0) return
            delay(wait)
        }
    }

    /** True when [ref] should go back to the queue (a 429, or a 401 for a key since replaced). */
    private suspend fun process(ref: TitleRef, apiKey: String): Boolean {
        if (cache.read(ref) != CachedTags.Missing) return false
        val tmdbId = when (val target = ref.target()) {
            is TmdbTarget.ById -> target.tmdbId
            is TmdbTarget.ByImdb -> cache.aliasFor(ref.kind, target.imdbId)
                ?: when (val found = source.find(target.imdbId, ref.kind, apiKey)) {
                    is Fetch.Ok -> found.value.also {
                        onReached()
                        cache.putAlias(ref.kind, target.imdbId, it)
                    }
                    else -> return onNotOk(ref, found, apiKey)
                }
            TmdbTarget.Unresolvable -> return false
        }
        // The same title may already be cached under its TMDB id (a tmdb: row): the alias is all it needed.
        if (cache.read(ref) != CachedTags.Missing) {
            tick()
            return false
        }
        return when (val fetched = source.tags(ref.kind, tmdbId, apiKey)) {
            is Fetch.Ok -> {
                cache.putTags(ref.kind, tmdbId, fetched.value)
                onReached()
                tick()
                false
            }
            else -> onNotOk(ref, fetched, apiKey)
        }
    }

    /** True when the title should be re-queued. */
    private fun onNotOk(ref: TitleRef, result: Fetch<*>, usedKey: String): Boolean = when (result) {
        is Fetch.Ok -> false
        Fetch.NotFound -> {
            cache.putWordsOnly(ref)
            onReached()
            tick()
            false
        }
        // A 401 for a key the user has since replaced says nothing about the new one: retry with it.
        Fetch.Unauthorized -> !enterUnauthorized(usedKey)
        is Fetch.RateLimited -> {
            synchronized(lock) { rateLimitUntil = maxOf(rateLimitUntil, nowMs() + result.retryAfterMs) }
            true
        }
        is Fetch.Failed -> {
            onFailed(ref, result.io)
            false
        }
    }

    private fun onReached() = synchronized(lock) { consecutiveIoFailures = 0 }

    // Guarded by lock.
    private fun enqueue(ref: TitleRef, priority: TagPriority) {
        levels[priority.ordinal].add(ref)
        queued[ref] = priority
    }

    private fun onFailed(ref: TitleRef, io: Boolean) {
        var openBreaker = false
        synchronized(lock) {
            failedUntil[ref] = nowMs() + FAILED_BACKOFF_MS
            ensureBackoffTimer()
            // A non-IO failure reached TMDB, so it ends a run of IO failures.
            if (!io) consecutiveIoFailures = 0
            if (io && ++consecutiveIoFailures >= BREAKER_FAILURES) {
                consecutiveIoFailures = 0
                openBreaker = true
            }
        }
        if (openBreaker) {
            Log.w(TAG, "TMDB tag lookups failing; pausing ${BREAKER_PAUSE_MS / 1000} s")
            breakerUntil = nowMs() + BREAKER_PAUSE_MS
            bumpVersion()
            scope.launch {
                delay(BREAKER_PAUSE_MS)
                tick()
            }
        }
    }

    // Guarded by lock. One timer for the earliest backoff end (R7-m1); it ticks so held titles re-queue.
    private fun ensureBackoffTimer() {
        if (backoffJob?.isActive == true) return
        backoffJob = scope.launch {
            while (true) {
                val earliest = synchronized(lock) {
                    failedUntil.values.minOrNull().also { if (it == null) backoffJob = null }
                } ?: return@launch
                val wait = earliest - nowMs()
                if (wait > 0) delay(wait)
                val cleared = synchronized(lock) {
                    val now = nowMs()
                    failedUntil.values.removeAll { it <= now }
                }
                if (cleared) tick()
            }
        }
    }

    /** False when [usedKey] is no longer the key in use: that 401 is stale and nothing changes. */
    private fun enterUnauthorized(usedKey: String): Boolean {
        synchronized(lock) {
            // Read under the lock: onKeyStateChanged (same lock) runs after the new value is visible,
            // so a stale 401 either sees the new key here or is cleared by that call (R2 m2).
            if (keyState.value?.effectiveKey != usedKey) return false
            if (unauthorized) return true
            unauthorized = true
            clearQueue()
            reprobeJob?.cancel()
            reprobeJob = scope.launch { reprobe() }
        }
        Log.w(TAG, "TMDB refused the key (401); content filters use words + genres until it works")
        tick()
        return true
    }

    private suspend fun reprobe() {
        while (unauthorized) {
            delay(REPROBE_INTERVAL_MS)
            val key = keyState.value?.effectiveKey
            if (key.isNullOrBlank()) continue
            if (source.probe(key) is Fetch.Ok) {
                unauthorized = false
                tick()
                return
            }
        }
    }

    private fun onKeyStateChanged(state: TmdbKeyState?) {
        synchronized(lock) {
            unauthorized = false
            reprobeJob?.cancel()
            reprobeJob = null
            if (state != null && !state.hasKey) clearQueue()
        }
        tick()
    }

    // Guarded by lock. Leftover tokens find an empty queue and are dropped.
    private fun clearQueue() {
        levels.forEach { it.clear() }
        queued.clear()
    }

    private fun bumpVersion() = version.update { it + 1 }

    private fun tick() {
        bumpVersion()
        tickCount.update { it + 1 }
    }

    internal companion object {
        const val TAG = "TitleTagLookup"
        const val WORKERS = 6
        const val QUEUE_CAP = 5_000
        const val TICK_INTERVAL_MS = 1_000L
        const val FAILED_BACKOFF_MS = 5 * 60_000L
        const val BREAKER_FAILURES = 5
        const val BREAKER_PAUSE_MS = 60_000L
        const val REPROBE_INTERVAL_MS = 15 * 60_000L
    }
}
