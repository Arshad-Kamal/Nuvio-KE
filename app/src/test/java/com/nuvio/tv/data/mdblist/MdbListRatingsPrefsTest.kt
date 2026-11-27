package com.nuvio.tv.data.mdblist

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.nuvio.tv.data.mdblist.MdbListRatingSource.IMDB
import com.nuvio.tv.data.mdblist.MdbListRatingSource.LETTERBOXD
import com.nuvio.tv.data.mdblist.MdbListRatingSource.MAL
import com.nuvio.tv.data.mdblist.MdbListRatingSource.METACRITIC
import com.nuvio.tv.data.mdblist.MdbListRatingSource.POPCORN
import com.nuvio.tv.data.mdblist.MdbListRatingSource.TMDB
import com.nuvio.tv.data.mdblist.MdbListRatingSource.TOMATOES
import com.nuvio.tv.data.mdblist.MdbListRatingSource.TRAKT
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S4 B1/B2: a profile's ratings switch, source order and off set as stored. */
class MdbListRatingsPrefsTest {

    @Test
    fun `nothing stored = switch unset (on with a key), B2's order, the first six shown`() {
        val settings = readMdbListRatingsSettings(emptyPreferences())
        assertNull(settings.enabled)
        assertTrue(settings.onWith(connected = true))
        assertFalse(settings.onWith(connected = false))
        assertEquals(MdbListRatingSource.entries, settings.order)
        assertEquals(listOf(IMDB, TOMATOES, POPCORN, METACRITIC, LETTERBOXD, TMDB), settings.shown)
    }

    @Test
    fun `stored values win, an empty off set shows all, switched off stays off`() {
        val settings = readMdbListRatingsSettings(
            preferencesOf(
                MdbListRatingsPrefs.ENABLED to false,
                MdbListRatingsPrefs.ORDER to "TMDB,IMDB,MAL,TRAKT,LETTERBOXD,METACRITIC,POPCORN,TOMATOES",
                MdbListRatingsPrefs.OFF to ""
            )
        )
        assertFalse(settings.onWith(connected = true))
        assertEquals(listOf(TMDB, IMDB, MAL, TRAKT, LETTERBOXD, METACRITIC, POPCORN, TOMATOES), settings.shown)
    }

    @Test
    fun `a stored order drops unknown and repeated names and adds missing sources at the end`() {
        assertEquals(
            listOf(TRAKT, IMDB, TOMATOES, POPCORN, METACRITIC, LETTERBOXD, TMDB, MAL),
            decodeMdbListOrder("TRAKT,ROTTEN,IMDB,TRAKT")
        )
    }

    @Test
    fun `writes land on the profile they name and read back through active`() = runTest {
        val stores = mutableMapOf<Int, TestPreferencesDataStore>()
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), MdbListRatingsPrefs.FEATURE) } answers { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
        }
        val active = MutableStateFlow(1)
        val prefs = MdbListRatingsPrefs(factory, mockk<ProfileManager> { every { activeProfileId } returns active })
        prefs.setEnabled(1, false)
        prefs.toggleSource(1, TRAKT) // off by default: on
        prefs.toggleSource(1, IMDB) // two presses in a row both count
        prefs.move(1, TOMATOES, -1)
        val one = prefs.active.first()
        assertFalse(one.onWith(connected = true))
        assertEquals(listOf(TOMATOES, IMDB), one.order.take(2))
        assertEquals(setOf(MAL, IMDB), one.off)
        active.value = 2
        assertEquals(MdbListRatingsSettings.DEFAULT_OFF, prefs.active.first().off)
    }

    @Test
    fun `move swaps with the neighbour and does nothing at the ends`() {
        val order = MdbListRatingSource.entries
        assertEquals(listOf(TOMATOES, IMDB) + order.drop(2), moveMdbListSource(order, IMDB, 1))
        assertEquals(listOf(TOMATOES, IMDB) + order.drop(2), moveMdbListSource(order, TOMATOES, -1))
        assertEquals(order, moveMdbListSource(order, IMDB, -1))
        assertEquals(order, moveMdbListSource(order, MAL, 1))
    }
}
