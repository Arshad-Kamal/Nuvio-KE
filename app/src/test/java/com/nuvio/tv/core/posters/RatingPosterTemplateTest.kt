package com.nuvio.tv.core.posters

import com.nuvio.tv.core.posters.RatingPosterTemplate.Parsed
import com.nuvio.tv.core.posters.RatingPosterTemplate.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingPosterTemplateTest {

    private val postersPlus =
        "https://postersplus.elfhosted.com/poster?tmdb_id={tmdb_id?}&imdb_id={imdb_id?}&stremio_id={id}" +
            "&type={type}&shape={shape}&access_key=7f3c9a1e5b2d4e"

    private fun valid(raw: String): RatingPosterTemplate =
        (RatingPosterTemplate.parse(raw) as Parsed.Valid).template

    private fun refusal(raw: String): Refusal? = (RatingPosterTemplate.parse(raw) as? Parsed.Refused)?.reason

    @Test
    fun `posters plus template is valid and exposes host and key tail only`() {
        val template = valid(postersPlus)
        assertEquals("postersplus.elfhosted.com", template.displayHost)
        assertEquals("2d4e", template.keyTail)
        assertFalse(template.isHttp)
        assertFalse(template.toString().contains("7f3c9a1e5b2d4e"))
        assertFalse(RatingPosterState.On(template).toString().contains("access_key"))
    }

    @Test
    fun `rpdb key in the path is found`() {
        val template = valid("https://api.ratingposterdb.com/t1-Ab12Cd34Ef56Gh78/imdb/poster-default/{imdb_id}.jpg")
        assertEquals("Gh78", template.keyTail)
    }

    @Test
    fun `no recognisable key leaves the tail out`() {
        assertNull(valid("https://posters.example.com/p/{imdb_id}.jpg").keyTail)
    }

    @Test
    fun `a key under 12 characters shows no tail, so the tail never gives much of it away`() {
        assertNull(valid("https://posters.example.com/p/{imdb_id}.jpg?key=abcd").keyTail)
        assertNull(valid("https://posters.example.com/p/{imdb_id}.jpg?key=abcdefghijk").keyTail)
        assertEquals("ijkl", valid("https://posters.example.com/p/{imdb_id}.jpg?key=abcdefghijkl").keyTail)
    }

    @Test
    fun `refusals`() {
        assertEquals(Refusal.EMPTY, refusal("   "))
        assertEquals(Refusal.TOO_LONG, refusal("https://a.example/{imdb_id}?k=" + "x".repeat(2100)))
        assertEquals(Refusal.NOT_A_URL, refusal("posters.example.com/{imdb_id}"))
        assertEquals(Refusal.NOT_A_URL, refusal("ftp://posters.example.com/{imdb_id}"))
        assertEquals(Refusal.USERINFO, refusal("https://me:pw@posters.example.com/{imdb_id}"))
        assertEquals(Refusal.PLACEHOLDER_BEFORE_PATH, refusal("https://{id}.posters.example.com/p"))
        assertEquals(Refusal.PLACEHOLDER_BEFORE_PATH, refusal("https://posters.example.com:{tmdb_id}/p"))
        assertEquals(Refusal.NO_ID_PLACEHOLDER, refusal("https://posters.example.com/p?type={type}&shape={shape}"))
        assertEquals(Refusal.HTTP_NOT_HOME_NETWORK, refusal("http://posters.example.net/p/{imdb_id}"))
    }

    @Test
    fun `template tricks are refused`() {
        // Extra slashes and backslashes: OkHttp skips them, so the token would land in its host.
        assertEquals(Refusal.NOT_A_URL, refusal("https:///x{imdb_id}.evil.com/p"))
        assertEquals(Refusal.NOT_A_URL, refusal("https:/\\x{imdb_id}.evil.com/p"))
        assertEquals(Refusal.NOT_A_URL, refusal("https:/posters.example.com/{imdb_id}"))
        // `\\` ends the authority as OkHttp reads it: the token is in evil.com's path, the template's own host.
        assertEquals("evil.com", valid("https://evil.com\\{imdb_id}.x/p").displayHost)
        // A `://` later in the string is not the scheme's.
        assertEquals(Refusal.NOT_A_URL, refusal("https:/@evil.example/p/{imdb_id}?r=https://x/"))
        assertEquals(Refusal.USERINFO, refusal("https://@posters.example.com/{imdb_id}"))
        assertEquals(Refusal.PLACEHOLDER_BEFORE_PATH, refusal("https://%7Bid%7D.posters.example.com/p"))
        // An id only in the fragment is never sent.
        assertEquals(Refusal.NO_ID_PLACEHOLDER, refusal("https://posters.example.com/p#{imdb_id}"))
    }

    @Test
    fun `upper-case scheme and idn hosts read as OkHttp reads them`() {
        assertEquals("posters.example.com", valid("HTTPS://Posters.Example.com/p/{imdb_id}").displayHost)
        assertEquals("xn--pstr-5qa.example", valid("https://pöstr.example/p/{imdb_id}").displayHost)
    }

    @Test
    fun `private range boundaries`() {
        listOf("10.255.255.255", "172.16.0.0", "172.31.0.1", "192.168.255.255").forEach {
            assertTrue(it, isHomeNetworkLiteral(it))
        }
        listOf("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.1.1", "192.169.1.1", "193.168.1.1")
            .forEach { assertFalse(it, isHomeNetworkLiteral(it)) }
    }

    @Test
    fun `encoded placeholders decode, a key with encoded pipes keeps its bytes`() {
        val template = valid("https://posters.example.com/p?id=%7Bimdb_id%7Ctmdb_id%7D&access_key=ab%7Ccd")
        assertEquals("https://posters.example.com/p?id={imdb_id|tmdb_id}&access_key=ab%7Ccd", template.pattern)
        assertEquals(listOf("imdb_id", "tmdb_id"), template.tokens.single().keys)
    }

    @Test
    fun `http is accepted only for canonical private ip literals`() {
        listOf(
            "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20", "192.168.1.20:8000", "[fd12:3456::1]", "[fc00::1]",
            "[::ffff:192.168.1.20]",
        ).forEach { host ->
            assertTrue(host, RatingPosterTemplate.parse("http://$host/poster?imdb_id={imdb_id}") is Parsed.Valid)
        }
        listOf(
            "010.0.0.1", "0300.0250.1.20", "3232235796", "192.168.1", "0xc0.0xa8.1.20", "192.168.1.20.",
            "[::ffff:8.8.8.8]", "172.32.0.1", "8.8.8.8", "127.0.0.1", "169.254.1.1", "100.64.0.1", "[fe80::1]",
            "[::1]", "nas.local", "posters", "posters.lan",
        ).forEach { host ->
            assertEquals(host, Refusal.HTTP_NOT_HOME_NETWORK, refusal("http://$host/poster?imdb_id={imdb_id}"))
        }
    }

    @Test
    fun `home network literal reads canonical hosts by hand`() {
        assertTrue(isHomeNetworkLiteral("192.168.0.1"))
        assertFalse(isHomeNetworkLiteral("192.168.00.1"))
        assertFalse(isHomeNetworkLiteral("256.168.0.1"))
        assertTrue(isHomeNetworkLiteral("fd00::1"))
        assertFalse(isHomeNetworkLiteral("fd::1"))
        assertFalse(isHomeNetworkLiteral("fec0::1"))
    }

    @Test
    fun `display host shows a non-default port`() {
        assertEquals("192.168.1.20:8000", valid("http://192.168.1.20:8000/poster?imdb_id={imdb_id}").displayHost)
        assertEquals("[fd00::1]:8000", valid("http://[fd00::1]:8000/poster?imdb_id={imdb_id}").displayHost)
    }

    @Test
    fun `same text same template, fingerprint never holds the text`() {
        val a = valid(postersPlus)
        val b = valid("  $postersPlus ")
        assertEquals(a, b)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(16, a.fingerprint.length)
        assertFalse(a.fingerprint == valid(postersPlus.replace("7f3c", "7f3d")).fingerprint)
    }
}
