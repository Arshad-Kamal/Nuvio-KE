package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleCharsetDetectorTest {

    private val hebrewText = """
        1
        00:00:02,027 --> 00:00:19,081
        <i>- :גאה להציג Extreme צוות -</i>

        2
        00:00:34,191 --> 00:00:40,192
        <i>תורגם וסונכרן משמיעה על-ידי
        iMri & thebarak</i>

        3
        00:00:40,193 --> 00:00:46,785
        <i>הגהה: אבי דניאלי
        GimLY סנכרון וליטוש על-ידי</i>

        46
        00:04:42,243 --> 00:04:45,387
        זוהן, החזרנו את
        !הפנטום. -לא
    """.trimIndent()

    @Test
    fun decodesHebrewWindows1255WithLanguageHint() {
        val win1255Charset = Charset.forName("windows-1255")
        val rawBytes = hebrewText.toByteArray(win1255Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertTrue(decoded.contains("זוהן, החזרנו את"))
        assertTrue(decoded.contains("!הפנטום. -לא"))
        assertTrue(decoded.contains("Extreme צוות"))
    }

    @Test
    fun decodesHebrewWindows1255WithoutLanguageHintAutoDetection() {
        val win1255Charset = Charset.forName("windows-1255")
        val rawBytes = hebrewText.toByteArray(win1255Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertTrue("Expected decoded Hebrew text, but got: $decoded", decoded.contains("זוהן, החזרנו את"))
        assertTrue(decoded.contains("!הפנטום. -לא"))
    }

    @Test
    fun decodesUtf8WithBom() {
        val utf8Text = "שלום עולם! Hello world!"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val rawBytes = bom + utf8Text.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertEquals(utf8Text, decoded)
    }

    @Test
    fun decodesUtf8WithoutBom() {
        val utf8Text = "1\n00:00:01,000 --> 00:00:04,000\nזוהן, החזרנו את הפנטום"
        val rawBytes = utf8Text.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertEquals(utf8Text, decoded)
    }

    @Test
    fun decodesArabicWindows1256WithLanguageHint() {
        val arabicText = "مرحبا بكم في نيو يورك"
        val win1256Charset = Charset.forName("windows-1256")
        val rawBytes = arabicText.toByteArray(win1256Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ara")
        assertEquals(arabicText, decoded)
    }

    @Test
    fun decodesTurkishWindows1254WithLanguageHint() {
        val turkishText = "Merhaba dünya! Şöför ve ağaç."
        val win1254Charset = Charset.forName("windows-1254")
        val rawBytes = turkishText.toByteArray(win1254Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "tur")
        assertEquals(turkishText, decoded)
    }

    @Test
    fun decodesCyrillicWindows1251WithLanguageHint() {
        val russianText = "Привет мир! Это тестовые субтитры."
        val win1251Charset = Charset.forName("windows-1251")
        val rawBytes = russianText.toByteArray(win1251Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "rus")
        assertEquals(russianText, decoded)
    }

    @Test
    fun decodesGreekWindows1253WithLanguageHint() {
        val greekText = "Γεια σου κόσμε! Ελληνικοί υπότιτλοι."
        val win1253Charset = Charset.forName("windows-1253")
        val rawBytes = greekText.toByteArray(win1253Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ell")
        assertEquals(greekText, decoded)
    }

    @Test
    fun decodesDoubleEncodedHebrewUtf8WithLanguageHint() {
        val gibberishLatin1Utf8Text = "46\n00:04:42,243 --> 00:04:45,387\næåäï, äçæøðå àú\n!äôðèåí. -ìà"
        val rawBytes = gibberishLatin1Utf8Text.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertTrue("Expected decoded Hebrew text, but got: $decoded", decoded.contains("זוהן, החזרנו את"))
        assertTrue(decoded.contains("!הפנטום. -לא"))
    }

    @Test
    fun decodesDoubleEncodedHebrewUtf8WithoutLanguageHint() {
        val gibberishLatin1Utf8Text = "46\n00:04:42,243 --> 00:04:45,387\næåäï, äçæøðå àú\n!äôðèåí. -ìà\n\n47\n00:04:46,652 --> 00:04:49,374\n,îä æàú àåîøú\n?äçæøðå àú äôðèåí"
        val rawBytes = gibberishLatin1Utf8Text.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertTrue("Expected decoded Hebrew text, but got: $decoded", decoded.contains("זוהן, החזרנו את"))
    }

    // Double-encoding repair for the targets whose validity gates were dead until
    // 2026-08-27 (W3-C4): Turkish, Vietnamese and Central European. Fixtures are
    // built by reversing the pipeline: real text -> target-charset bytes -> read
    // back as windows-1252 (mojibake); the repair must undo exactly that.
    private fun doubleEncoded(text: String, realCharset: Charset): String =
        String(text.toByteArray(realCharset), Charset.forName("windows-1252"))

    private fun nfd(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)

    @Test
    fun repairsDoubleEncodedTurkishUtf8WithLanguageHint() {
        val turkish = "Merhaba dünya! Müzik Şöför ve ağaç ışığı Göztepe"
        val mojibake = doubleEncoded(turkish, Charset.forName("windows-1254"))

        val repaired = SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(mojibake, "tr")
        assertEquals(nfd(turkish), nfd(repaired))
    }

    @Test
    fun repairsDoubleEncodedVietnameseUtf8WithLanguageHint() {
        // The JVM's windows-1258 ENCODER '?'-substitutes Vietnamese that cp1258
        // only represents decomposed, so a String round-trip fixture is lossy.
        // Build the fixture from literal cp1258 bytes ("Đơn giàn ân êm ôm ù" —
        // every letter precomposed in cp1258); read back as windows-1252 = the
        // double-encoded mojibake a real file would carry.
        val cp1258Bytes = byteArrayOf(
            0xD0.toByte(), 0x6F, 0x6E, 0x20, 0x67, 0x69, 0xE0.toByte(), 0x6E, 0x20,
            0xE2.toByte(), 0x6E, 0x20, 0xEA.toByte(), 0x6D, 0x20, 0xF4.toByte(), 0x6D, 0x20, 0xF9.toByte()
        )
        val mojibake = String(cp1258Bytes, Charset.forName("windows-1252"))
        val expected = String(cp1258Bytes, Charset.forName("windows-1258"))

        val repaired = SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(mojibake, "vie")
        assertEquals(nfd(expected), nfd(repaired))
        assertTrue(repaired != mojibake)
    }

    @Test
    fun repairsDoubleEncodedCentralEuropeanUtf8WithLanguageHint() {
        val polish = "Zażółć gęślą jaźń — Pchnąć w tę łódź jeża i ośm skrzyń"
        val mojibake = doubleEncoded(polish, Charset.forName("windows-1250"))

        val repaired = SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(mojibake, "pol")
        assertEquals(nfd(polish), nfd(repaired))
    }
}
