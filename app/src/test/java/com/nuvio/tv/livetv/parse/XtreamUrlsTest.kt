package com.nuvio.tv.livetv.parse

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 78 D1 (movie/series items skipped) and D3 (extension-less live URLs play directly). */
class XtreamUrlsTest {

    @Test
    fun `movie and series items are VOD`() {
        assertTrue(XtreamUrls.isVod(XtreamFixtures.MOVIE))
        assertTrue(XtreamUrls.isVod(XtreamFixtures.SERIES))
        assertTrue(XtreamUrls.isVod("https://xt.example/movie/u/p/77"))
        assertTrue(XtreamUrls.isVod("http://xt.example:8080/series/u/p/12.mkv?token=t"))
    }

    @Test
    fun `live items and look-alikes are not VOD`() {
        listOf(
            XtreamFixtures.LIVE_TS,
            XtreamFixtures.LIVE_M3U8,
            XtreamFixtures.LIVE_BARE,
            "http://cdn.example/movie/trailer.mp4",
            "http://cdn.example/movie/channel/hd/index.m3u8",
            "http://cdn.example/series/u/p/12/extra.mp4",
            "http://cdn.example/movies/u/p/12.mp4",
            "http://xt.example/live/movie/pass/1001.ts",
            "http://xt.example/series/pass/1001",
            "http://cdn.example/movie/u//12.mp4",
            "rtmp://xt.example/movie/u/p/12.mp4",
            "not a url /movie/u/p/1.mkv"
        ).forEach { assertFalse(it, XtreamUrls.isVod(it)) }
    }

    @Test
    fun `extension-less live paths are bare live`() {
        assertTrue(XtreamUrls.isBareLive(XtreamFixtures.LIVE_BARE))
        assertTrue(XtreamUrls.isBareLive(XtreamFixtures.LIVE_BARE_LIVE_PREFIX))
        assertTrue(XtreamUrls.isBareLive("https://xt.example/my@account.xc/my_password/1477"))
    }

    @Test
    fun `other shapes are not bare live`() {
        listOf(
            XtreamFixtures.LIVE_TS,
            XtreamFixtures.LIVE_M3U8,
            XtreamFixtures.MOVIE,
            "http://xt.example/u/p/1003?token=x",
            "http://xt.example/u/p/1003#frag",
            "http://xt.example/u/p/1003/",
            "http://xt.example/u/p/abc",
            "http://xt.example/p/1003",
            "http://xt.example/vod/u/p/1003",
            "http://xt.example/a/u/p/1003",
            "http://xt.example/u/%20/1003",
            "udp://239.0.0.1:1234"
        ).forEach { assertFalse(it, XtreamUrls.isBareLive(it)) }
    }

    @Test
    fun `live paths with or without their output extension are live (Spec 90 L5)`() {
        listOf(
            XtreamFixtures.LIVE_TS,
            XtreamFixtures.LIVE_M3U8,
            XtreamFixtures.LIVE_BARE,
            XtreamFixtures.LIVE_BARE_LIVE_PREFIX,
            "http://xt.example/u/p/1003.TS"
        ).forEach { assertTrue(it, XtreamUrls.isLive(it)) }
        listOf(
            XtreamFixtures.MOVIE,
            "http://xt.example/u/p/1003.mp4",
            "http://xt.example/u/p/1003.ts?token=x",
            "http://xt.example/u/p/abc.ts",
            "http://xt.example/vod/u/p/1003.ts",
            "http://xt.example/p/1003.ts",
            "udp://239.0.0.1:1234"
        ).forEach { assertFalse(it, XtreamUrls.isLive(it)) }
    }

    /** Spec 78's isVod as it was, its file check a Regex (Spec 137 D9 replaced it with a char scan). */
    private fun regexIsVod(url: String): Boolean {
        if (!url.contains("/movie/") && !url.contains("/series/")) return false
        val segments = url.toHttpUrlOrNull()?.pathSegments ?: return false
        return segments.size == 4 && segments[0].let { it == "movie" || it == "series" } &&
            segments[1].isNotBlank() && segments[2].isNotBlank() && Regex("""\d+(\.[A-Za-z0-9]+)?""").matches(segments[3])
    }

    @Test
    fun `isVod accepts exactly what the old regex accepted (Spec 137 D9)`() {
        val files = listOf(
            "12", "0", "12.mkv", "12.MP4", "12.m3u8", "12.ts", "12.", ".mkv", "12.mk-v", "12.mkv.bak", "12..mkv",
            "a12", "12a", "12.mkv ", " 12", "12%20", "1%2E2", "12.%E2%80%8B", "%D9%A1%D9%A2", "", "12;x", "12.a_b"
        )
        val urls = files.flatMap { file ->
            listOf(
                "http://xt.example:8080/movie/u/p/$file",
                "https://xt.example/series/u/p/$file?token=t",
                "http://xt.example/series/u/p/$file#frag"
            )
        } + listOf(
            XtreamFixtures.MOVIE, XtreamFixtures.SERIES, XtreamFixtures.LIVE_TS, XtreamFixtures.LIVE_M3U8,
            XtreamFixtures.LIVE_BARE, XtreamFixtures.LIVE_BARE_LIVE_PREFIX,
            "http://xt.example/movie/u/p/12/", "http://xt.example/movie/u/p", "http://xt.example/movie//p/12.mkv",
            "http://xt.example/movie/%20/p/12.mkv", "http://xt.example/MOVIE/u/p/12.mkv", "http://xt.example/live/movie/p/12",
            "http://xt.example/movie/u/p/./12.mkv", "http://xt.example/movie/u/p/../12.mkv", "http://xt.example\\movie\\u\\p\\12.mkv",
            "http://u:pw@xt.example/movie/u/p/12.mkv", "rtmp://xt.example/movie/u/p/12.mp4", "movie/u/p/12.mkv",
            "not a url /movie/u/p/1.mkv", "http:///movie/u/p/12.mkv", "http://[::1]/series/u/p/7"
        )
        urls.forEach { assertEquals(it, regexIsVod(it), XtreamUrls.isVod(it)) }
        assertTrue(urls.count(XtreamUrls::isVod) > 20)
    }
}
