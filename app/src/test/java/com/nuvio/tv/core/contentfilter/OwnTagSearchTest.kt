package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.domain.contentfilter.OwnTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Spec 117 S7b: Find a tag's search outcomes (P9, M1 stage 4c). */
class OwnTagSearchTest {

    private val keyed = MutableStateFlow<TmdbKeyState?>(TmdbKeyState(userKey = "", effectiveKey = "k"))

    @Test
    fun `matches come back in TMDB's order, the query trimmed`() = runTest {
        var asked: Pair<String, String>? = null
        val search = OwnTagSearch(keyed) { key, query ->
            asked = key to query
            listOf(OwnTag(1, "reality tv"), OwnTag(2, "reality show"))
        }
        assertEquals(OwnTagSearchResult.Found(listOf(OwnTag(1, "reality tv"), OwnTag(2, "reality show"))), search.search(" reality "))
        assertEquals("k" to "reality", asked)
    }

    @Test
    fun `a refused or failed request, a thrown error and no key all say failed`() = runTest {
        assertEquals(OwnTagSearchResult.Failed, OwnTagSearch(keyed) { _, _ -> null }.search("x"))
        assertEquals(OwnTagSearchResult.Failed, OwnTagSearch(keyed) { _, _ -> throw IOException() }.search("x"))
        val noKey = MutableStateFlow<TmdbKeyState?>(TmdbKeyState(userKey = "", effectiveKey = ""))
        assertEquals(OwnTagSearchResult.Failed, OwnTagSearch(noKey) { _, _ -> error("not asked") }.search("x"))
    }

    @Test
    fun `a blank query asks nothing`() = runTest {
        assertEquals(OwnTagSearchResult.Found(emptyList()), OwnTagSearch(keyed) { _, _ -> error("not asked") }.search("  "))
    }
}
