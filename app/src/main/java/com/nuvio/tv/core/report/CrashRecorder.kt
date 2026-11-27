package com.nuvio.tv.core.report

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec 116 D4: keeps this TV's last crashes for a report. A chained default uncaught-exception
 * handler writes time, thread and the stack trace with its cause chain to `filesDir/report/crash-N.txt`
 * (newest [KEEP], at most [MAX_CHARS], [ReportStorage.MAX_AGE_MS] old at most), raw: a dying process gets no
 * scrub, DataStore read or regex. The report scrubs the files at send. Only the first crashing thread
 * writes; the previous handler is always called.
 */
object CrashRecorder {
    const val KEEP = 3
    const val MAX_CHARS = 32 * 1024
    private const val PREFIX = "crash-"
    private const val SUFFIX = ".txt"

    /** Installs the handler over the current default. Call before `super.onCreate()` (P2). */
    fun install(dir: File, now: () -> Long = System::currentTimeMillis) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler(dir, previous, now))
    }

    internal fun handler(
        dir: File,
        previous: Thread.UncaughtExceptionHandler?,
        now: () -> Long,
    ): Thread.UncaughtExceptionHandler {
        val written = AtomicBoolean(false)
        // Preallocated at install: a dying process may be out of memory.
        val buffer = StringBuilder(MAX_CHARS + 256)
        return Thread.UncaughtExceptionHandler { thread, error ->
            try {
                if (written.compareAndSet(false, true)) write(dir, buffer, thread, error, now())
            } catch (_: Throwable) {
                // A recorder failure must never replace the crash.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Crash files newest first, after dropping those past [ReportStorage.MAX_AGE_MS]. */
    fun records(dir: File, now: Long): List<File> {
        prune(dir, now)
        return files(dir)
    }

    private fun write(dir: File, out: StringBuilder, thread: Thread, error: Throwable, time: Long) {
        out.setLength(0)
        out.append(ReportScrub.CRASH_HEADER_PREFIX).append("time=").append(time)
            .append(" thread=").append(thread.name).append('\n')
        appendChain(out, error)
        if (out.length > MAX_CHARS) out.setLength(MAX_CHARS)
        dir.mkdirs()
        prune(dir, time)
        val next = (files(dir).maxOfOrNull(::index) ?: 0) + 1
        File(dir, "$PREFIX$next$SUFFIX").writeText(out.toString())
        files(dir).drop(KEEP).forEach { it.delete() }
    }

    /** The trace and its causes; the walk stops at a cause already seen (a cycle). */
    private fun appendChain(out: StringBuilder, error: Throwable) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var first = true
        while (current != null && seen.add(current) && out.length < MAX_CHARS) {
            if (!first) out.append("Caused by: ")
            out.append(current.toString()).append('\n')
            for (frame in current.stackTrace) {
                if (out.length >= MAX_CHARS) break
                out.append("\tat ").append(frame.toString()).append('\n')
            }
            first = false
            current = current.cause
        }
    }

    private fun prune(dir: File, now: Long) {
        files(dir).filter { now - it.lastModified() > ReportStorage.MAX_AGE_MS }.forEach { it.delete() }
    }

    /** Newest first, by the file's number. */
    private fun files(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) && index(f) > 0 }
            .orEmpty().sortedByDescending(::index)

    private fun index(file: File): Int = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toIntOrNull() ?: 0
}
