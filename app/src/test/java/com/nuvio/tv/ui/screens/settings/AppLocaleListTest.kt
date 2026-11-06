package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 21 K9: the language picker ([APP_LOCALE_TAGS]) and `res/xml/locale_config.xml` are two
 * lists of the same languages. Indonesian is `in` in the picker (the tag saved choices hold) and
 * `id` in the config; both name one language. Every language but English (the default `values/`)
 * also needs translated strings, in folders Android names with the legacy codes `in` and `iw`.
 */
class AppLocaleListTest {

    // Unit tests run from the app module directory (Gradle's default Test.workingDir).
    private val resDir = File("src/main/res")

    /** Legacy language code (picker tag, resource folder) -> current code. */
    private val legacyToCurrent = mapOf("in" to "id", "iw" to "he")
    private val currentToLegacy = legacyToCurrent.entries.associate { (legacy, current) -> current to legacy }

    @Test
    fun `picker and locale_config list the same languages`() {
        val config = File(resDir, "xml/locale_config.xml")
        assertTrue("run from the app module: ${config.absolutePath}", config.isFile)
        val withoutComments = config.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val configTags = Regex("""android:name="([^"]+)"""").findAll(withoutComments)
            .map { it.groupValues[1] }
            .toList()

        assertEquals("locale_config has duplicates", configTags.size, configTags.toSet().size)
        assertEquals("picker has duplicates", APP_LOCALE_TAGS.size, APP_LOCALE_TAGS.toSet().size)
        assertEquals(
            APP_LOCALE_TAGS.map { legacyToCurrent[it] ?: it }.toSortedSet(),
            configTags.map { legacyToCurrent[it] ?: it }.toSortedSet()
        )
    }

    @Test
    fun `every picker language but English has translated strings`() {
        val missing = APP_LOCALE_TAGS.filter { it != "en" }.filterNot { tag ->
            val parts = tag.split("-")
            val language = currentToLegacy[parts[0]] ?: parts[0]
            val folders = if (parts.size == 1) {
                listOf("values-$language")
            } else {
                listOf("values-$language-r${parts[1]}", "values-b+$language+${parts[1]}")
            }
            // Any XML file: values-hu holds its strings as string.xml (resource files merge by folder).
            folders.any { folder ->
                File(resDir, folder).listFiles { file -> file.extension == "xml" }?.isNotEmpty() == true
            }
        }

        assertEquals(emptyList<String>(), missing)
    }
}
