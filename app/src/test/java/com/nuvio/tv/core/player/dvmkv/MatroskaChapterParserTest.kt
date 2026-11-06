package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.PlaybackChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Format-capability F2/G1: the MKV Chapters element parse, driven through the vendored
 * [DefaultEbmlReader] from real EBML bytes (with the unknown siblings mkvmerge writes), plus the
 * extractor's routing of chapter IDs to the parser.
 */
class MatroskaChapterParserTest {

    @Test
    fun `top-level atoms become sorted chapters, hidden disabled untimed and nested ones dropped`() {
        val chapters = parse(
            chaptersElement(
                edition(
                    atom(90_500_000_000L, "Café", "Cafe (en)", nested = atom(95_000_000_000L, "Sub")),
                    atom(0L, "Opening", nested = atom(10_000_000_000L, "Off", enabled = false)),
                    atom(30_000_000_000L, "Hidden", hidden = true),
                    atom(40_000_000_000L, "Disabled", enabled = false),
                    atom(null, "No start"),
                    atom(60_000_000_000L, nested = atom(65_000_000_000L, "Nested", hidden = true)),
                )
            )
        )
        assertEquals(
            listOf(
                PlaybackChapter(0L, "Opening"),
                PlaybackChapter(60_000L, null),
                PlaybackChapter(90_500L, "Café"),
            ),
            chapters
        )
    }

    @Test
    fun `first default edition wins and ordered editions are skipped`() {
        val chapters = parse(
            chaptersElement(
                edition(atom(1_000_000_000L, "Ordered"), ordered = true),
                edition(atom(2_000_000_000L, "First")),
                edition(atom(3_000_000_000L, "Default"), isDefault = true),
                edition(atom(4_000_000_000L, "Second default"), isDefault = true),
                edition(atom(5_000_000_000L, "Later")),
            )
        )
        assertEquals(listOf(PlaybackChapter(3_000L, "Default")), chapters)
    }

    @Test
    fun `first edition with chapters is used when none is default`() {
        val chapters = parse(
            chaptersElement(
                edition(),
                edition(atom(2_000_000_000L, "A")),
                edition(atom(5_000_000_000L, "B")),
            )
        )
        assertEquals(listOf(PlaybackChapter(2_000L, "A")), chapters)
    }

    @Test
    fun `chapter elements outside a Chapters element are ignored`() {
        val stray = master(MatroskaChapterParser.ID_EDITION_ENTRY, atom(1_000_000_000L, "X"))
        assertEquals(emptyList<PlaybackChapter>(), parse(stray))
    }

    @Test
    fun `extractor routes chapter IDs to the parser and treats Chapters as level 1`() {
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        listOf(
            MatroskaChapterParser.ID_CHAPTERS,
            MatroskaChapterParser.ID_EDITION_ENTRY,
            MatroskaChapterParser.ID_EDITION_FLAG_DEFAULT,
            MatroskaChapterParser.ID_EDITION_FLAG_ORDERED,
            MatroskaChapterParser.ID_CHAPTER_ATOM,
            MatroskaChapterParser.ID_CHAPTER_TIME_START,
            MatroskaChapterParser.ID_CHAPTER_FLAG_HIDDEN,
            MatroskaChapterParser.ID_CHAPTER_FLAG_ENABLED,
            MatroskaChapterParser.ID_CHAPTER_DISPLAY,
            MatroskaChapterParser.ID_CHAP_STRING,
        ).forEach { id ->
            val expected = MatroskaChapterParser.getElementType(id)
            assertTrue(Integer.toHexString(id), expected != EbmlProcessor.ELEMENT_TYPE_UNKNOWN)
            assertEquals(Integer.toHexString(id), expected, extractor.getElementType(id))
        }
        assertTrue(extractor.isLevel1Element(MatroskaChapterParser.ID_CHAPTERS))
    }

    // --- helpers ---

    private fun parse(bytes: ByteArray): List<PlaybackChapter> {
        val parser = MatroskaChapterParser()
        val reader = DefaultEbmlReader()
        reader.init(ForwardingProcessor(parser))
        val stream = ByteArrayInputStream(bytes)
        val input = DefaultExtractorInput(
            { buffer, offset, length -> stream.read(buffer, offset, length).let { if (it < 0) C.RESULT_END_OF_INPUT else it } },
            0L,
            bytes.size.toLong()
        )
        while (reader.read(input)) {}
        return parser.chapters
    }

