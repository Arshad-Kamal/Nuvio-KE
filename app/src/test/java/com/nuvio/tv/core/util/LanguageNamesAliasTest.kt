package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0.9.0 language-alias resolution (T10 item 1): addon subtitles labeled with
 * full English names resolve to ISO codes before display/matching.
 */
class LanguageNamesAliasTest {

    @Test
    fun resolveLanguageNameAlias_exactAndWordBoundaries() {
        assertEquals("en", resolveLanguageNameAlias("english"))
        assertEquals("en", resolveLanguageNameAlias("english forced"))
        assertEquals("no", resolveLanguageNameAlias("forced norwegian"))
        assertEquals("de", resolveLanguageNameAlias("audio german sdh"))
        assertEquals("zh", resolveLanguageNameAlias("mandarin"))
    }

    @Test
    fun resolveLanguageNameAlias_requiresTokenBoundary() {
        assertNull(resolveLanguageNameAlias("notenglish"))
        assertNull(resolveLanguageNameAlias("german,forced"))
        assertNull(resolveLanguageNameAlias(""))
        assertNull(resolveLanguageNameAlias("en"))
    }

    @Test
    fun languageCodeToName_resolvesEnglishNamesThroughDisplayOverrides() {
        // Stable across JVM default locales: "indonesian" → alias "id" → LANGUAGE_DISPLAY_OVERRIDES.
        assertEquals("Indonesia", languageCodeToName("Indonesian"))
    }

    @Test
    fun languageCodeToName_unresolvableCodeFallsBackToUpperCased() {
        assertEquals("XX", languageCodeToName("xx"))
    }

    @Test
    fun languageCodeToName_dashTwinLookupMatchesNormalizePipeline() {
        // Underscore inputs whose dash twin is an override key resolve the same in both
        // pipelines (normalizeLanguageCode double-lookup parity for the display path).
        assertEquals(languageCodeToName("es-419"), languageCodeToName("es_la"))
        assertEquals(languageCodeToName("zh-TW"), languageCodeToName("chi_tw"))
    }

    @Test
    fun aliasMap_leavesPortugueseSpanishToSpecialBlocks() {
        // normalizeLanguageCode's pt/es blocks own the regional split (pt-br / es-419); if those
        // names enter the alias map, languageCodeToName would collapse the variants.
        assertFalse(LANGUAGE_NAME_ALIASES.containsKey("portuguese"))
        assertFalse(LANGUAGE_NAME_ALIASES.containsKey("spanish"))
        assertTrue(LANGUAGE_NAME_ALIASES.containsKey("english"))
    }
}
