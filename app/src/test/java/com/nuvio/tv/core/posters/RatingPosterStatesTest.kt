package com.nuvio.tv.core.posters

import com.nuvio.tv.core.posters.RatingPosterState.Loading
import com.nuvio.tv.core.posters.RatingPosterState.Off
import com.nuvio.tv.core.posters.RatingPosterState.On
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RatingPosterStatesTest {

    private val raw1 = "https://pp.example.com/poster?imdb_id={imdb_id}&access_key=one"
    private val raw3 = "https://pp.example.com/poster?imdb_id={imdb_id}&access_key=three"

    private fun on(raw: String) = On((RatingPosterTemplate.parse(raw) as RatingPosterTemplate.Parsed.Valid).template)

    @Test
    fun `no profile id yet is Loading, never the default profile's template`() = runTest {
        val ids = MutableStateFlow<Int?>(null)
        val seen = mutableListOf<RatingPosterState>()
        val reads = mutableListOf<Int>()
        ratingPosterStates(ids, { id -> reads += id; flowOf(if (id == 1) raw1 else raw3) })
            .onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        assertEquals(listOf<RatingPosterState>(Loading), seen)
        ids.value = 3
        runCurrent()
        assertEquals(listOf(Loading, on(raw3)), seen)
        assertEquals(listOf(3), reads)
    }

    @Test
    fun `profile switch passes through Loading`() = runTest {
        val ids = MutableStateFlow<Int?>(1)
        val seen = mutableListOf<RatingPosterState>()
        ratingPosterStates(ids, { id -> flowOf(if (id == 1) raw1 else null) })
            .onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        ids.value = 2
        runCurrent()
        assertEquals(listOf(Loading, on(raw1), Loading, Off), seen)
    }

    @Test
    fun `a read error is Off and later switches still update`() = runTest {
        val ids = MutableStateFlow<Int?>(1)
        val seen = mutableListOf<RatingPosterState>()
        ratingPosterStates(ids, { id ->
            if (id == 1) flow { throw IOException("disk") } else flowOf(raw3)
        }).onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        ids.value = 3
        runCurrent()
        assertEquals(listOf(Loading, Off, Loading, on(raw3)), seen)
    }

    @Test
    fun `a slow first read shows Off, then the late value still wins`() = runTest {
        val seen = mutableListOf<RatingPosterState>()
        ratingPosterStates(flowOf(1), { flow { delay(2_500); emit(raw1) } })
            .onEach { seen += it }.launchIn(backgroundScope)
        // advanceUntilIdle skips backgroundScope work, so time is advanced explicitly.
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf(Loading, Off), seen)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Loading, Off, on(raw1)), seen)
    }

    @Test
    fun `a switch during a slow first read never lets the old timer publish Off`() = runTest {
        val ids = MutableStateFlow<Int?>(1)
        val seen = mutableListOf<RatingPosterState>()
        ratingPosterStates(ids, { id -> if (id == 1) flow { delay(10_000); emit(raw1) } else flowOf(raw3) })
            .onEach { seen += it }.launchIn(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        ids.value = 3
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(Loading, on(raw3)), seen)
    }

    @Test
    fun `value and timeout on the same tick end at the value`() = runTest {
        val seen = mutableListOf<RatingPosterState>()
        ratingPosterStates(flowOf(1), { flow { delay(2_000); emit(raw1) } })
            .onEach { seen += it }.launchIn(backgroundScope)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(on(raw1), seen.last())
    }

    @Test
    fun `an io error reads again, so a later save shows`() = runTest {
        val seen = mutableListOf<RatingPosterState>()
        var attempts = 0
        ratingPosterStates(flowOf(1), {
            flow {
                attempts++
                if (attempts == 1) throw IOException("disk")
                emit(raw1)
            }
        }).onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        assertEquals(listOf(Loading, Off), seen)
        advanceTimeBy(READ_RETRY_MS + 1)
        runCurrent()
        assertEquals(listOf(Loading, Off, on(raw1)), seen)
    }

        @Test
    fun `a stored template that no longer parses reads as Off`() = runTest {
        val seen = mutableListOf<RatingPosterState>()
        ratingPosterStates(flowOf(1), { flowOf("http://posters.example.net/{imdb_id}") })
            .onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        assertEquals(listOf(Loading, Off), seen)
        assertTrue(seen.none { it is On })
    }
}
