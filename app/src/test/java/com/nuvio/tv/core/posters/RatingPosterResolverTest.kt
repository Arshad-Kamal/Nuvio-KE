package com.nuvio.tv.core.posters

import com.nuvio.tv.core.posters.PosterContentType.MOVIE
import com.nuvio.tv.core.posters.PosterContentType.SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingPosterResolverTest {

    private fun template(raw: String): RatingPosterTemplate =
        (RatingPosterTemplate.parse(raw) as RatingPosterTemplate.Parsed.Valid).template

    private fun resolve(raw: String, ids: PosterContentIds, type: PosterContentType = MOVIE): String? =
        RatingPosterResolver.resolve(template(raw), ids, type)

    // Spec 39 D17's shape, the Posters+ configurator's Nuvio template.
    private val postersPlus =
        "https://pp.example.com/poster?tmdb_id={tmdb_id?}&imdb_id={imdb_id?}&stremio_id={id}&type={type}" +
            "&shape={shape}&access_key=SECRET"

    @Test
    fun `posters plus fills from an imdb meta id`() {
        assertEquals(
            "https://pp.example.com/poster?tmdb_id=&imdb_id=tt1254207&stremio_id=tt1254207&type=movie" +
                "&shape=poster&access_key=SECRET",
            resolve(postersPlus, PosterContentIds.from("tt1254207")),
        )
    }

    @Test
    fun `posters plus fills from a tmdb meta id, colon kept`() {
        assertEquals(
            "https://pp.example.com/poster?tmdb_id=1396&imdb_id=&stremio_id=tmdb:1396&type=series" +
                "&shape=poster&access_key=SECRET",
            resolve(postersPlus, PosterContentIds.from("tmdb:1396"), SERIES),
        )
    }

    @Test
    fun `an id-less card keeps its addon art even with an all-optional template`() {
        val allOptional = "https://pp.example.com/poster?imdb_id={imdb_id?}&tmdb_id={tmdb_id?}&mal_id={mal_id?}&type={type}"
        assertNull(resolve(allOptional, PosterContentIds.from("ctmdb.collection:10")))
        assertNull(resolve(allOptional, PosterContentIds.from("yt_id:UCabc")))
    }

    @Test
    fun `ids of the wrong shape count as missing`() {
        val ids = PosterContentIds.from("tt12/../../admin?x=1#", explicitImdbId = "tt1{id}")
        assertEquals("", ids.id)
        assertNull(ids.imdbId)
        assertNull(resolve("https://pp.example.com/p/{imdb_id}.jpg", ids))
        assertNull(PosterContentIds.from("tmdb:13a6").tmdbId)
        assertNull(PosterContentIds.from("tmdb:12345678901").tmdbId)
    }

    @Test
    fun `explicit imdb id wins over a non-imdb meta id`() {
        assertEquals(
            "https://pp.example.com/p/tt0903747.jpg",
            resolve("https://pp.example.com/p/{imdb_id}.jpg", PosterContentIds.from("tmdb:1396", "tt0903747")),
        )
    }

    @Test
    fun `required placeholder without a value keeps the addon art`() {
        assertNull(resolve("https://pp.example.com/p/{tmdb_id}.jpg", PosterContentIds.from("tt1254207")))
    }

    @Test
    fun `pipe takes the first id the title has, none keeps the addon art`() {
        val raw = "https://pp.example.com/p/{tmdb_id|imdb_id}.jpg"
        assertEquals("https://pp.example.com/p/tt1254207.jpg", resolve(raw, PosterContentIds.from("tt1254207")))
        assertNull(resolve(raw, PosterContentIds.from("kitsu:7442")))
    }

    @Test
    fun `typed id and id type derive from the validated id`() {
        val raw = "https://pp.example.com/p/{id_type}/{typed_id}.jpg"
        assertEquals("https://pp.example.com/p/tmdb/series-1396.jpg", resolve(raw, PosterContentIds.from("tmdb:1396"), SERIES))
        assertEquals("https://pp.example.com/p/imdb/tt1254207.jpg", resolve(raw, PosterContentIds.from("tt1254207")))
        assertEquals("https://pp.example.com/p/kitsu/7442.jpg", resolve(raw, PosterContentIds.from("kitsu:7442"), SERIES))
    }

    @Test
    fun `unknown placeholders stay as written`() {
        assertEquals(
            "https://pp.example.com/p/tt1254207.jpg?x={lang}",
            resolve("https://pp.example.com/p/{imdb_id}.jpg?x={lang}", PosterContentIds.from("tt1254207")),
        )
    }

    @Test
    fun `http home network template fills to the same address`() {
        assertEquals(
            "http://192.168.1.20:8000/poster?imdb_id=tt1254207&access_key=k",
            resolve("http://192.168.1.20:8000/poster?imdb_id={imdb_id}&access_key=k", PosterContentIds.from("tt1254207")),
        )
    }

    @Test
    fun `a filled url over the cap keeps the addon art`() {
        // The template fits (2046 chars); `{id}` (4) filled with tt1254207 (9) takes it past 2048.
        val base = "https://pp.example.com/p/{id}?k="
        val raw = base + "x".repeat(2046 - base.length)
        assertNull(resolve(raw, PosterContentIds.from("tt1254207")))
    }

    @Test
    fun `an id filled only into the fragment keeps the addon art`() {
        val parsed = RatingPosterTemplate.parse("https://pp.example.com/p/{imdb_id?}.jpg#{tmdb_id}")
        val template = (parsed as RatingPosterTemplate.Parsed.Valid).template
        assertNull(RatingPosterResolver.resolve(template, PosterContentIds.of(id = "tmdb:1396", tmdbId = "1396"), MOVIE))
    }

        // -- RPDB family: the six swaps, written out --

    private val rpdbImdb = "https://api.ratingposterdb.com/KEY/imdb/poster-default/{imdb_id}.jpg"
    private val rpdbTmdbTyped = "https://api.ratingposterdb.com/KEY/tmdb/poster-default/{type}-{tmdb_id}.jpg"
    private val rpdbTvdbLiteral = "https://api.ratingposterdb.com/KEY/tvdb/poster-default/series-{tvdb_id}.jpg"

    @Test
    fun `rpdb imdb to tmdb adds the typed prefix`() {
        assertEquals(
            "https://api.ratingposterdb.com/KEY/tmdb/poster-default/series-1396.jpg",
            resolve(rpdbImdb, PosterContentIds.of(id = "tmdb:1396", tmdbId = "1396"), SERIES),
        )
    }

    @Test
    fun `rpdb imdb to tvdb adds the typed prefix`() {
        assertEquals(
            "https://api.ratingposterdb.com/KEY/tvdb/poster-default/series-81189.jpg",
            resolve(rpdbImdb, PosterContentIds.of(id = "tvdb:81189", tvdbId = "81189"), SERIES),
        )
    }

    @Test
    fun `rpdb tmdb to imdb drops the type prefix`() {
        assertEquals(
            "https://api.ratingposterdb.com/KEY/imdb/poster-default/tt0903747.jpg",
            resolve(rpdbTmdbTyped, PosterContentIds.of(id = "tt0903747", imdbId = "tt0903747"), SERIES),
        )
    }

    @Test
    fun `rpdb tmdb to tvdb keeps the template's own prefix`() {
        assertEquals(
            "https://api.ratingposterdb.com/KEY/tvdb/poster-default/series-81189.jpg",
            resolve(rpdbTmdbTyped, PosterContentIds.of(id = "tvdb:81189", tvdbId = "81189"), SERIES),
        )
    }

    @Test
    fun `rpdb tvdb to imdb drops the literal prefix`() {
        assertEquals(
            "https://api.ratingposterdb.com/KEY/imdb/poster-default/tt0903747.jpg",
            resolve(rpdbTvdbLiteral, PosterContentIds.of(id = "tt0903747", imdbId = "tt0903747"), SERIES),
        )
    }

    @Test
    fun `rpdb tvdb to tmdb keeps the literal prefix`() {
        assertEquals(
            "https://api.ratingposterdb.com/KEY/tmdb/poster-default/series-1396.jpg",
            resolve(rpdbTvdbLiteral, PosterContentIds.of(id = "tmdb:1396", tmdbId = "1396"), SERIES),
        )
    }

    @Test
    fun `rpdb optional imdb with no imdb id still swaps`() {
        assertEquals(
            "https://api.ratingposterdb.com/KEY/tmdb/poster-default/movie-10378.jpg",
            resolve(
                "https://api.ratingposterdb.com/KEY/imdb/poster-default/{imdb_id?}.jpg",
                PosterContentIds.of(id = "tmdb:10378", tmdbId = "10378"),
            ),
        )
    }

    @Test
    fun `swaps only for rpdb family hosts`() {
        assertNull(
            resolve(
                "https://posters.example.com/KEY/imdb/poster-default/{imdb_id}.jpg",
                PosterContentIds.of(id = "tmdb:1396", tmdbId = "1396"),
            )
        )
        // A family name in the query is not the host.
        assertNull(
            resolve(
                "https://posters.example.com/imdb/{imdb_id}.jpg?ref=ratingposterdb.com",
                PosterContentIds.of(id = "tmdb:1396", tmdbId = "1396"),
            )
        )
    }
}
