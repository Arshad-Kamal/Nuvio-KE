package com.nuvio.tv.core.streams

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.YieldingPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Spec 130 round 2: StreamBadgeSettingsDataStore's rules decode memo (DEFER) and atomic update (F4). */
class StreamBadgeSettingsStoreTest {

    private val key = stringPreferencesKey("stream_badge_rules")

    private fun storeOver(backing: TestPreferencesDataStore): StreamBadgeSettingsDataStore {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns backing
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return StreamBadgeSettingsDataStore(factory, profileManager)
    }

    private fun import(name: String, pattern: String = name) = StreamBadgeImport(
        sourceUrl = "https://example.com/$name.json",
        filters = listOf(StreamBadgeFilter(name = name, pattern = pattern))
    )

    private fun rulesJson(pattern: String): String =
        Json.encodeToString(StreamBadgeRules(imports = listOf(import("Badge", pattern))))

    @Test
    fun `unchanged stored rules decode once and a change decodes anew`() = runTest {
        val backing = TestPreferencesDataStore(preferencesOf(key to rulesJson("DV")))
        val store = storeOver(backing)
        val first = store.settings.first().rules
        // A new String with the same content, as a later DataStore read yields.
        backing.updateData { preferencesOf(key to String(rulesJson("DV").toCharArray())) }
        val second = store.settings.first().rules

        backing.updateData { preferencesOf(key to rulesJson("Atmos")) }
        val changed = store.settings.first().rules

        assertSame(first, second)
        assertNotSame(first, changed)
        assertEquals("Atmos", changed.imports.single().filters.single().pattern)
    }

    @Test
    fun `no stored rules reads as empty`() = runTest {
        val store = storeOver(TestPreferencesDataStore())
        val first = store.settings.first().rules

        assertEquals(StreamBadgeRules(), first)
        // One shared instance, so the presentation keeps its identity fast path.
        assertSame(first, store.settings.first().rules)
    }

    // F4: two deletes ~50 ms apart; each must see the other's write, so neither pack comes back.
    @Test
    fun `interleaved updates each apply to the previous write`() = runTest {
        val store = storeOver(YieldingPreferencesDataStore())
        store.setStreamBadgeRules(StreamBadgeRules(imports = listOf(import("A"), import("B"), import("C"))))

        listOf("A", "B").map { name ->
            async { store.updateStreamBadgeRules { it.removeSource("https://example.com/$name.json") } }
        }.awaitAll()

        assertEquals(
            listOf("https://example.com/C.json"),
            store.settings.first().rules.imports.map { it.sourceUrl }
        )
    }

    // An update over the legacy single-import shape keeps that import and writes the new format.
    @Test
    fun `update over legacy stored rules keeps the legacy import`() = runTest {
        val legacy = """{"sourceUrl":"https://example.com/legacy.json","filters":[{"name":"L","pattern":"L"}],"groups":[]}"""
        val backing = TestPreferencesDataStore(preferencesOf(key to legacy))
        val store = storeOver(backing)

        store.updateStreamBadgeRules { it.upsert(import("A")) }

        assertEquals(
            listOf("https://example.com/legacy.json", "https://example.com/A.json"),
            store.settings.first().rules.imports.map { it.sourceUrl }
        )
        assertEquals(true, backing.value[key]!!.contains("\"imports\""))
    }

    @Test
    fun `update returns the saved normalized rules`() = runTest {
        val store = storeOver(TestPreferencesDataStore())

        val saved = store.updateStreamBadgeRules { it.upsert(import("A")) }

        assertEquals(saved, store.settings.first().rules)
        assertEquals(true, saved.imports.single().isActive)
    }
}
