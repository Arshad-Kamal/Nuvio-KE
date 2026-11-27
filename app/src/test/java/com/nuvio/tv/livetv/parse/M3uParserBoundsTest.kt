package com.nuvio.tv.livetv.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

/** Spec 137: the parser's own memory bounds (D2 line, D7 item, D8 header, D3 kept) and its line splitting. */
class M3uParserBoundsTest {

    private val long = "x".repeat(M3uParser.MAX_LINE_CHARS + 10)

    private fun item(title: String, url: String = "http://cdn.example/$title.m3u8") =
        "#EXTINF:-1 tvg-id=\"$title.in\",$title\n$url\n"

    private fun titles(text: String) = M3uParser.parse(text).entries.map { it.title }

    /** Hands out at most [chunk] chars per read, so every line break lands on a read boundary somewhere. */
    private class Chunked(text: String, private val chunk: Int) : Reader() {
        private val inner = StringReader(text)
        override fun read(cbuf: CharArray, off: Int, len: Int) = inner.read(cbuf, off, minOf(len, chunk))
        override fun close() = inner.close()
    }

    @Test
    fun `line endings split as readLine splits them, across any read boundary`() {
        val text = "#EXTM3U\r\n#EXTINF:-1,A\rhttp://x/a\n\n#EXTINF:-1,B\r\n\r\nhttp://x/b\r#EXTINF:-1,C\nhttp://x/c"
        val whole = M3uParser.parse(text)
        assertEquals(listOf("A", "B", "C"), whole.entries.map { it.title })
        assertEquals(listOf("http://x/a", "http://x/b", "http://x/c"), whole.entries.map { it.url })
        for (chunk in 1..7) assertEquals("chunk $chunk", whole, M3uParser.parse(Chunked(text, chunk)))
    }

    private fun lines(text: String, chunk: Int, max: Int = 100): List<String> {
        val reader = M3uParser.LineReader(Chunked(text, chunk), max)
        return generateSequence { reader.next()?.let { if (reader.overlong) "$it~" else it } }.toList()
    }

    @Test
    fun `the line reader splits exactly as readLine, at every read boundary`() {
        val text = "a\r\nb\rc\n\nd\r\r\ne\n\r\nf"
        val expected = text.reader().buffered().readLines()
        assertEquals(listOf("a", "b", "c", "", "d", "", "e", "", "f"), expected)
        for (chunk in 1..text.length) assertEquals("chunk $chunk", expected, lines(text, chunk))
        assertEquals(listOf("a", ""), lines("a\n\r", 1))
        assertEquals(emptyList<String>(), lines("", 4))
        // Kept up to max chars, marked when cut; exactly max is not cut.
        assertEquals(listOf("abc~", "abc", "ab"), lines("abcdef\nabc\r\nab", 2, max = 3))
    }

    @Test
    fun `an over-long line drops its whole item, wherever it falls, and the next item is intact`() {
        // #EXTINF, a directive, the URL: the item goes, URL included, never kept as a nameless channel.
        assertEquals(listOf("A", "C"), titles(item("A") + "#EXTINF:-1 x=\"$long\",B\nhttp://x/b\n" + item("C")))
        assertEquals(listOf("A", "C"), titles(item("A") + "#EXTINF:-1,B\n#KODIPROP:k=$long\n#KODIPROP:k2=v\nhttp://x/b\n" + item("C")))
        assertEquals(listOf("A", "C"), titles(item("A") + "#EXTINF:-1,B\nhttp://x/$long\n" + item("C")))
        // A dropped item's leftover directives do not bind to the next #EXTINF.
        val next = M3uParser.parse("#EXTINF:-1,B\n#EXTVLCOPT:http-user-agent=$long\n#EXTVLCOPT:http-referrer=r\n" + item("C"))
        assertEquals(listOf("C"), next.entries.map { it.title })
        assertEquals(emptyMap<String, String>(), next.entries.single().headers)
        // A directive written before its #EXTINF belongs to that item: over-long, it drops the #EXTINF and URL too.
        assertEquals(listOf("A", "C"), titles(item("A") + "#KODIPROP:k=$long\n#EXTINF:-1,B\nhttp://x/b\n" + item("C")))
        // Owed #EXTINF that never comes: a bare URL ends the dropped item (header-less layout), the next item is intact.
        assertEquals(listOf("C"), titles("#KODIPROP:k=$long\nhttp://x/b\n" + item("C")))
        // A line kept blank but cut drops the item it falls in, and costs nothing between items.
        val blank = " ".repeat(M3uParser.MAX_LINE_CHARS + 1) + "tail"
        assertEquals(listOf("A", "C"), titles(item("A") + "#EXTINF:-1,B\n$blank\nhttp://x/b\n" + item("C")))
        assertEquals(listOf("A", "C"), titles(item("A") + "$blank\n" + item("C")))
        // A line of exactly the limit is kept.
        val exact = "http://x/" + "p".repeat(M3uParser.MAX_LINE_CHARS - 9)
        assertEquals(listOf(exact), M3uParser.parse("#EXTINF:-1,A\n$exact\n").entries.map { it.url })
        // An over-long comment costs nothing; an over-long header line is dropped alone.
        assertEquals(listOf("A"), titles("#$long\n" + item("A")))
        val header = M3uParser.parse("\uFEFF#EXTM3U x=\"$long\"\n#EXTM3U url-tvg=\"http://g/e.xml\"\n" + item("A"))
        assertEquals(listOf("http://g/e.xml"), header.epgUrls)
        assertEquals(listOf("A"), header.entries.map { it.title })
    }

