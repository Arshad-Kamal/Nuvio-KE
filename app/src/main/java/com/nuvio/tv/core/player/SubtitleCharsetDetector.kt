package com.nuvio.tv.core.player

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.min

object SubtitleCharsetDetector {

    private fun safeCharset(name: String, fallback: Charset = StandardCharsets.UTF_8): Charset {
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            fallback
        }
    }

    private val CHARSET_WIN1254: Charset = safeCharset("windows-1254")
    private val CHARSET_WIN1255: Charset = safeCharset("windows-1255")
    private val CHARSET_WIN1256: Charset = safeCharset("windows-1256")
    private val CHARSET_WIN1251: Charset = safeCharset("windows-1251")
    private val CHARSET_WIN1250: Charset = safeCharset("windows-1250")
    private val CHARSET_WIN1253: Charset = safeCharset("windows-1253")
    private val CHARSET_WIN1252: Charset = safeCharset("windows-1252", StandardCharsets.ISO_8859_1)
    private val CHARSET_WIN874: Charset = safeCharset("windows-874", safeCharset("TIS-620", StandardCharsets.ISO_8859_1))
    private val CHARSET_WIN1258: Charset = safeCharset("windows-1258")
    private val CHARSET_KOI8_R: Charset = safeCharset("KOI8-R")
    private val CHARSET_GB18030: Charset = safeCharset("GB18030")
    private val CHARSET_BIG5: Charset = safeCharset("Big5")
    private val CHARSET_SHIFT_JIS: Charset = safeCharset("Shift_JIS")
    private val CHARSET_EUC_KR: Charset = safeCharset("EUC-KR")

    private const val MAX_SAMPLE_BYTES = 4096
    private val MARKUP_TAG = Regex("<[^>]*>|\\{[^}]*\\}")
    private val WHITESPACE = Regex("\\s+")

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        languageHint: String? = null
    ): String {
        if (length <= 0) return ""

        if (length >= 3 && (bytes[offset].toInt() and 0xFF) == 0xEF &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xBB &&
            (bytes[offset + 2].toInt() and 0xFF) == 0xBF
        ) {
            val utf8 = String(bytes, offset + 3, length - 3, StandardCharsets.UTF_8)
            return repairDoubleEncodedUtf8IfNeeded(utf8, languageHint)
        }
        if (length >= 2 && (bytes[offset].toInt() and 0xFF) == 0xFF &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xFE
        ) {
            return String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16LE)
        }
        if (length >= 2 && (bytes[offset].toInt() and 0xFF) == 0xFE &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xFF
        ) {
            return String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16BE)
        }

        if (isFastValidUtf8(bytes, offset, length)) {
            val utf8 = String(bytes, offset, length, StandardCharsets.UTF_8)
            return repairDoubleEncodedUtf8IfNeeded(utf8, languageHint)
        }

        val hintedCharset = resolveCharsetFromLanguageHint(bytes, offset, length, languageHint)
        val charset = hintedCharset ?: detectUniversalCharset(bytes, offset, length)
        val decoded = String(bytes, offset, length, charset)
        return repairDoubleEncodedUtf8IfNeeded(decoded, languageHint)
    }

    fun normalizeToUtf8(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        languageHint: String? = null
    ): ByteArray {
        val decoded = decode(bytes, offset, length, languageHint)
        return decoded.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Undoes double encoding: legacy single-byte subtitle bytes read back as windows-1252 and
     * saved as UTF-8. A mapped hint picks the target charset; with no hint only Hebrew is tried
     * (Ktuvit-style files); any other hint (Western European, CJK, unknown) leaves text alone.
     */
    fun repairDoubleEncodedUtf8IfNeeded(text: String, languageHint: String? = null): String {
        if (text.isEmpty()) return text
        // Spec 135 (issue #2): double-encoded text is a windows-1252 reading of UTF-8 bytes, so a
        // character windows-1252 cannot produce (Vietnamese ộ ơ đ ư ...) means the text is already
        // right. Re-encoding it would drop each such letter (Android writes 0x1A for it, a box on
        // screen). Same guard as Spec 134 D2.
        if (!couldBeWindows1252Decoded(text)) return text

        val normalized = languageHint?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        val targetCharset = normalized?.let { charsetForLanguage(it) }

        if (targetCharset != null) {
            val latin1Count = text.count { it in '\u00E0'..'\u00FA' }
            if (latin1Count >= 5 && couldBeWindows1252Decoded(text)) {
                try {
                    val win1252Bytes = text.toByteArray(CHARSET_WIN1252)
                    val candidate = String(win1252Bytes, targetCharset)
                    val validTargetChars = when (targetCharset) {
                        CHARSET_WIN1255 -> candidate.count { it in '\u0590'..'\u05FF' }
                        CHARSET_WIN1256 -> candidate.count { it in '\u0600'..'\u06FF' }
                        CHARSET_WIN1253 -> candidate.count { it in '\u0370'..'\u03FF' }
                        CHARSET_WIN1251 -> candidate.count { it in '\u0400'..'\u04FF' }
                        CHARSET_WIN874 -> candidate.count { it in '\u0E00'..'\u0E7F' }
                        // Ğ ğ İ ı, plus Ş ş (round-trips cp1252↔cp1254 byte-identically
                        // yet is Turkish-distinctive in the repaired text).
                        CHARSET_WIN1254 -> candidate.count { it in "\u011E\u011F\u0130\u0131\u015E\u015F" }
                        // Vietnamese: precomposed letters (Latin Extended Additional),
                        // combining tone marks, and the precomposed-in-cp1258 letters
                        // Đ đ Ơ ơ Ư ư — some runtimes decode cp1258 decomposed.
                        CHARSET_WIN1258 -> candidate.count {
                            it in '\u1EA0'..'\u1EF9' || it in '\u0300'..'\u0323' ||
                                it == '\u0110' || it == '\u0111' ||
                                it == '\u01A0' || it == '\u01A1' ||
                                it == '\u01AF' || it == '\u01B0'
                        }
                        // Latin Extended-A: ą ć ę ł ń ř š ž ů … for Polish/Czech/etc.
                        CHARSET_WIN1250 -> candidate.count { it in '\u0100'..'\u017F' }
                        else -> 0
                    }
                    val latinScript = targetCharset == CHARSET_WIN1254 ||
                        targetCharset == CHARSET_WIN1258 || targetCharset == CHARSET_WIN1250
                    if (validTargetChars > 0 && (latinScript || isMostlyMojibakeWords(text))) {
                        return candidate
                    }
                } catch (_: Exception) {}
            }
        } else if (normalized.isNullOrBlank()) {
            val latin1Count = text.count { it in '\u00E0'..'\u00FA' }
            if (latin1Count >= 15 && couldBeWindows1252Decoded(text) && isMostlyMojibakeWords(text)) {
                try {
                    val win1252Bytes = text.toByteArray(CHARSET_WIN1252)
                    val hebrewCandidate = String(win1252Bytes, CHARSET_WIN1255)
                    val hebrewChars = hebrewCandidate.count { it in '\u0590'..'\u05FF' }
                    if (hebrewChars >= latin1Count * 0.8) {
                        return hebrewCandidate
                    }
                } catch (_: Exception) {}
            }
        }

        return text
    }

    /**
     * True when every char is one a windows-1252 read of single-byte data can yield (U+FFFD for
     * its five undefined bytes, C1 controls when the reader was Latin-1). Any other char means
     * the text is genuine UTF-8, e.g. Romanian ă ș ț under a stale "rus" track tag (upstream
     * NuvioTV #3315), and must not be re-decoded.
     */
    private fun couldBeWindows1252Decoded(text: String): Boolean {
        val encoder = CHARSET_WIN1252.newEncoder()
        return text.all { c -> c <= '\u00FF' || c == '\uFFFD' || encoder.canEncode(c) }
    }

    /**
     * True when at least half the words (2+ chars with a letter; markup tags dropped) have no
     * ASCII letter. A double-encoded non-Latin script turns every letter into Latin-1 mojibake;
     * genuine Latin-script text has mostly plain-ASCII words even when heavily accented
     * (Portuguese "não", "está"), so it never passes (upstream 53ca8f503's word-density gate).
     */
    private fun isMostlyMojibakeWords(text: String): Boolean {
        val words = MARKUP_TAG.replace(text, " ").split(WHITESPACE)
            .filter { word -> word.length >= 2 && word.any { it.isLetter() } }
        if (words.isEmpty()) return false
        val mojibakeWords = words.count { word -> word.none { it in 'a'..'z' || it in 'A'..'Z' } }
        return mojibakeWords * 2 >= words.size
    }

    private fun resolveCharsetFromLanguageHint(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        languageHint: String?
    ): Charset? {
        if (languageHint.isNullOrBlank()) return null
        val normalized = languageHint.trim().lowercase()
        val lang = normalized.substringBefore('-').substringBefore('_')

        val legacyCharset = charsetForLanguage(lang)
        return when {
            legacyCharset == CHARSET_WIN1251 ->
                if (hasKoi8Vowels(bytes, offset, length)) CHARSET_KOI8_R else CHARSET_WIN1251
            legacyCharset != null -> legacyCharset
            // Western European hints force cp1252 (upstream 2497f2b74). English is left to detection:
            // addons tag many non-English tracks "eng" (Spec 134 D3).
            lang == "por" || lang == "pt" || lang == "pob" || lang.startsWith("portuguese") ||
                lang == "spa" || lang == "es" || lang.startsWith("spanish") ||
                lang == "fra" || lang == "fre" || lang == "fr" || lang.startsWith("french") ||
                lang == "deu" || lang == "ger" || lang == "de" || lang.startsWith("german") ||
                lang == "ita" || lang == "it" || lang.startsWith("italian") ||
                lang == "nld" || lang == "dut" || lang == "nl" || lang.startsWith("dutch") ||
                lang == "dan" || lang == "da" || lang.startsWith("danish") ||
                lang == "swe" || lang == "sv" || lang.startsWith("swedish") ||
                lang == "nor" || lang == "no" || lang.startsWith("norwegian") ||
                lang == "fin" || lang == "fi" || lang.startsWith("finnish") ||
                lang == "cat" || lang == "ca" || lang.startsWith("catalan") ||
                lang == "glg" || lang == "gl" || lang.startsWith("galician") ||
                lang == "eus" || lang == "baq" || lang == "eu" || lang.startsWith("basque") -> CHARSET_WIN1252
            lang == "zho" || lang == "zh" || lang == "chi" || lang.startsWith("chinese") -> {
                if (normalized.contains("tw") || normalized.contains("hk") || normalized.contains("traditional") || normalized.contains("hant")) {
                    // A GBK file under a traditional tag is not valid Big5; keep its bytes readable.
                    if (isCjkClean(bytes, offset, length, CHARSET_BIG5)) CHARSET_BIG5 else CHARSET_GB18030
                } else if (normalized.contains("cn") || normalized.contains("sg") || normalized.contains("simplified") || normalized.contains("hans")) {
                    CHARSET_GB18030
                } else {
                    if (isCjkClean(bytes, offset, length, CHARSET_BIG5)) CHARSET_BIG5 else CHARSET_GB18030
                }
            }
            lang == "jpn" || lang == "ja" || lang.startsWith("japanese") -> CHARSET_SHIFT_JIS
            lang == "kor" || lang == "ko" || lang.startsWith("korean") -> CHARSET_EUC_KR
            else -> null
        }
    }

    /**
     * Legacy single-byte charset for a normalized language code or name (the part before
     * any '-'/'_'), or null. Cyrillic maps to CP1251; [resolveCharsetFromLanguageHint]
     * refines that to KOI8-R from the bytes and adds Western European cp1252 and the CJK charsets.
     * This map also picks the double-encoding repair target, so cp1252 languages stay out of it.
     */
    private fun charsetForLanguage(lang: String): Charset? = when {
        lang == "heb" || lang == "he" || lang == "iw" || lang.startsWith("hebrew") -> CHARSET_WIN1255
        lang == "ara" || lang == "ar" || lang.startsWith("arabic") -> CHARSET_WIN1256
        lang == "ell" || lang == "el" || lang == "gre" || lang.startsWith("greek") -> CHARSET_WIN1253
        lang == "tur" || lang == "tr" || lang.startsWith("turkish") -> CHARSET_WIN1254
        lang == "rus" || lang == "ru" || lang == "ukr" || lang == "uk" || lang == "bel" || lang == "be" ||
            lang == "bul" || lang == "bg" || lang == "mkd" || lang == "mk" || lang == "srp" || lang == "sr" ||
            lang.startsWith("russian") || lang.startsWith("ukrainian") || lang.startsWith("bulgarian") ||
            lang.startsWith("serbian") || lang.startsWith("cyrillic") -> CHARSET_WIN1251
        lang == "tha" || lang == "th" || lang.startsWith("thai") -> CHARSET_WIN874
        lang == "vie" || lang == "vi" || lang.startsWith("vietnamese") -> CHARSET_WIN1258
        lang == "pol" || lang == "pl" || lang == "ces" || lang == "cs" || lang == "cze" || lang == "hun" ||
            lang == "hu" || lang == "slv" || lang == "sl" || lang == "hrv" || lang == "hr" || lang == "ron" ||
            lang == "ro" || lang == "rum" || lang == "slk" || lang == "sk" || lang.startsWith("polish") ||
            lang.startsWith("czech") || lang.startsWith("hungarian") || lang.startsWith("romanian") ||
            lang.startsWith("croatian") || lang.startsWith("slovak") || lang.startsWith("slovenian") -> CHARSET_WIN1250
        else -> null
    }

    private fun hasKoi8Vowels(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val sampleLength = min(length, MAX_SAMPLE_BYTES)
        val end = offset + sampleLength
        var russianVowels = 0
        var koi8Vowels = 0
        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xEE || b == 0xE0 || b == 0xE5 || b == 0xE8 || b == 0xFF || b == 0xFB) russianVowels++
            if (b == 0xCF || b == 0xC1 || b == 0xC5 || b == 0xC9 || b == 0xD5 || b == 0xDF) koi8Vowels++
        }
        return koi8Vowels > russianVowels && koi8Vowels >= 3
    }

    private fun isFastValidUtf8(bytes: ByteArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b1 = bytes[i++].toInt() and 0xFF
            if (b1 <= 0x7F) continue
            if (b1 in 0xC2..0xDF) {
                if (i >= end || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else if (b1 in 0xE0..0xEF) {
                if (i + 1 >= end || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else if (b1 in 0xF0..0xF4) {
                if (i + 2 >= end || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else {
                return false
            }
        }
        return true
    }

    private fun detectUniversalCharset(bytes: ByteArray, offset: Int, length: Int): Charset {
        var startOffset = offset
        val totalEnd = offset + length
        while (startOffset < totalEnd && (bytes[startOffset].toInt() and 0xFF) < 0x80) {
            startOffset++
        }
        if (startOffset >= totalEnd) return StandardCharsets.UTF_8

        val sampleOffset = if (startOffset > offset) (startOffset - min(100, startOffset - offset)) else offset
        val sampleLength = min(totalEnd - sampleOffset, MAX_SAMPLE_BYTES)
        val end = sampleOffset + sampleLength

        var nonAscii = 0
        var nonAsciiInLongRuns = 0
        var thaiConsonants = 0
        var consecutiveNonAscii = 0
        var maxConsecutiveNonAscii = 0

        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) {
                nonAscii++
                if (b in 0xA1..0xBF) thaiConsonants++
                consecutiveNonAscii++
                if (consecutiveNonAscii > maxConsecutiveNonAscii) {
                    maxConsecutiveNonAscii = consecutiveNonAscii
                }
            } else {
                if (consecutiveNonAscii >= 3) nonAsciiInLongRuns += consecutiveNonAscii
                consecutiveNonAscii = 0
            }
        }
        if (consecutiveNonAscii >= 3) nonAsciiInLongRuns += consecutiveNonAscii

        if (nonAscii == 0) return StandardCharsets.UTF_8

        // Japanese Shift_JIS check (Hiragana in 0x82 0x9F..0xF1)
        var hiraganaCount = 0
        var sjisIdx = sampleOffset
        while (sjisIdx < end - 1) {
            val b1 = bytes[sjisIdx].toInt() and 0xFF
            val b2 = bytes[sjisIdx + 1].toInt() and 0xFF
            if (b1 == 0x82 && b2 in 0x9F..0xF1) {
                hiraganaCount++
                sjisIdx++
            }
            sjisIdx++
        }
        if (hiraganaCount >= 2 && isCjkClean(bytes, sampleOffset, sampleLength, CHARSET_SHIFT_JIS)) {
            return CHARSET_SHIFT_JIS
        }

        // Korean EUC-KR syllables check (Hangul block match)
        val hangulScore = countBlockMatches(bytes, sampleOffset, sampleLength, CHARSET_EUC_KR, Character.UnicodeBlock.HANGUL_SYLLABLES)
        val gbHanziScore = countBlockMatches(bytes, sampleOffset, sampleLength, CHARSET_GB18030, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)

        if (hangulScore >= 4 && hangulScore >= gbHanziScore && maxConsecutiveNonAscii >= 4) {
            return CHARSET_EUC_KR
        }

        // Thai single-byte consonants check (0xA1..0xBF), gated so GBK/Big5 stay Chinese
        if (thaiConsonants * 4 >= nonAscii && thaiConsonants >= 4 &&
            isThaiNotChinese(bytes, sampleOffset, end, nonAscii)
        ) {
            return CHARSET_WIN874
        }

        if (gbHanziScore >= 4 && maxConsecutiveNonAscii >= 6) {
            var big5TrailCount = 0
            var i = sampleOffset
            while (i < end - 1) {
                val b1 = bytes[i].toInt() and 0xFF
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b1 in 0xA1..0xF9 && b2 in 0x40..0x7E) {
                    big5TrailCount++
                    i++
                }
                i++
            }
            if (big5TrailCount >= 2 && isCjkClean(bytes, sampleOffset, sampleLength, CHARSET_BIG5)) {
                return CHARSET_BIG5
            }
            return CHARSET_GB18030
        }

        // Latin script: accented letters sit alone or in pairs between ASCII letters ("ções", "příliš"), while
        // Cyrillic, Greek, Hebrew and Arabic words are long high-byte runs. Measured (Spec 134 D5): Latin
        // paragraphs 0-22% of high bytes in runs of 3+, non-Latin 87-91%. Markup letters cannot move this.
        if (nonAsciiInLongRuns * 2 < nonAscii) {
            return latinCharset(bytes, sampleOffset, end, nonAscii)
        }

        // Statistical evaluation for non-Latin single-byte alphabets (Hebrew, Arabic, Thai, Cyrillic, Greek)
        var hebrewLetters = 0
        var hebrewNeutral = 0
        var notCp1255 = 0
        var arabicAlCount = 0
        var russianVowels = 0
        var koi8Vowels = 0
        var cyrillicAo = 0
        var greekAo = 0

        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0xE0..0xFA) hebrewLetters++
            if (b in 0x80..0xBF || b == 0xFD || b == 0xFE) hebrewNeutral++
            if (b in 0xD9..0xDF || b == 0xFB || b == 0xFC || b == 0xFF) notCp1255++
            if (b == 0xEE || b == 0xE0 || b == 0xE5 || b == 0xE8 || b == 0xFF || b == 0xFB || b == 0xF3 || b == 0xFD || b == 0xFE) russianVowels++
            if (b == 0xCF || b == 0xC1 || b == 0xC5 || b == 0xC9 || b == 0xD5 || b == 0xDF) koi8Vowels++
            if (b == 0xE0 || b == 0xEE) cyrillicAo++
            if (b == 0xE1 || b == 0xEF) greekAo++
            if (i < end - 1 && b == 0xC7 && (bytes[i + 1].toInt() and 0xFF) == 0xE1) arabicAlCount++
        }

        // Hebrew: letters (0xE0..0xFA) are 90%+ of high bytes once punctuation, symbols and LRM/RLM
        // (0x80..0xBF, 0xFD, 0xFE) are set aside, and no byte cp1255 leaves undefined appears; Greek tonos
        // (0xDC..0xDF, 0xFC) and Russian ы ь я Щ..Я (0xFB, 0xFC, 0xFF, 0xD9..0xDF) use those (Spec 134 D5).
        if (hebrewLetters >= 4 && notCp1255 == 0 && hebrewLetters * 10 >= (nonAscii - hebrewNeutral) * 9) {
            return CHARSET_WIN1255
        }

        // Thai check (0xA1..0xBF consonants)
        if (thaiConsonants * 4 >= nonAscii && thaiConsonants >= 4) {
            return CHARSET_WIN874
        }

        // Arabic check
        if (arabicAlCount > 0) {
            return CHARSET_WIN1256
        }

        // Cyrillic check
        if (koi8Vowels > russianVowels && koi8Vowels >= 3) {
            return CHARSET_KOI8_R
        }
        // cp1251 vs cp1253 by each script's commonest letters, which sit on different bytes: Cyrillic а о
        // (0xE0, 0xEE; Greek ΰ ξ, rare) vs Greek α ο (0xE1, 0xEF; Cyrillic б п, far rarer than а о). Upstream's
        // widened vowel sets shared й х щ ъ ы ь with Greek, so short Russian lines read as Greek (Spec 134 D5).
        if (cyrillicAo >= 2 && cyrillicAo > greekAo) {
            return CHARSET_WIN1251
        }
        if (greekAo >= 2 && greekAo > cyrillicAo) {
            return CHARSET_WIN1253
        }

        return latinCharset(bytes, sampleOffset, end, nonAscii)
    }

    /**
     * Latin-script single-byte charset from bytes that only the target languages use (Spec 134 D5,
     * measured on pt/it/fr/es/de/pl/cs/hu/tr/vi paragraphs). Central European needs letters cp1252 text
     * does not carry (cp1250 ą ł ź š ž ť ľ Š Ž Ť Ź; not ś/ď, whose bytes are French œ/ï); Vietnamese
     * needs đ or ư plus combining tone marks (Italian ò/ì alone are tone-mark bytes); Turkish ğ ı ş
     * Ğ İ Ş. Each also needs a share of the high bytes, so a stray name does not switch the charset.
     */
    private fun latinCharset(bytes: ByteArray, start: Int, end: Int, nonAscii: Int): Charset {
        var centralEuropean = 0
        var vietnameseLetters = 0
        var vietnameseTones = 0
        var turkish = 0
        for (i in start until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xB9 || b == 0xB3 || b == 0x9F || b == 0x9A || b == 0x9E || b == 0x8F || b == 0x8A ||
                b == 0x8E || b == 0x8D || b == 0x9D || b == 0xBE
            ) centralEuropean++
            if (b == 0xF0 || b == 0xFD) vietnameseLetters++
            if (b == 0xCC || b == 0xD2 || b == 0xF2 || b == 0xEC) vietnameseTones++
            if (b == 0xF0 || b == 0xFE || b == 0xFD || b == 0xD0 || b == 0xDE || b == 0xDD) turkish++
        }
        if (centralEuropean >= 2 && centralEuropean * 20 >= nonAscii) return CHARSET_WIN1250
        if (vietnameseLetters >= 1 && vietnameseTones >= 2 && vietnameseTones * 10 >= nonAscii) return CHARSET_WIN1258
        if (turkish >= 2 && turkish * 10 >= nonAscii) return CHARSET_WIN1254
        return CHARSET_WIN1252
    }

    /**
     * The bytes decoded strictly, or null on any result but UNDERFLOW (malformed, unmappable;
     * overflow cannot happen at this size). A trailing partial sequence is left undecoded instead
     * of failing: the detector's sample can end inside a multi-byte character, and a file cut
     * mid-character still decodes in its own charset (Spec 87 T1, W5-01).
     */
    private fun decodeStrictly(bytes: ByteArray, offset: Int, length: Int, cs: Charset): CharBuffer? {
        val decoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate((length * decoder.maxCharsPerByte()).toInt() + 1)
        val result = decoder.decode(ByteBuffer.wrap(bytes, offset, length), out, /* endOfInput = */ false)
        if (!result.isUnderflow) return null
        out.flip()
        return out
    }

    /**
     * Guards the early Thai check (upstream 2497f2b74), which runs before the GB/Big5 check because
     * Thai cp874 also decodes as GB18030 hanzi. GBK/Big5 text meets its 0xA1..0xBF byte share too
     * (Spec 134 D4/D5; Spec 87's over-4 KB CJK tests), so every signal must read Thai:
     * - cp874 orthography: a leading vowel (0xE0..0xE4) precedes a consonant (0xA1..0xCE); a
     *   following vowel or mark (0xD0..0xDA, 0xE7..0xEE) follows a consonant or mark. At most 1% of
     *   high bytes may break it (measured: Thai 0%, GBK/Big5 dialogue 2-13%).
     * - Bytes cp874 leaves undefined: at most 1 per 500 high bytes (GB/Big5 trails hit them often).
     * - GB2312 pairs: Chinese is mostly level-1 hanzi (lead 0xB0..0xD7); Thai vowel bytes as leads land
     *   in level 2. Under 95% level 1 (Thai 59-86%); this catches short repetitive Chinese whose
     *   orthography happens to be clean (Spec 87's GBK fixture line scores 0 violations).
     */
    internal fun isThaiNotChinese(bytes: ByteArray, start: Int, end: Int, nonAscii: Int): Boolean {
        fun consonant(b: Int) = b in 0xA1..0xCE
        fun followMark(b: Int) = b in 0xD0..0xDA || b in 0xE7..0xEE
        fun cp874Undefined(b: Int) = b in 0xDB..0xDE || b >= 0xFC ||
            (b in 0x81..0x9F && b != 0x85 && b !in 0x91..0x97)
        var violations = 0
        var undefinedBytes = 0
        for (i in start until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) continue
            val prev = if (i > start) bytes[i - 1].toInt() and 0xFF else 0xA1
            val next = if (i + 1 < end) bytes[i + 1].toInt() and 0xFF else 0xA1
            if (b in 0xE0..0xE4 && !consonant(next)) violations++
            if (followMark(b) && !(consonant(prev) || followMark(prev))) violations++
            if (cp874Undefined(b)) undefinedBytes++
        }
        if (violations * 100 > nonAscii || undefinedBytes * 500 > nonAscii) return false

        var gbPairs = 0
        var levelOne = 0
        var i = start
        while (i < end - 1) {
            val lead = bytes[i].toInt() and 0xFF
            if (lead < 0x80) { i++; continue }
            val trail = bytes[i + 1].toInt() and 0xFF
            if (lead in 0xB0..0xF7 && trail in 0xA1..0xFE) {
                gbPairs++
                if (lead <= 0xD7) levelOne++
            }
            i += 2
        }
        return gbPairs == 0 || levelOne * 100 < gbPairs * 95
    }

    private fun isCjkClean(bytes: ByteArray, offset: Int, length: Int, cs: Charset): Boolean =
        decodeStrictly(bytes, offset, length, cs) != null

    private fun countBlockMatches(bytes: ByteArray, offset: Int, length: Int, cs: Charset, vararg blocks: Character.UnicodeBlock): Int {
        val decoded = decodeStrictly(bytes, offset, length, cs) ?: return 0
        return decoded.count { Character.UnicodeBlock.of(it) in blocks }
    }
}
