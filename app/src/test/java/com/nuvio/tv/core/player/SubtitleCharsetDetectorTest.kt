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

    // W5-01 (Spec 87 T1): hint-less legacy CJK files over 4 KB. The detector samples a fixed
    // window that can end inside a double-byte character; that must not zero the CJK scores.
    // The window is anchored at the first non-ASCII byte, so spaces added after the first CJK
    // character slide the rest of the file past its end: one cue's worth of shifts puts the
    // end at every byte of a cue, mid-character included.
    private fun cjkSrt(line: String, shift: Int): String = buildString {
        var cue = 1
        while (length < 6000) {
            val text = if (cue == 1) line.take(1) + " ".repeat(shift) + line.drop(1) else line
            val second = (cue % 60).toString().padStart(2, '0')
            append(cue).append('\n')
            append("00:00:$second,000 --> 00:00:$second,500\n")
            append(text).append('\n').append(line).append('\n').append(line).append("\n\n")
            cue++
        }
    }

    private fun assertDecodesWithoutHintOver4Kb(charsetName: String, line: String) {
        val charset = Charset.forName(charsetName)
        val cueBytes = ("99\n00:00:39,000 --> 00:00:39,500\n" + "$line\n".repeat(3) + "\n").toByteArray(charset).size
        for (shift in 0 until cueBytes) {
            val srt = cjkSrt(line, shift)
            val bytes = srt.toByteArray(charset)
            assertTrue(bytes.size > 4096)
            assertEquals("$charsetName shift=$shift", srt, SubtitleCharsetDetector.decode(bytes))
        }
    }

    private val simplifiedLine = "我们今天晚上去看电影吧，你觉得怎么样？"
    private val traditionalLine = "我們今天晚上去看電影吧，你覺得怎麼樣"

    @Test
    fun decodesGbkOver4KbWithoutHint() = assertDecodesWithoutHintOver4Kb("GBK", simplifiedLine)

    @Test
    fun decodesBig5Over4KbWithoutHint() = assertDecodesWithoutHintOver4Kb("Big5", traditionalLine)

    @Test
    fun decodesShiftJisOver4KbWithoutHint() =
        assertDecodesWithoutHintOver4Kb("Shift_JIS", "今日はとても良い天気ですね。一緒に散歩しませんか？")

    @Test
    fun decodesEucKrOver4KbWithoutHint() =
        assertDecodesWithoutHintOver4Kb("EUC-KR", "오늘 밤에 영화 보러 갈래요? 정말 재미있을 거예요.")

    // The Chinese hint picks Big5 when the whole file decodes cleanly as Big5, else GB18030.
    @Test
    fun chineseHintDecodesGbkAsGb18030() {
        val srt = cjkSrt(simplifiedLine, 0)
        assertEquals(srt, SubtitleCharsetDetector.decode(srt.toByteArray(Charset.forName("GBK")), languageHint = "zh"))
    }

    @Test
    fun chineseHintDecodesBig5AsBig5() {
        val srt = cjkSrt(traditionalLine, 0)
        assertEquals(srt, SubtitleCharsetDetector.decode(srt.toByteArray(Charset.forName("Big5")), languageHint = "zh"))
    }

    @Test
    fun chineseHintKeepsBig5ForAFileCutMidCharacter() {
        val text = cjkSrt(traditionalLine, 0).trimEnd()
        val bytes = text.toByteArray(Charset.forName("Big5"))
        val decoded = SubtitleCharsetDetector.decode(bytes.copyOf(bytes.size - 1), languageHint = "zh")
        assertTrue("Expected Big5 text, got: ${decoded.takeLast(40)}", decoded.startsWith(text.dropLast(1)))
    }

    @Test
    fun repairsDoubleEncodedCentralEuropeanUtf8WithLanguageHint() {
        val polish = "Zażółć gęślą jaźń — Pchnąć w tę łódź jeża i ośm skrzyń"
        val mojibake = doubleEncoded(polish, Charset.forName("windows-1250"))

        val repaired = SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(mojibake, "pol")
        assertEquals(nfd(polish), nfd(repaired))
    }

    // Spec 135 (issue #2): correct UTF-8 Vietnamese has letters windows-1252 cannot produce; the
    // double-encoding repair must leave it alone (it used to drop every such letter). The last line's
    // ì ò ã map to combining marks in windows-1258, which is what let the broken candidate win.
    private val vietnameseSrt = (1..20).joinToString("") {
        "$it\n00:00:0$it,000 --> 00:00:0$it,900\n" +
            "Nếu trong thời gian ngắn mà để chúng moi khoản bồi thường khổng lồ như vậy...\n" +
            "Cô đưa một chai nước cho con bé rồi.\n" +
            "Còn nhìn thấy đã có những gì?\n\n"
    }

    @Test
    fun correctVietnameseUtf8IsKeptForEveryHint() {
        for (hint in listOf("vie", "vi", "eng", null)) {
            val bytes = vietnameseSrt.toByteArray(Charsets.UTF_8)
            assertEquals("hint=$hint", vietnameseSrt, SubtitleCharsetDetector.decode(bytes, languageHint = hint))
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
            assertEquals("bom hint=$hint", vietnameseSrt, SubtitleCharsetDetector.decode(bom, languageHint = hint))
            assertEquals(
                "repair hint=$hint",
                vietnameseSrt,
                SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(vietnameseSrt, hint)
            )
        }
    }

    // Upstream NuvioTV 2497f2b74 + 53ca8f503 regression suite (Spec 134), ported verbatim.
    @Test
    fun decodesPortugueseWindows1252WithLanguageHint() {
        val ptText = "Eles não têm medo de nada. Não vamos desistir, eles estão lá. Você não sabe o que eles têm."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = ptText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "por")
        assertEquals(ptText, decoded)
    }

    @Test
    fun decodesPortugueseWindows1252WithoutLanguageHint() {
        val ptText = "Eles não têm medo de nada. Não vamos desistir, eles estão lá. Você não sabe o que eles têm."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = ptText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(ptText, decoded)
    }

    @Test
    fun decodesPortugueseUtf8WithoutDistortion() {
        val ptText = "Eles não têm medo de nada. Não vamos desistir, eles estão lá. Você não sabe o que eles têm."
        val rawBytes = ptText.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "por")
        assertEquals(ptText, decoded)
    }

    @Test
    fun preservesTranslatedRomanianUtf8SubtitlesWithRussianLanguageHint() {
        val roText = "Când ajunge la gară, își dă seama că a uitat pâinea și apa în mașină. În sfârșit, pleacă spre casă."
        val rawBytes = roText.toByteArray(Charsets.UTF_8)

        // Simulates issue #3315: Subtitle was translated from Russian to Romanian, but player track language metadata is still "rus"
        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "rus")
        assertEquals(roText, decoded)
    }

    @Test
    fun decodesSpanishWindows1252WithoutLanguageHint() {
        val esText = "¿Cómo estás? ¡Muy bien, señor! Canción y corazón."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = esText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(esText, decoded)
    }

    @Test
    fun decodesFrenchWindows1252WithoutLanguageHint() {
        val frText = "Bonjour le monde! Ça va très bien, où sont les élèves? À bientôt!"
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = frText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(frText, decoded)
    }

    @Test
    fun decodesGermanWindows1252WithoutLanguageHint() {
        val deText = "Guten Tag! Über den Wolken müssen Äpfel und Öfen schön sein."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = deText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(deText, decoded)
    }

    @Test
    fun decodesItalianWindows1252WithoutLanguageHint() {
        val itText = "Ciao a tutti! Perché così è la città e più avanti c'è un caffè."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = itText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(itText, decoded)
    }

    @Test
    fun decodesPolishWindows1250WithLanguageHint() {
        val plText = "Cześć świecie! Zażółć gęślą jaźń. Dzień dobry!"
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = plText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "pol")
        assertEquals(plText, decoded)
    }

    @Test
    fun decodesCzechWindows1250WithLanguageHint() {
        val csText = "Příliš žluťoučký kůň úpěl ďábelské ódy. Dobrý den!"
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = csText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ces")
        assertEquals(csText, decoded)
    }

    @Test
    fun decodesHungarianWindows1250WithLanguageHint() {
        val huText = "Jó napot kívánok! Árvíztűrő tükörfúrógép."
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = huText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "hun")
        assertEquals(huText, decoded)
    }

    @Test
    fun decodesRomanianWindows1250WithLanguageHint() {
        val roText = "Bună ziua! Vă mulţumesc frumos pentru ajutor."
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = roText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ron")
        assertEquals(roText, decoded)
    }

    @Test
    fun decodesThaiWindows874WithLanguageHint() {
        val thText = "สวัสดีครับ ยินดีต้อนรับสู่ประเทศไทย"
        val win874Charset = Charset.forName("windows-874")
        val rawBytes = thText.toByteArray(win874Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "tha")
        assertEquals(thText, decoded)
    }

    @Test
    fun decodesThaiWindows874WithoutLanguageHintAutoDetection() {
        val thText = "สวัสดีครับ ยินดีต้อนรับสู่ประเทศไทย ทดสอบภาษาไทย"
        val win874Charset = Charset.forName("windows-874")
        val rawBytes = thText.toByteArray(win874Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(thText, decoded)
    }

    @Test
    fun decodesVietnameseWindows1258WithLanguageHint() {
        val viText = "Xin chào bạn! Tôi là người Việt Nam."
        val win1258Charset = Charset.forName("windows-1258")
        val rawBytes = byteArrayOf(
            0x58, 0x69, 0x6e, 0x20, 0x63, 0x68, 0xe0.toByte(), 0x6f, 0x21, 0x20,
            0x54, 0xf4.toByte(), 0x69, 0x20, 0x79, 0xea.toByte(), 0x75, 0x20,
            0x56, 0x69, 0xec.toByte(), 0x74, 0x20, 0x4e, 0x61, 0x6d, 0x2e
        )

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "vie")
        assertEquals("Xin chào! Tôi yêu Vít Nam.", decoded)
    }

    @Test
    fun decodesJapaneseShiftJisWithLanguageHint() {
        val jaText = "こんにちは、世界！日本語の字幕テストです。"
        val shiftJisCharset = Charset.forName("Shift_JIS")
        val rawBytes = jaText.toByteArray(shiftJisCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "jpn")
        assertEquals(jaText, decoded)
    }

    @Test
    fun decodesJapaneseShiftJisWithoutLanguageHintAutoDetection() {
        val jaText = "こんにちは、世界！日本語の字幕テストです。"
        val shiftJisCharset = Charset.forName("Shift_JIS")
        val rawBytes = jaText.toByteArray(shiftJisCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(jaText, decoded)
    }

    @Test
    fun decodesKoreanEucKrWithLanguageHint() {
        val koText = "안녕하세요 세상! 한국어 자막 테스트입니다."
        val eucKrCharset = Charset.forName("EUC-KR")
        val rawBytes = koText.toByteArray(eucKrCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "kor")
        assertEquals(koText, decoded)
    }

    @Test
    fun decodesKoreanEucKrWithoutLanguageHintAutoDetection() {
        val koText = "안녕하세요 세상! 한국어 자막 테스트입니다."
        val eucKrCharset = Charset.forName("EUC-KR")
        val rawBytes = koText.toByteArray(eucKrCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(koText, decoded)
    }

    @Test
    fun decodesChineseTraditionalBig5WithLanguageHint() {
        val zhText = "繁體中文測試字幕，你好世界！"
        val big5Charset = Charset.forName("Big5")
        val rawBytes = zhText.toByteArray(big5Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "zh-tw")
        assertEquals(zhText, decoded)
    }

    @Test
    fun decodesChineseSimplifiedGb18030WithLanguageHint() {
        val zhText = "简体中文测试字幕，你好世界！"
        val gb18030Charset = Charset.forName("GB18030")
        val rawBytes = zhText.toByteArray(gb18030Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "zh-cn")
        assertEquals(zhText, decoded)
    }

    @Test
    fun decodesRussianWindows1251WithoutLanguageHintAutoDetection() {
        val ruText = "Привет мир! Это проверка автоматического определения кодировки."
        val win1251Charset = Charset.forName("windows-1251")
        val rawBytes = ruText.toByteArray(win1251Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(ruText, decoded)
    }

    @Test
    fun decodesGreekWindows1253WithoutLanguageHintAutoDetection() {
        val elText = "Γεια σας φίλοι μου! Αυτή είναι μια δοκιμή ελληνικών υποτίτλων."
        val win1253Charset = Charset.forName("windows-1253")
        val rawBytes = elText.toByteArray(win1253Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(elText, decoded)
    }

    // Spec 134 (KE): gates added while reconciling upstream's Hebrew-only repair with KE's
    // per-charset repair (W3-C4). Long files: the old no-hint Hebrew fallback needed 15+ accents.
    private val longPortuguese = List(4) {
        "Eles não têm medo de nada. Não vamos desistir, eles estão lá. Você não sabe o que eles têm."
    }.joinToString("\n")

    @Test
    fun keepsLongPortugueseUtf8WithoutHint() {
        assertEquals(longPortuguese, SubtitleCharsetDetector.decode(longPortuguese.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodesLongPortugueseWindows1252WithoutHint() {
        val bytes = longPortuguese.toByteArray(Charset.forName("windows-1252"))
        assertEquals(longPortuguese, SubtitleCharsetDetector.decode(bytes))
    }

    @Test
    fun keepsRomanianWithOnlyWindows1252LettersUnderRussianHint() {
        // Every letter is windows-1252-encodable, so only the word-density gate stops a Cyrillic re-decode.
        val roText = "Când vine mâine în câmp, câinele râde în vânt la pâine. Îmi place în câmp."
        assertEquals(roText, SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(roText, "rus"))
    }

    @Test
    fun decodesThaiDialogueWindows874WithoutHint() {
        // Spec 134: the early Thai check is gated so GBK/Big5 keep decoding (Spec 87 tests); real Thai still passes it.
        val thText = "1\n00:00:01,000 --> 00:00:03,000\nผมไม่รู้ว่าเขาจะมาหรือเปล่า แต่เราต้องรอที่นี่ก่อนนะครับ\n\n" +
            "2\n00:00:04,000 --> 00:00:06,000\nกรุณาอย่าทำแบบนั้นอีก ฉันขอร้องล่ะ แม่บอกให้กลับบ้านก่อนมืด\n"
        val rawBytes = thText.toByteArray(Charset.forName("windows-874"))
        assertEquals(thText, SubtitleCharsetDetector.decode(rawBytes))
    }

    @Test
    fun decodesRussianWindows1251MislabelledEnglish() {
        // Spec 134 D3: an "eng" tag does not force cp1252, so detection still finds Cyrillic.
        val ruText = "Привет мир! Это проверка автоматического определения кодировки."
        val rawBytes = ruText.toByteArray(Charset.forName("windows-1251"))
        assertEquals(ruText, SubtitleCharsetDetector.decode(rawBytes, languageHint = "eng"))
    }

    @Test
    fun repairsCzechMojibakeDespiteWindows1252UndefinedBytes() {
        // ť (cp1250 0x9D) is undefined in windows-1252 and reads back as U+FFFD; the rest still repairs.
        val czech = "Příliš žluťoučký kůň úpěl ďábelské ódy. Dobrý den, přátelé!"
        val mojibake = doubleEncoded(czech, Charset.forName("windows-1250"))
        assertTrue(mojibake.contains('\uFFFD'))

        val repaired = SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(mojibake, "ces")
        assertTrue("got: $repaired", repaired.contains("Příliš") && repaired.contains("kůň úpěl ďábelské"))
    }

    // Spec 134 red-team round 1 (D5): hint-less paragraphs per language, with the letters that broke the
    // ported detector (Portuguese õ, Italian ò, French œ/ï, Hebrew … and RLM).
    private val paragraphs = listOf(
        "windows-1252" to "Eles não têm informações sobre as eleições. As soluções são difíceis, mas não impossíveis. Você põe a mão no coração e diz que as opções acabaram? Não, ainda há milhões de razões.",
        "windows-1252" to "Però non è così facile. Può darsi che lui sia già partito, ma io non lo so. Perché non me l'hai detto prima? Farò quello che posso, però tu devi aiutarmi.",
        "windows-1252" to "Mon cœur est brisé, ma sœur. Où étais-tu hier soir? Je t'ai cherchée partout, même à l'hôpital. C'est très naïf de croire qu'il reviendra.",
        "windows-1252" to "Über den Wolken muss die Freiheit wohl grenzenlos sein. Können Sie mir bitte helfen? Ich weiß nicht, was ich tun soll.",
        "windows-1250" to "Zażółć gęślą jaźń. Proszę, nie rób tego więcej. Dzień dobry, czy możesz mi pomóc? Wszystko będzie dobrze, obiecuję.",
        "windows-1250" to "Příliš žluťoučký kůň úpěl ďábelské ódy. Děkuji vám, že jste přišli. Nevím, co mám dělat, ale musíme jít.",
        "windows-1254" to "Ne yapıyorsun burada? Şimdi gitmemiz gerekiyor. Ağaçların altında oturup konuşalım. Işığı kapat lütfen.",
        "windows-1251" to "Что ты здесь делаешь? Мы должны уйти прямо сейчас. Я не знаю, что делать. Спасибо тебе за помощь, друг.",
        "windows-1253" to "Τι κάνεις εδώ; Πρέπει να φύγουμε αμέσως. Δεν ξέρω τι να κάνω. Ευχαριστώ για τη βοήθεια, φίλε μου.",
        "windows-1255" to "מה אתה עושה כאן? אנחנו חייבים ללכת עכשיו. אני לא יודע מה לעשות… תודה על העזרה, חבר.\u200F זה מאוד מסוכן – אבל אין לנו ברירה.",
        "windows-1256" to "ماذا تفعل هنا؟ يجب أن نذهب الآن. لا أعرف ماذا أفعل. شكرا لك على المساعدة يا صديقي.",
    )

    @Test
    fun decodesHintlessParagraphsInEachLegacyCharset() {
        for ((charsetName, text) in paragraphs) {
            val bytes = text.toByteArray(Charset.forName(charsetName))
            assertEquals(charsetName, text, SubtitleCharsetDetector.decode(bytes))
        }
    }

    @Test
    fun decodesHintlessVietnameseWindows1258() {
        // Literal cp1258 bytes for "Người Việt đi đâu? Tôi không biết.": cp1258 spells toned letters as
        // base + combining mark (CC grave, F2 dot below, EC acute), with ư FD, ơ F5, đ F0.
        val cp1258Bytes = byteArrayOf(
            0x4E, 0x67, 0xFD.toByte(), 0xF5.toByte(), 0xCC.toByte(), 0x69, 0x20, 0x56, 0x69, 0xEA.toByte(), 0xF2.toByte(), 0x74, 0x20,
            0xF0.toByte(), 0x69, 0x20, 0xF0.toByte(), 0xE2.toByte(), 0x75, 0x3F, 0x20, 0x54, 0xF4.toByte(), 0x69, 0x20, 0x6B, 0x68,
            0xF4.toByte(), 0x6E, 0x67, 0x20, 0x62, 0x69, 0xEA.toByte(), 0xEC.toByte(), 0x74, 0x2E
        )
        val expected = String(cp1258Bytes, Charset.forName("windows-1258"))
        assertEquals(nfd(expected), nfd(SubtitleCharsetDetector.decode(cp1258Bytes)))
    }

    @Test
    fun decodesHintlessLegacyAssDialogueLines() {
        // NuvioAssMatroskaExtractor decodes each event alone and without a hint; ASS field and tag letters
        // must not pull a non-Latin line into cp1252.
        val hebrew = "0,0,Default,,0,0,0,,{\\i1}מה קורה כאן?{\\i0}"
        val russian = "Dialogue: 0,0:00:05.10,0:00:07.20,Default,,0,0,0,,{\\i1}Что ты здесь делаешь?{\\i0}"
        assertEquals(hebrew, SubtitleCharsetDetector.decode(hebrew.toByteArray(Charset.forName("windows-1255"))))
        assertEquals(russian, SubtitleCharsetDetector.decode(russian.toByteArray(Charset.forName("windows-1251"))))
    }

    private val nameHeavySimplified = "嗯，琪琪，你看见婷婷和萱萱了吗？呃，昊天说鑫鑫在楼下等我们。喔，好吧，我们快走。"
    private val nameHeavyTraditional = "嗯，琪琪，你看見婷婷和萱萱了嗎？呃，昊天說鑫鑫在樓下等我們。喔，好吧，我們快走。"

    @Test
    fun decodesNameHeavyChineseWithoutHint() {
        val gbk = cjkSrt(nameHeavySimplified, 0)
        assertEquals(gbk, SubtitleCharsetDetector.decode(gbk.toByteArray(Charset.forName("GBK"))))
        val big5 = cjkSrt(nameHeavyTraditional, 0)
        assertEquals(big5, SubtitleCharsetDetector.decode(big5.toByteArray(Charset.forName("Big5"))))
    }

    @Test
    fun thaiGateAdmitsThaiAndRejectsChinese() {
        fun gate(bytes: ByteArray): Boolean {
            val nonAscii = bytes.count { it < 0 }
            return SubtitleCharsetDetector.isThaiNotChinese(bytes, 0, bytes.size, nonAscii)
        }
        val thai = "ผมไม่รู้ว่าเขาจะมาหรือเปล่า แต่เราต้องรอที่นี่ก่อนนะครับ กรุณาอย่าทำแบบนั้นอีก ฉันขอร้องล่ะ"
        assertTrue(gate(thai.toByteArray(Charset.forName("windows-874"))))
        assertTrue(!gate(nameHeavySimplified.toByteArray(Charset.forName("GBK"))))
        assertTrue(!gate(nameHeavyTraditional.toByteArray(Charset.forName("Big5"))))
        assertTrue(!gate(simplifiedLine.toByteArray(Charset.forName("GBK"))))
    }

    @Test
    fun traditionalHintFallsBackToGb18030ForGbkBytes() {
        val srt = cjkSrt(simplifiedLine, 0)
        assertEquals(srt, SubtitleCharsetDetector.decode(srt.toByteArray(Charset.forName("GBK")), languageHint = "zh-tw"))
    }

    @Test
    fun brazilianPortugueseHintDecodesWindows1252() {
        val bytes = paragraphs[0].second.toByteArray(Charset.forName("windows-1252"))
        assertEquals(paragraphs[0].second, SubtitleCharsetDetector.decode(bytes, languageHint = "pob"))
    }

    @Test
    fun keepsCorrectUtf8VietnameseUnderVietnameseHint() {
        // Spec 135 / GitHub #2: the W3-C4 repair re-encoded correct Vietnamese as cp1252 (losing ộ ơ đ ư) and
        // decoded it as cp1258; D2 guard (a) must leave it alone, NFC and NFD alike.
        val vi = "Xin chào, tôi là người Việt Nam. Anh có khỏe không? Chúng ta phải đi ngay bây giờ. Cảm ơn bạn rất nhiều."
        for (text in listOf(vi, nfd(vi))) {
            assertEquals(text, SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(text, "vie"))
            assertEquals(text, SubtitleCharsetDetector.decode(text.toByteArray(Charsets.UTF_8), languageHint = "vie"))
        }
    }

    @Test
    fun noHintHebrewRepairNeedsABlankHint() {
        // D2 guard (c), as upstream: double-encoded Hebrew under an unmapped tag is left alone.
        val mojibake = doubleEncoded(paragraphs[9].second.replace("\u200F", ""), Charset.forName("windows-1255"))
        assertEquals(mojibake, SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(mojibake, "und"))
        assertTrue(SubtitleCharsetDetector.repairDoubleEncodedUtf8IfNeeded(mojibake, null).contains("אנחנו חייבים"))
    }
}