    @Test
    fun `a directive flood is bounded per item, with or without a URL after it`() {
        val flood = (0 until 20_000).joinToString("") { "#KODIPROP:key$it=value-$it\n" }
        assertTrue(flood.length > M3uParser.MAX_ITEM_CHARS)
        assertEquals(listOf("A", "C"), titles(item("A") + "#EXTINF:-1,B\n$flood" + "http://x/b\n" + item("C")))
        assertEquals(listOf("A", "C"), titles(item("A") + "#EXTINF:-1,B\n$flood" + "http://xt.example/movie/u/p/1.mkv\n" + item("C")))
        assertEquals(listOf("A"), titles(item("A") + "#EXTINF:-1,B\n$flood"))
        // Written before its #EXTINF, the flood still drops its whole item, #EXTINF and URL included.
        assertEquals(listOf("A", "C"), titles(item("A") + flood + "#EXTINF:-1,B\nhttp://x/b\n" + item("C")))
        // Under the budget, every directive stays (an item is not cut short).
        val few = (0 until 100).joinToString("") { "#KODIPROP:key$it=v\n" }
        assertEquals(100, M3uParser.parse("#EXTINF:-1,A\n${few}http://x/a\n").entries.single().kodiProps.size)
        // An item of exactly the budget (its lines' chars, breaks not counted) is kept; one char more is not.
        fun sized(chars: Int): String {
            val head = "#EXTINF:-1,A"
            val url = "http://x/a"
            var left = chars - head.length - url.length
            val props = buildString {
                var n = 0
                while (left > 0) {
                    // Each line under MAX_LINE_CHARS; at least one value char after the prefix.
                    val prefix = "#KODIPROP:k${n++}="
                    val line = prefix + "v".repeat(maxOf(1, minOf(left, 60_000) - prefix.length))
                    append(line).append('\n')
                    left -= line.length
                }
            }
            assertEquals(chars, head.length + url.length + props.lines().sumOf { it.length })
            return "$head\n$props$url\n"
        }
        assertEquals(1, M3uParser.parse(sized(M3uParser.MAX_ITEM_CHARS)).entries.size)
        assertEquals(0, M3uParser.parse(sized(M3uParser.MAX_ITEM_CHARS + 1)).entries.size)
    }

    @Test
    fun `header lines past the header budget are ignored`() {
        val headers = (0 until 20_000).joinToString("") { "#EXTM3U k$it=\"v\"\n" }
        val p = M3uParser.parse(headers + "#EXTM3U url-tvg=\"http://g/late.xml\"\n" + item("A"))
        val held = p.attrs.entries.sumOf { it.key.length + it.value.length }
        assertTrue("$held", held < M3uParser.MAX_HEADER_CHARS)
        assertTrue(p.attrs.size in 1000 until 20_000)
        assertEquals(emptyList<String>(), p.epgUrls)
        assertEquals(listOf("A"), p.entries.map { it.title })
    }

    @Test
    fun `the kept budget ends the parse with what was kept, and VOD items do not count`() {
        // Ten items of one size (ch0-ch9): each charges its two lines' chars, breaks not counted.
        val items = (0 until 10).joinToString("") { item("ch$it") }
        val perItem = item("ch0").length - 2L
        val vod = (0 until 1000).joinToString("") { item("m$it", "http://xt.example/movie/u/p/$it.mkv") }
        // Exactly five items' worth keeps five; the sixth passes it and ends the parse.
        val kept = M3uParser.parse(StringReader(vod + items), maxKeptChars = 5 * perItem).entries
        assertEquals((0 until 5).map { "ch$it" }, kept.map { it.title })
        assertEquals(4, M3uParser.parse(StringReader(vod + items), maxKeptChars = 5 * perItem - 1).entries.size)
        // The parse ends there: a smaller item after the one that passed the budget is not kept either.
        val big = item("big", "http://x/" + "p".repeat(200))
        // Room after ch0 (220) fits "s" (51 chars) but not "big" (239): skipping instead of ending would keep "s".
        val ended = M3uParser.parse(StringReader(item("ch0") + big + item("s")), maxKeptChars = perItem + 220).entries
        assertEquals(listOf("ch0"), ended.map { it.title })
    }
}
