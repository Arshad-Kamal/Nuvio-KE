package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.ScoutPicksRepository
import com.nuvio.tv.core.scout.ScoutRating
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.core.tmdb.TmdbService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScoutRateViewModelTest {

    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val profileId = MutableStateFlow(1)
    private val keyState = MutableStateFlow<TmdbKeyState?>(TmdbKeyState(userKey = "k", effectiveKey = "k"))
    private val enabled = mapOf(1 to MutableStateFlow(true), 2 to MutableStateFlow(true))
    private val ratings = mapOf(1 to MutableStateFlow<ScoutRating?>(null), 2 to MutableStateFlow<ScoutRating?>(null))
    private val repository = mockk<ScoutPicksRepository> {
        every { enabled(any()) } answers { enabled.getValue(firstArg()) }
        every { rating(any(), "tt1", "tt1", false) } answers { ratings.getValue(firstArg()) }
        // As the real repository (D7): a rating without its TMDB id is not saved.
        coEvery { setRating(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val rating = arg<ScoutRating?>(4)
            if (rating != null && arg<Int?>(6) == null) return@answers false
            ratings.getValue(firstArg()).value = rating
            true
        }
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns profileId }
    private val keys = mockk<TmdbApiKeyProvider> { every { state } returns keyState }
    private var lookupFails = false
    private val tmdb = mockk<TmdbService> {
        coEvery { ensureTmdbId("tt1", "movie") } answers { if (lookupFails) throw IllegalStateException("offline") else "45745" }
    }

    private fun viewModel() = ScoutRateViewModel(repository, profiles, tmdb, keys).apply { bind("tt1", "tt1", false, "Sintel") }

    @Test
    fun `a choice saves for the active profile and the current choice again clears it`() {
        val vm = viewModel()

        vm.choose(ScoutRating.LOVE)
        assertEquals(ScoutRating.LOVE, vm.rating.value)
        coVerify { repository.setRating(1, "tt1", false, "Sintel", ScoutRating.LOVE, "tt1", 45745, any()) }

        vm.choose(ScoutRating.LIKE)
        assertEquals(ScoutRating.LIKE, vm.rating.value)

        vm.choose(ScoutRating.LIKE)
        assertEquals(null, vm.rating.value)
        coVerify { repository.setRating(1, "tt1", false, "Sintel", null, "tt1", null, any()) }
    }

    @Test
    fun `the rating follows the active profile`() {
        val vm = viewModel()
        vm.choose(ScoutRating.NOT_FOR_ME)

        profileId.value = 2
        assertEquals(null, vm.rating.value)
        vm.choose(ScoutRating.LIKE)
        assertEquals(ScoutRating.NOT_FOR_ME, ratings.getValue(1).value)
        assertEquals(ScoutRating.LIKE, ratings.getValue(2).value)
    }

    @Test
    fun `Rate shows only with a TMDB key and Picked for you on (D5)`() {
        val vm = viewModel()
        assertTrue(vm.visible.value)

        keyState.value = TmdbKeyState(userKey = "", effectiveKey = "")
        assertFalse(vm.visible.value)
        keyState.value = null
        assertFalse(vm.visible.value)
        keyState.value = TmdbKeyState(userKey = "k", effectiveKey = "k")
        assertTrue(vm.visible.value)

        enabled.getValue(1).value = false
        assertFalse(vm.visible.value)
        profileId.value = 2
        assertTrue(vm.visible.value)
    }

    @Test
    fun `nothing is saved before a title is bound`() {
        val vm = ScoutRateViewModel(repository, profiles, tmdb, keys)

        vm.choose(ScoutRating.LOVE)

        coVerify(exactly = 0) { repository.setRating(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the TMDB id comes from a tmdb page id with no request, else from the IMDb id`() {
        coEvery { tmdb.ensureTmdbId(any(), any()) } answers { firstArg<String>().removePrefix("tmdb:").removePrefix("tt") }
        every { repository.rating(any(), any(), any(), any()) } returns MutableStateFlow(null)

        ScoutRateViewModel(repository, profiles, tmdb, keys).apply { bind("tmdb:5", "tt1", false, "Sintel") }.choose(ScoutRating.LIKE)
        ScoutRateViewModel(repository, profiles, tmdb, keys).apply { bind("kitsu:9", " tt9 ", true, "Agent 327") }.choose(ScoutRating.LIKE)

        coVerify { tmdb.ensureTmdbId("tmdb:5", "movie") }
        coVerify { repository.setRating(1, "tmdb:5", false, "Sintel", ScoutRating.LIKE, "tt1", 5, any()) }
        coVerify { tmdb.ensureTmdbId("tt9", "tv") }
        coVerify { repository.setRating(1, "kitsu:9", true, "Agent 327", ScoutRating.LIKE, "tt9", 9, any()) }
    }

    @Test
    fun `a failed TMDB lookup saves nothing and leaves Rate as it was, but a clear still goes (D7)`() {
        val vm = viewModel()
        vm.choose(ScoutRating.LIKE)
        lookupFails = true

        vm.choose(ScoutRating.LOVE)
        assertEquals(ScoutRating.LIKE, vm.rating.value)

        vm.choose(ScoutRating.LIKE)
        assertEquals(null, vm.rating.value)
    }
}
