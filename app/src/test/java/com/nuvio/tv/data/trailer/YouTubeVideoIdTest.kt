package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single YouTube id parser shared by TrailerService and the in-app extractor. */
class YouTubeVideoIdTest {

    @Test
    fun `accepts bare ids and the common URL shapes`() {
        assertEquals(ID, youtubeVideoId(ID))
        assertEquals(ID, youtubeVideoId(" $ID "))
        assertEquals(ID, youtubeVideoId("https://www.youtube.com/watch?v=$ID"))
        assertEquals(ID, youtubeVideoId("https://m.youtube.com/watch?feature=share&v=$ID"))
        assertEquals(ID, youtubeVideoId("https://youtu.be/$ID"))
        assertEquals(ID, youtubeVideoId("https://youtu.be/$ID?t=42"))
        assertEquals(ID, youtubeVideoId("https://www.youtube.com/embed/$ID"))
        assertEquals(ID, youtubeVideoId("https://youtube.com/shorts/$ID"))
        assertEquals(ID, youtubeVideoId("https://www.youtube.com/live/$ID"))
    }

    @Test
    fun `rejects other hosts and malformed ids`() {
        assertNull(youtubeVideoId("https://example.com/watch?v=$ID"))
        assertNull(youtubeVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(youtubeVideoId("https://www.youtube.com/channel/$ID"))
        assertNull(youtubeVideoId("not a url"))
        assertNull(youtubeVideoId(""))
    }

    @Test
    fun `isYoutubeVideoId accepts exactly a bare 11-char id`() {
        assertTrue(isYoutubeVideoId(ID))
        assertTrue(isYoutubeVideoId("a-b_c-d_e-f"))
        assertFalse(isYoutubeVideoId(" $ID"))
        assertFalse(isYoutubeVideoId(ID.dropLast(1)))
        assertFalse(isYoutubeVideoId("https://youtu.be/$ID"))
    }

    private companion object {
        const val ID = "dQw4w9WgXcQ"
    }
}
