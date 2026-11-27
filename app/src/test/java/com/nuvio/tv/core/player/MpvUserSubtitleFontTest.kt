package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.UserSubtitleFontStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/** Spec 132 S5: the id-to-file lookup the players use, the ExoPlayer font cache, and mpv's `~~/fonts` copy. */
class MpvUserSubtitleFontTest {

    private val root = Files.createTempDirectory("s132").toFile()
    private val storeDir = File(root, UserSubtitleFontStore.DIR_NAME).apply { mkdirs() }
    private val fontsDir = File(root, "fonts")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun stored(id: String, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        File(storeDir, "$id-$name").apply { writeBytes(bytes) }

    @Test
    fun `fileOf finds a store id's file and nothing else`() {
        val file = stored(ID_A, "Montserrat-Medium.otf")
        stored(ID_B, "Other.ttf")
        File(storeDir, "$ID_A-Montserrat-Medium.otf.tmp").writeText("partial")
        assertEquals(file, UserSubtitleFontStore.fileOf(root, ID_A))
        assertNull("not a store id", UserSubtitleFontStore.fileOf(root, "font-1"))
        assertNull("no path tricks", UserSubtitleFontStore.fileOf(root, "../$ID_A"))
        assertNull("unknown id", UserSubtitleFontStore.fileOf(root, ID_C))
        file.delete()
        assertNull("file gone", UserSubtitleFontStore.fileOf(root, ID_A))
    }

    @Test
    fun `the chosen font is copied once and its family returned`() {
        val chosen = stored(ID_A, "M.otf", ByteArray(64) { it.toByte() })
        assertEquals("Montserrat Medium", syncMpvUserSubtitleFont(fontsDir, chosen, "Montserrat Medium"))
        val copy = File(fontsDir, chosen.name)
        assertArrayEquals(chosen.readBytes(), copy.readBytes())

        copy.setLastModified(1_000L)
        assertEquals("Montserrat Medium", syncMpvUserSubtitleFont(fontsDir, chosen, "Montserrat Medium"))
        assertEquals("a same-size copy is left alone", 1_000L, copy.lastModified())
        assertFalse(File(fontsDir, "${chosen.name}.tmp").exists())
    }

    @Test
    fun `a new choice replaces the old copy and leaves other files alone`() {
        val old = stored(ID_A, "A.ttf")
        val new = stored(ID_B, "B.ttf", byteArrayOf(9, 9))
        syncMpvUserSubtitleFont(fontsDir, old, "A")
        val foreign = File(fontsDir, "subfont.ttf").apply { writeText("x") }
        File(fontsDir, "$ID_C-C.ttf.tmp").writeText("leftover")

        assertEquals("B", syncMpvUserSubtitleFont(fontsDir, new, "B"))
        assertEquals(setOf(new.name, "subfont.ttf"), fontsDir.list()!!.toSet())
        assertTrue(foreign.exists())
    }

    @Test
    fun `a copy of another size is replaced`() {
        val chosen = stored(ID_A, "A.ttf", ByteArray(32) { 7 })
        fontsDir.mkdirs()
        File(fontsDir, chosen.name).writeBytes(byteArrayOf(1))
        assertEquals("A", syncMpvUserSubtitleFont(fontsDir, chosen, "A"))
        assertArrayEquals(chosen.readBytes(), File(fontsDir, chosen.name).readBytes())
    }

    @Test
    fun `a failed copy leaves no temp file for libass to load`() {
        val chosen = stored(ID_A, "A.ttf")
        assertTrue(chosen.setReadable(false))
        try {
            assertNull(syncMpvUserSubtitleFont(fontsDir, chosen, "A"))
            assertTrue(fontsDir.list().orEmpty().isEmpty())
        } finally {
            chosen.setReadable(true)
        }
    }

    @Test
    fun `the font cache loads once per file version, remembers a failure, and has nothing for no file`() {
        val loads = mutableListOf<String>()
        var result: String? = "face"
        val cache = UserFontCache { file: File -> loads += file.name; result }
        val a = stored(ID_A, "A.ttf")
        assertNull(cache.get(null))
        assertEquals("face", cache.get(a))
        assertEquals("face", cache.get(a))
        assertEquals(1, loads.size)

        a.writeBytes(byteArrayOf(1, 2, 3, 4))
        result = null
        assertNull("a changed file loads again", cache.get(a))
        assertNull(cache.get(a))
        assertEquals("a failure is remembered", 2, loads.size)

        result = "other"
        assertEquals("other", cache.get(stored(ID_B, "B.ttf")))
        assertEquals(3, loads.size)
    }

    @Test
    fun `no choice, a blank family or a missing file clears the copy and means Roboto`() {
        val chosen = stored(ID_A, "A.ttf")
        syncMpvUserSubtitleFont(fontsDir, chosen, "A")
        assertNull(syncMpvUserSubtitleFont(fontsDir, null, null))
        assertTrue(fontsDir.list()!!.isEmpty())

        assertNull(syncMpvUserSubtitleFont(fontsDir, chosen, " "))
        assertTrue(fontsDir.list()!!.isEmpty())

        chosen.delete()
        assertNull(syncMpvUserSubtitleFont(fontsDir, chosen, "A"))
        assertTrue(fontsDir.list().orEmpty().isEmpty())
    }

    private companion object {
        const val ID_A = "font-1-3f2b9c1e-8d4a-4f6b-9a2e-5c7d1e0f4a3b"
        const val ID_B = "font-2-0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        const val ID_C = "font-3-11111111-2222-4333-8444-555555555555"
    }
}
