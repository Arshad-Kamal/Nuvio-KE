package com.nuvio.tv.core.player.autosync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSyncSubtitleHttpTest {

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nNão sei se é verdade, coração.\n"

    // KE: upstream's two Windows-1252 Portuguese tests are left out: they test the shared
    // SubtitleCharsetDetector, which lacks upstream 2497f2b74 / 53ca8f503 (Spec 9 register item).

    @Test
    fun keepsUtf8SubtitlesUnchanged() {
        val body = srt.toByteArray(Charsets.UTF_8).toResponseBody("text/plain".toMediaType())

        assertEquals(srt, AutoSyncSubtitleHttp.readResponseBodyLimited(body, maxBytes = 1 shl 20, languageHint = "pt"))
    }
}
