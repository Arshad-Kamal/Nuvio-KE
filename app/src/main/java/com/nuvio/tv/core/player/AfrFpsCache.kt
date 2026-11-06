package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.core.player.FrameRateUtils.FrameRateDetection
import java.util.Locale

/**
 * AFR fps cache: the in-memory LRU of frame-rate detections plus its SHA-256-keyed disk
 * persistence. Split out of [FrameRateUtils] (DRDYK L3b-57, move-only).
 */
object AfrFpsCache {

    // Same tag as FrameRateUtils: `logcat -s FrameRateUtils` keeps every AFR line.
    private const val TAG = "FrameRateUtils"

    private const val FRAME_RATE_CACHE_SIZE = 64
    private val frameRateCache = object : LinkedHashMap<String, FrameRateDetection>(FRAME_RATE_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FrameRateDetection>?): Boolean {
            return size > FRAME_RATE_CACHE_SIZE
        }
    }

    // ------------------------------------------------------------------
    // C-1 (15 Aug 2026): disk persistence for the fps cache.
    //
    // The cache's only durable writer is the track-format AFR path, which
    // runs AFTER prepare -- so on every cold process start the preflight
    // reader misses and the play pays the mid-prepare display switch and
    // audio re-handshake. Persisting detections lets every rewatch take
    // the preflight hit path: switch before prepare, sink opens once.
    //
    //  * INERT WHEN UNINITIALISED: unit tests never call
    //    initFrameRateCachePersistence, so JVM behaviour is byte-identical
    //    to the pre-C-1 cache (the eviction/keying tests pin it).
    //  * PRIVACY: cache keys embed sanitised header values (debrid/Emby
    //    tokens ride in them). Only SHA-256(key) ever touches disk; the
    //    plaintext key never leaves memory.
    //  * TTL: persisted entries expire after 30 days. The preflight hit
    //    path stands down track-format AFR, so a stale entry (same
    //    filename, different content) would be uncorrectable within a
    //    play -- bound its lifetime instead.
    //  * Writes are debounced 2 s onto a single daemon thread and land
    //    via temp-file + rename; the startup path never blocks on IO.
    private const val PERSIST_CAP = 256
    private const val PERSIST_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val PERSIST_FLUSH_DELAY_MS = 2000L
    private const val PERSIST_FILE_NAME = "afr_fps_cache_v1.txt"

    private class PersistedDetection(
        val detection: FrameRateDetection,
        val storedAtMs: Long
    )

    @Volatile private var persistFile: java.io.File? = null
    private val persistFlushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val persistExecutor: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AfrCachePersist").apply { isDaemon = true }
        }
    }

    // SHA-256(cache key) -> detection + store time. Guarded by the
    // frameRateCache lock so reader promotion, writer insertion and flush
    // snapshots cannot interleave.
    private val persistedCache = object : LinkedHashMap<String, PersistedDetection>(PERSIST_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PersistedDetection>?): Boolean {
            return size > PERSIST_CAP
        }
    }

    /** Idempotent; call once from Application.onCreate. Loads off-thread. */
    fun initFrameRateCachePersistence(context: android.content.Context) {
        if (persistFile != null) return
        val file = java.io.File(context.filesDir, PERSIST_FILE_NAME)
        persistFile = file
        persistExecutor.execute { loadPersistedCache(file) }
    }

    private fun hashKey(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(key.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun loadPersistedCache(file: java.io.File) {
        val now = System.currentTimeMillis()
        val loaded = ArrayList<Pair<String, PersistedDetection>>()
        try {
            if (!file.exists()) return
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('|')
                    if (parts.size != 6) return@forEach
                    val hash = parts[0]
                    val storedAt = parts[1].toLongOrNull() ?: return@forEach
                    if (now - storedAt > PERSIST_TTL_MS) return@forEach
                    val raw = parts[2].toFloatOrNull() ?: return@forEach
                    val snapped = parts[3].toFloatOrNull() ?: return@forEach
                    val w = parts[4].toIntOrNull()
                    val h = parts[5].toIntOrNull()
                    loaded.add(hash to PersistedDetection(FrameRateDetection(raw, snapped, w, h), storedAt))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AFR fps cache load failed (starting empty): ${e.message}")
            return
        }
        if (loaded.isEmpty()) return
        synchronized(frameRateCache) {
            loaded.forEach { (hash, entry) ->
                if (!persistedCache.containsKey(hash)) persistedCache[hash] = entry
            }
        }
        Log.i(TAG, "AFR fps cache hydrated: ${loaded.size} persisted detection(s)")
    }

    private fun schedulePersistFlush() {
        if (persistFile == null) return
        if (!persistFlushPending.compareAndSet(false, true)) return
        persistExecutor.schedule({
            persistFlushPending.set(false)
            flushPersistedCache()
        }, PERSIST_FLUSH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun flushPersistedCache() {
        val file = persistFile ?: return
        val snapshot: List<Pair<String, PersistedDetection>> = synchronized(frameRateCache) {
            persistedCache.entries.map { it.key to it.value }
        }
        try {
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                snapshot.forEach { (hash, e) ->
                    w.append(hash).append('|')
                    w.append(e.storedAtMs.toString()).append('|')
                    w.append(e.detection.raw.toString()).append('|')
                    w.append(e.detection.snapped.toString()).append('|')
                    w.append(e.detection.videoWidth?.toString() ?: "").append('|')
                    w.append(e.detection.videoHeight?.toString() ?: "")
                    w.newLine()
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AFR fps cache flush failed: ${e.message}")
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
        val raw = headers ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        val sanitized = LinkedHashMap<String, String>(raw.size)
        raw.forEach { (key, value) ->
            val k = key.trim()
            val v = value.trim()
            if (k.isNotEmpty() && v.isNotEmpty() && !k.equals("Range", ignoreCase = true)) {
                sanitized[k] = v
            }
        }
        return sanitized
    }

    internal fun buildCacheKey(url: String, headers: Map<String, String>, filename: String?): String {
        val sanitized = sanitizeHeaders(headers)
        // The resolved CDN host rotates per debrid resolve (nexus-170/196/197/198
        // all observed for one title on 24 Jul 2026), so including it here made the
        // entry miss whenever a fresh resolve landed on a different edge -- the
        // cache only hit when the link cache happened to return the identical URL.
        // The filename identifies the content; the host is transport. Reader and
        // writer both come through here, so the two sides cannot disagree.
        val baseKey = if (!filename.isNullOrBlank()) {
            "file://$filename"
        } else {
            url.substringBefore('?')
        }

        if (sanitized.isEmpty()) return baseKey
        return buildString {
            append(baseKey)
            // Normalize header names to lowercase so cache hits are stable across casing.
            sanitized.mapKeys { (key, _) -> key.lowercase(Locale.ROOT) }
                .toSortedMap()
                .forEach { (key, value) ->
                    append('|')
                    append(key)
                    append('=')
                    append(value)
                }
        }
    }

    fun getCachedFrameRate(url: String, headers: Map<String, String>, filename: String? = null): FrameRateDetection? {
        val key = buildCacheKey(url, headers, filename)
        synchronized(frameRateCache) {
            val inMemory = frameRateCache[key]
            if (inMemory != null) return inMemory
            if (persistedCache.isEmpty()) return null
            val hash = hashKey(key)
            val persisted = persistedCache[hash] ?: return null
            if (System.currentTimeMillis() - persisted.storedAtMs > PERSIST_TTL_MS) {
                persistedCache.remove(hash)
                return null
            }
            // Promote so LRU ordering and later writes behave exactly as a
            // same-process detection would.
            frameRateCache[key] = persisted.detection
            Log.i(TAG, "AFR fps cache: served from persisted entry")
            return persisted.detection
        }
    }

    fun cacheFrameRate(url: String, headers: Map<String, String>, detection: FrameRateDetection, filename: String? = null) {
        val key = buildCacheKey(url, headers, filename)
        synchronized(frameRateCache) {
            frameRateCache[key] = detection
            if (persistFile != null) {
                persistedCache[hashKey(key)] = PersistedDetection(detection, System.currentTimeMillis())
            }
        }
        schedulePersistFlush()
    }

    /** Test-only: wipe the in-memory FPS cache between unit tests. */
    internal fun clearFrameRateCache() {
        synchronized(frameRateCache) {
            frameRateCache.clear()
            persistedCache.clear()
        }
    }
}
