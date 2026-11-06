package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import android.content.Context
import com.nuvio.tv.core.health.AddonHealthLevel
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Old-format-read gate for the Group A batch-1 stores: raw JSON in the exact
 * shape the previous Gson writers produced, seeded into the DataStore slot,
 * read back through each store's public flow (json-consolidation spec, Phase 2).
 */
class LegacyStoreJsonShapeReadTest {

    private fun factory(vararg stores: Pair<String, Preferences>): ProfileDataStoreFactory {
        val dataStores = stores.associate { (feature, prefs) ->
            feature to TestPreferencesDataStore(prefs)
        }
        return mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers { dataStores.getValue(secondArg()) }
        }
    }

    private fun profileManager(): ProfileManager = mockk {
        every { activeProfileId } returns MutableStateFlow(1)
        every { profiles } returns MutableStateFlow(emptyList())
        every { activeProfile } returns null
    }

    @Test
    fun `search history reads gson-written recent searches`() = runTest {
        val store = SearchHistoryDataStore(
            factory = factory(
                "search_history" to preferencesOf(
                    stringPreferencesKey("recent_searches") to
                        """["frieren","Frieren ","dune part two"]"""
                )
            ),
            profileManager = profileManager()
        )

        // Normalization collapses case/blank duplicates keeping first occurrence.
        assertEquals(listOf("frieren", "dune part two"), store.recentSearches.first())
    }

    @Test
    fun `addon preferences read gson-written urls states and names`() = runTest {
        val store = AddonPreferences(
            factory = factory(
                "addon_preferences" to preferencesOf(
                    stringPreferencesKey("installed_addon_urls_ordered") to
                        """["https://v3-cinemeta.strem.io","https://opensubtitles-v3.strem.io"]""",
                    stringPreferencesKey("installed_addon_enabled_states") to
                        """{"https://v3-cinemeta.strem.io":false}""",
                    stringPreferencesKey("addon_user_set_names") to
                        """{"https://v3-cinemeta.strem.io":"Cinemeta"}"""
                )
            ),
            profileManager = profileManager()
        )

        assertEquals(
            listOf("https://v3-cinemeta.strem.io", "https://opensubtitles-v3.strem.io"),
            store.installedAddonUrls.first()
        )
        assertEquals(
            mapOf("https://v3-cinemeta.strem.io" to false),
            store.addonEnabledStates.first()
        )
        assertEquals(
            mapOf("https://v3-cinemeta.strem.io" to "Cinemeta"),
            store.userSetNames.first()
        )
    }

    @Test
    fun `layout preferences read gson-written catalog keys and titles`() = runTest {
        val store = LayoutPreferenceDataStore(
            factory = factory(
                "layout_settings" to preferencesOf(
                    stringPreferencesKey("home_catalog_order_keys") to
                        """["cinemeta.movie","trakt.trending","cinemeta.movie"]""",
                    stringPreferencesKey("disabled_home_catalog_keys") to
                        """["trakt.trending"]""",
                    stringPreferencesKey("custom_catalog_titles") to
                        """{"cinemeta.movie":"Featured"}"""
                )
            ),
            profileManager = profileManager()
        )

        // parseCatalogKeys normalizes: trim, drop empties, distinct, keep order.
        assertEquals(listOf("cinemeta.movie", "trakt.trending"), store.homeCatalogOrderKeys.first())
        assertEquals(listOf("trakt.trending"), store.disabledHomeCatalogKeys.first())
        assertEquals(mapOf("cinemeta.movie" to "Featured"), store.customCatalogTitles.first())
    }

    @Test
    fun `watched series holder reads gson-written revalidation deadlines`() = runTest {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("revalidate_after")] = """{"tv:123":1750000000000}"""
            // Current reset version, so the persisted map is honored.
            this[intPreferencesKey("validation_reset_version")] = 1
        }.toPreferences()
        val holder = WatchedSeriesStateHolder(
            factory = factory("watched_series_cache" to prefs),
            profileManager = profileManager()
        )

        holder.loadFromDisk(1)

        org.junit.Assert.assertTrue(holder.hasBeenValidated("tv:123"))
        org.junit.Assert.assertFalse(holder.hasBeenValidated("tv:999"))
    }

    @Test
    fun `addon health store reads gson-written records`() = runTest {
        // Gson-era shape: one healthy addon record and one tripped resolver breaker.
        val legacy = """{
            "addon:https://fast.example":{
                "samples":[{"atMs":1750000000000,"outcome":"SUCCESS","latencyMs":120}],
                "consecutiveFailures":0,
                "consecutiveSuccesses":1,
                "breakerOpenUntilMs":0
            },
            "resolver:torbox":{"samples":[{"atMs":1750000000000,"outcome":"FAILURE","latencyMs":9000}],"consecutiveFailures":2,"consecutiveSuccesses":0,"breakerOpenUntilMs":1850000000000}
        }"""
        val prefs = preferencesOf(stringPreferencesKey("health_records") to legacy)
        val layout = mockk<LayoutPreferenceDataStore> {
            every { addonHealthEnabled } returns flowOf(true)
        }
        val store = AddonHealthStore(
            factory = factory("addon_health" to prefs),
            profileManager = profileManager(),
            layoutPreferenceDataStore = layout
        )

        val levels = store.levels.first()

        assertEquals(AddonHealthLevel.HEALTHY, levels["addon:https://fast.example"])
        assertEquals(AddonHealthLevel.DOWN, levels["resolver:torbox"])
    }

    @Test
    fun `watched items read gson-written set entries`() = runTest {
        // One full movie entry and one episode entry with nulls omitted
        // (Gson's shape); a malformed element is skipped, not fatal.
        val legacy = setOf(
            """{"contentId":"tt0111161","contentType":"movie","title":"The Shawshank Redemption","watchedAt":1750000000000,"releaseInfo":"1994"}""",
            """{"contentId":"tt0903747","contentType":"series","title":"Breaking Bad","season":1,"episode":2,"watchedAt":1750000100000,"poster":"https://img/p.jpg"}""",
            "not json"
        )
        val prefs = preferencesOf(stringSetPreferencesKey("watched_items") to legacy)
        val store = WatchedItemsPreferences(
            factory = factory("watched_items_preferences" to prefs),
            profileManager = profileManager()
        )

        val items = store.getAllItems(1)

        assertEquals(2, items.size)
        assertTrue(items.any { it.contentId == "tt0111161" && it.season == null })
        assertTrue(items.any { it.contentId == "tt0903747" && it.season == 1 && it.episode == 2 })
    }

    @Test
    fun `library reads gson-written item and pending key entries`() = runTest {
        val legacyItems = setOf(
            """{"id":"tt0111161","type":"movie","name":"The Shawshank Redemption","posterShape":"POSTER","description":"Prison drama","releaseInfo":"1994","imdbRating":9.3,"genres":["Drama"],"logo":"https://img/l.png","addedAt":1750000000000}"""
        )
        val legacyPending = setOf("""{"contentId":"tt0111161","contentType":"movie"}""")
        val prefs = preferencesOf(
            stringSetPreferencesKey("library_items") to legacyItems,
            stringSetPreferencesKey("library_pending_upserts") to legacyPending
        )
        val store = LibraryPreferences(
            factory = factory("library_preferences" to prefs),
            profileManager = profileManager()
        )

        val state = store.getSyncState(1)

        assertEquals(1, state.items.size)
        assertEquals("The Shawshank Redemption", state.items.first().name)
        assertEquals("https://img/l.png", state.items.first().logo)
        assertEquals(1, state.pendingUpsertKeys.size)
    }

    @Test
    fun `cw enrichment cache reads gson-written snapshot file`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("cw-cache").toFile()
        val context = mockk<Context> {
            every { filesDir } returns dir
        }
        val legacy = """[
            {"contentId":"tt0903747","contentType":"series","name":"Breaking Bad",
             "backdrop":"https://img/b.jpg","videoId":"tt0903747:1:2","season":1,"episode":2,
             "episodeTitle":"Cat's in the Bag...","thumbnail":"https://img/t.jpg","released":"2008-01-27",
             "hasAired":true,"airDateLabel":"Jan 27, 2008","lastWatched":1750000000000,"imdbRating":9.5,
             "genres":["Drama"],"sortTimestamp":1750000000000,"isNewSeasonRelease":false}
        ]"""
        File(File(dir, "cw_enrichment").apply { mkdirs() }, "nextup_1.json").writeText(legacy)
        val cache = ContinueWatchingEnrichmentCache(
            context = context,
            profileManager = profileManager()
        )

        val snapshot = cache.getNextUpSnapshot()

        assertEquals(1, snapshot.size)
        assertEquals("tt0903747", snapshot.first().contentId)
        assertEquals("Cat's in the Bag...", snapshot.first().episodeTitle)
        assertEquals(9.5f, snapshot.first().imdbRating)
    }

    @Test
    fun `watch progress reads lenient legacy entry shapes`() = runTest {
        // Metadata-store seeding drives parseProgressMap through the bucket
        // migration: camelCase, double-encoded string, snake_case, and a
        // numeric-string entry must all parse; one malformed entry is skipped.
        val doubleEncoded =
            """{"contentId":"tt0068646","contentType":"movie","name":"The Godfather","videoId":"tt0068646","lastWatched":1750000300000}"""
                .replace("\"", "\\\"")
        val legacy = """{
            "tt0111161": {"contentId":"tt0111161","contentType":"movie","name":"The Shawshank Redemption","videoId":"tt0111161","lastWatched":1750000000000},
            "tt0068646": "$doubleEncoded",
            "tt0306414": {"content_id":"tt0306414","content_type":"series","name":"The Wire","video_id":"tt0306414","last_watched":1750000400000},
            "tt0110912": {"contentId":"tt0110912","contentType":"movie","name":"Pulp Fiction","videoId":"tt0110912","lastWatched":"1750000500000"},
            "bad": "not json"
        }"""
        val prefs = preferencesOf(stringPreferencesKey("watch_progress_map") to legacy)
        val store = WatchProgressPreferences(
            factory = factory(
                "watch_progress_preferences" to prefs,
                "watch_progress_recent_preferences" to preferencesOf(),
                "watch_progress_archive_preferences" to preferencesOf()
            ),
            profileManager = profileManager()
        )

        val entries = store.getAllRawEntries(1)

        assertEquals(4, entries.size)
        assertTrue(entries.containsKey("tt0068646"))
        assertEquals("The Wire", entries["tt0306414"]?.name)
        assertEquals(1750000500000L, entries["tt0110912"]?.lastWatched)
    }

    @Test
    fun `layout catalog keys migrate from legacy string set slot`() = runTest {
        // Pre-JSON persistence stored these keys as a Set; the CCE recovery
        // path must convert through the shared string-list encoder.
        val prefs = preferencesOf(
            stringSetPreferencesKey("home_catalog_order_keys") to
                setOf("cinemeta.movie", "trakt.trending")
        )
        val store = LayoutPreferenceDataStore(
            factory = factory("layout_settings" to prefs),
            profileManager = profileManager()
        )

        assertEquals(listOf("cinemeta.movie", "trakt.trending"), store.homeCatalogOrderKeys.first())
    }

    @Test
    fun `addon urls fall back to legacy set slot`() = runTest {
        val prefs = preferencesOf(
            stringSetPreferencesKey("installed_addon_urls") to
                setOf("https://v3-cinemeta.strem.io")
        )
        val store = AddonPreferences(
            factory = factory("addon_preferences" to prefs),
            profileManager = profileManager()
        )

        val urls = store.installedAddonUrls.first()

        assertTrue(urls.any { it.contains("cinemeta") })
    }
}
