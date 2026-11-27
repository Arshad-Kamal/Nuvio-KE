package com.nuvio.tv.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 116 D3: part order, first line, scrub on every part, trim by UTF-8 bytes. */
class ReportTextTest {

    private val none = ReportSecrets(emptySet(), emptyMap())

    private fun parts(log: String) = ReportParts("tv", "app", "closes", "errors", "settings", log)

    @Test
    fun `first line and the six parts in D3 order`() {
        val text = ReportText.build(parts("log line"), none).bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith(ReportText.FIRST_LINE + "\n"))
        val titles = Regex("== (.+) ==").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("This TV", "Nuvio KE", "Last closes", "Playback errors", "Playback settings", "App log"), titles)
        assertTrue(text.endsWith("== App log ==\nlog line\n"))
    }

    @Test
    fun `every part is scrubbed, and the parts shown are the parts sent`() {
        val secrets = ReportSecrets(setOf("Zk81mnQx"), emptyMap())
        val file = ReportText.build(ReportParts("a Zk81mnQx", "b", "c Zk81mnQx", "d", "e", "f Zk81mnQx"), secrets)
        val text = file.bytes.toString(Charsets.UTF_8)
        assertFalse("Zk81mnQx" in text)
        assertEquals("a <secret>", file.parts.thisTv)
        assertTrue(file.parts.appLog in text)
    }

    @Test
    fun `when the other parts overflow alone, the log goes and Last closes keeps its newest lines`() {
        val anr = (1..20).joinToString("\n") { "# exit anr $it\n" + "x".repeat(32_000) }
        val file = ReportText.build(ReportParts("tv", "app", anr, "errors", "settings", "log"), none)
        assertTrue(file.bytes.size <= ReportText.MAX_BYTES)
        assertEquals("", file.parts.appLog)
        assertTrue(file.parts.lastCloses.startsWith("# exit anr 1\n"))
        assertFalse("# exit anr 20" in file.parts.lastCloses)
        assertEquals("tv", file.parts.thisTv)
    }

    @Test
    fun `a large Last closes is cut to its newest lines and the small parts stay whole`() {
        // Last closes overflows: it keeps its first (newest) lines and the small parts stay whole.
        val closes = (1..200).joinToString("\n") { "close $it " + "y".repeat(2_000) }
        val errors = (1..40).joinToString("\n") { "line $it " + "z".repeat(2_000) }
        val file = ReportText.build(ReportParts("tv", "app", closes, errors, "settings", "log"), none)
        assertTrue(file.bytes.size <= ReportText.MAX_BYTES)
        assertEquals("", file.parts.appLog)
        assertTrue(file.parts.lastCloses.startsWith("close 1 "))
        assertFalse("close 200 " in file.parts.lastCloses)
        assertEquals("tv", file.parts.thisTv)
    }

    @Test
    fun `an empty log and a log that fits exactly are kept whole`() {
        val empty = ReportText.build(parts(""), none)
        assertTrue(empty.bytes.toString(Charsets.UTF_8).endsWith("== App log ==\n"))
        val fixed = empty.bytes.size
        val exact = "a".repeat(ReportText.MAX_BYTES - fixed - 1) // + its newline = the budget
        val file = ReportText.build(parts(exact), none)
        assertEquals(exact, file.parts.appLog)
        assertEquals(ReportText.MAX_BYTES, file.bytes.size)
        assertEquals("", ReportText.build(parts(exact + "b"), none).parts.appLog)
    }

    @Test
    fun `the log is trimmed oldest first so the file fits, counting UTF-8 bytes`() {
        // 3-byte characters: a char count would overshoot the byte cap threefold.
        val line = "é".repeat(500) + "€".repeat(500)
        val log = (1..400).joinToString("\n") { "$it $line" }
        val file = ReportText.build(parts(log), none)
        assertTrue(file.bytes.size <= ReportText.MAX_BYTES)
        assertTrue(file.bytes.size > ReportText.MAX_BYTES - 3_000)
        assertTrue(file.parts.appLog.endsWith("400 $line"))
        assertFalse(file.parts.appLog.startsWith("1 "))
        assertEquals(file.bytes.toString(Charsets.UTF_8).substringAfter("== App log ==\n"), file.parts.appLog + "\n")
    }
}
