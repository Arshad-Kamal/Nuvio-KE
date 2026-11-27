package com.nuvio.tv.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Spec 116 D4. */
class CrashRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = System.currentTimeMillis()

    private class Recording : Thread.UncaughtExceptionHandler {
        val seen = mutableListOf<Throwable>()
        override fun uncaughtException(t: Thread, e: Throwable) { seen += e }
    }

    @Test
    fun `writes the trace with its causes and always calls the previous handler`() {
        val dir = tmp.newFolder("report")
        val previous = Recording()
        val error = IllegalStateException("Player released", RuntimeException("root"))
        CrashRecorder.handler(dir, previous, { now }).uncaughtException(Thread.currentThread(), error)
        assertEquals(listOf<Throwable>(error), previous.seen)
        val text = CrashRecorder.records(dir, now).single().readText()
        assertTrue(text.startsWith(ReportScrub.CRASH_HEADER_PREFIX + "time=$now thread="))
        assertTrue("java.lang.IllegalStateException: Player released" in text)
        assertTrue("Caused by: java.lang.RuntimeException: root" in text)
        assertTrue("\tat " in text)
    }

    @Test
    fun `the previous handler runs even when writing fails`() {
        val notADir = tmp.newFile("report")
        val previous = Recording()
        CrashRecorder.handler(notADir, previous, { now }).uncaughtException(Thread.currentThread(), Error("x"))
        assertEquals(1, previous.seen.size)
    }

    @Test
    fun `only the first crash writes`() {
        val dir = tmp.newFolder("report")
        val previous = Recording()
        val handler = CrashRecorder.handler(dir, previous, { now })
        handler.uncaughtException(Thread.currentThread(), Error("first"))
        handler.uncaughtException(Thread.currentThread(), Error("second"))
        assertEquals(2, previous.seen.size) // the second crash still reaches the previous handler
        val files = CrashRecorder.records(dir, now)
        assertEquals(1, files.size)
        assertTrue("first" in files.single().readText())
    }

    @Test
    fun `keeps the newest three and drops files older than 14 days`() {
        val dir = tmp.newFolder("report")
        repeat(4) { CrashRecorder.handler(dir, null, { now }).uncaughtException(Thread.currentThread(), Error("e$it")) }
        val kept = CrashRecorder.records(dir, now)
        assertEquals(listOf("crash-4.txt", "crash-3.txt", "crash-2.txt"), kept.map(File::getName))
        kept.last().setLastModified(now - ReportStorage.MAX_AGE_MS - 1)
        kept.dropLast(1).forEach { it.setLastModified(now) }
        assertEquals(listOf("crash-4.txt", "crash-3.txt"), CrashRecorder.records(dir, now).map(File::getName))
    }

    @Test
    fun `a cause cycle stops the walk`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val dir = tmp.newFolder("report")
        CrashRecorder.handler(dir, null, { now }).uncaughtException(Thread.currentThread(), a)
        val text = CrashRecorder.records(dir, now).single().readText()
        assertEquals(1, Regex("Caused by").findAll(text).count())
        assertTrue(text.length <= CrashRecorder.MAX_CHARS)
    }
}
