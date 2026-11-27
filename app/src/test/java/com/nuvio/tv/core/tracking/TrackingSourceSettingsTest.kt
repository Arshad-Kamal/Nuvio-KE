package com.nuvio.tv.core.tracking

import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.shouldExcludePreferenceFromProfileSettingsSync
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.simkl.RelatedTitlesSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The source choices in ContinueWatchingSettingsDataStore (Spec 104 S1e, D10). */
class TrackingSourceSettingsTest {

    private val profiles = mutableMapOf(1 to TestPreferencesDataStore(), 2 to TestPreferencesDataStore())
    private val activeIdFlow = MutableStateFlow(1)
    private val settings = ContinueWatchingSettingsDataStore(
        factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), ContinueWatchingSettingsDataStore.FEATURE) } answers { profiles.getValue(firstArg()) }
        },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeIdFlow }
    )

    @Test
    fun `source choices go into the synced profile settings (D10)`() {
        listOf(ContinueWatchingSettingsDataStore.PROGRESS_SOURCE_KEY, ContinueWatchingSettingsDataStore.LIBRARY_SOURCE_KEY).forEach { key ->
            assertFalse(key, shouldExcludePreferenceFromProfileSettingsSync(ContinueWatchingSettingsDataStore.FEATURE, key))
        }
    }

    @Test
    fun `Simkl's anime mode is kept per profile, synced, and never read from the pre-cut key`() = runTest {
        profiles.getValue(1).updateData { prefs ->
            prefs.toMutablePreferences().apply { this[stringPreferencesKey("simkl_anime_id_preference")] = "MAL" }
        }
        assertEquals(com.nuvio.tv.data.simkl.SimklAnimeSeasons.PER_SHOW, settings.simklAnimeSeasons(1).first())
        settings.setSimklAnimeSeasons(com.nuvio.tv.data.simkl.SimklAnimeSeasons.PER_SEASON, 2)
        assertEquals(com.nuvio.tv.data.simkl.SimklAnimeSeasons.PER_SEASON, settings.simklAnimeSeasons(2).first())
        assertEquals(com.nuvio.tv.data.simkl.SimklAnimeSeasons.PER_SHOW, settings.activeSimklAnimeSeasons.first())
        activeIdFlow.value = 2
        assertEquals(com.nuvio.tv.data.simkl.SimklAnimeSeasons.PER_SEASON, settings.activeSimklAnimeSeasons.first())
        assertEquals("season", profiles.getValue(2).value[stringPreferencesKey("simkl_anime_seasons")])
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync(ContinueWatchingSettingsDataStore.FEATURE, "simkl_anime_seasons"))
    }

    @Test
    fun `the More like this source is kept per profile, synced, and never read from the pre-cut key`() = runTest {
        profiles.getValue(1).updateData { prefs ->
            prefs.toMutablePreferences().apply { this[stringPreferencesKey("more_like_this_source")] = "simkl" }
        }
        assertEquals(RelatedTitlesSource.TMDB, settings.relatedTitlesSource.first())
        settings.setRelatedTitlesSource(RelatedTitlesSource.SIMKL, 2)
        assertEquals(RelatedTitlesSource.TMDB, settings.relatedTitlesSource.first())
        activeIdFlow.value = 2
        assertEquals(RelatedTitlesSource.SIMKL, settings.relatedTitlesSource.first())
        assertEquals("simkl", profiles.getValue(2).value[stringPreferencesKey("tracking_more_like_this_source")])
        assertFalse(
            shouldExcludePreferenceFromProfileSettingsSync(ContinueWatchingSettingsDataStore.FEATURE, "tracking_more_like_this_source")
        )
    }

    @Test
    fun `both sources start as Nuvio, even over a pre-cut choice`() = runTest {
        profiles.getValue(1).updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[stringPreferencesKey("watch_progress_source")] = "SIMKL"
                this[stringPreferencesKey("library_source_mode")] = "MDBLIST"
            }
        }
        assertEquals(TrackingSource.NUVIO, settings.getProgressSource(1))
        assertEquals(TrackingSource.NUVIO, settings.librarySource.first())
    }

    @Test
    fun `choices are kept per profile and follow the active profile`() = runTest {
        settings.setProgressSource(TrackingSource.MDBLIST, 1)
        settings.setLibrarySource(TrackingSource.SIMKL, 2)

        assertEquals(TrackingSource.MDBLIST, settings.progressSource.first())
        assertEquals(TrackingSource.NUVIO, settings.librarySource.first())
        assertEquals("mdblist", profiles.getValue(1).value[stringPreferencesKey("tracking_progress_source")])

        activeIdFlow.value = 2
        assertEquals(TrackingSource.NUVIO, settings.progressSource.first())
        assertEquals(TrackingSource.SIMKL, settings.librarySource.first())
        assertEquals(TrackingSource.SIMKL, settings.getLibrarySource(2))
    }
}