    private fun chaptersElement(vararg editions: ByteArray) = master(MatroskaChapterParser.ID_CHAPTERS, *editions)

    private fun edition(vararg atoms: ByteArray, isDefault: Boolean = false, ordered: Boolean = false): ByteArray {
        val children = buildList {
            add(uint(ID_EDITION_UID, 7))
            if (isDefault) add(uint(MatroskaChapterParser.ID_EDITION_FLAG_DEFAULT, 1))
            if (ordered) add(uint(MatroskaChapterParser.ID_EDITION_FLAG_ORDERED, 1))
            addAll(atoms)
        }
        return master(MatroskaChapterParser.ID_EDITION_ENTRY, *children.toTypedArray())
    }

    /** A ChapterAtom; each title is one ChapterDisplay, in order. A nested atom follows the parent's fields. */
    private fun atom(
        startNs: Long?,
        vararg titles: String,
        hidden: Boolean = false,
        enabled: Boolean = true,
        nested: ByteArray? = null,
    ): ByteArray {
        val children = buildList {
            add(uint(ID_CHAPTER_UID, 42))
            if (startNs != null) add(uint(MatroskaChapterParser.ID_CHAPTER_TIME_START, startNs))
            if (startNs != null) add(uint(ID_CHAPTER_TIME_END, startNs + 1_000_000L))
            if (hidden) add(uint(MatroskaChapterParser.ID_CHAPTER_FLAG_HIDDEN, 1))
            if (!enabled) add(uint(MatroskaChapterParser.ID_CHAPTER_FLAG_ENABLED, 0))
            titles.forEach { title ->
                add(
                    master(
                        MatroskaChapterParser.ID_CHAPTER_DISPLAY,
                        str(MatroskaChapterParser.ID_CHAP_STRING, title),
                        str(ID_CHAP_LANGUAGE, "eng"),
                    )
                )
            }
            if (nested != null) add(nested)
        }
        return master(MatroskaChapterParser.ID_CHAPTER_ATOM, *children.toTypedArray())
    }

    private fun master(id: Int, vararg children: ByteArray) =
        element(id, children.fold(ByteArray(0)) { acc, child -> acc + child })

    private fun uint(id: Int, value: Long) = element(id, ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() })

    private fun str(id: Int, value: String) = element(id, value.toByteArray(Charsets.UTF_8))

    /** EBML element: the ID's own bytes (marker bits included), an 8-byte size VINT, the payload. */
    private fun element(id: Int, payload: ByteArray): ByteArray {
        val idBytes = (3 downTo 0).map { (id ushr (8 * it)).toByte() }.dropWhile { it == 0.toByte() }
        val size = ByteArray(8) { i -> if (i == 0) 0x01 else (payload.size.toLong() ushr (56 - 8 * i)).toByte() }
        return idBytes.toByteArray() + size + payload
    }

    private class ForwardingProcessor(private val parser: MatroskaChapterParser) : EbmlProcessor {
        override fun getElementType(id: Int): Int = MatroskaChapterParser.getElementType(id)
        override fun isLevel1Element(id: Int) = false
        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) =
            parser.startMasterElement(id)
        override fun endMasterElement(id: Int) = parser.endMasterElement(id)
        override fun integerElement(id: Int, value: Long) = parser.integerElement(id, value)
        override fun floatElement(id: Int, value: Double) = Unit
        override fun stringElement(id: Int, value: String) = parser.stringElement(id, value)
        override fun binaryElement(id: Int, contentsSize: Int, input: ExtractorInput) =
            input.skipFully(contentsSize)
    }

    private companion object {
        // Siblings real files carry that the parser leaves to the reader's skip path.
        const val ID_EDITION_UID = 0x45BC
        const val ID_CHAPTER_UID = 0x73C4
        const val ID_CHAPTER_TIME_END = 0x92
        const val ID_CHAP_LANGUAGE = 0x437C
    }
}
