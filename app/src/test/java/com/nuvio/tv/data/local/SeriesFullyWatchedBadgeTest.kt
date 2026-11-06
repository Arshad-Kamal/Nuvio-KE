package com.nuvio.tv.data.local

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import kotlin.concurrent.thread

/** L5-03: the Details series badge write must not clobber a concurrent badge update. */
class SeriesFullyWatchedBadgeTest {

    @Test
    fun `a badge update landing between the snapshot and the write survives`() {
        val holder = holder()
        // Warm-up write so class loading cannot block the writer thread below.
        holder.setSeriesFullyWatched(setOf("warmup"), fullyWatched = true)

        lateinit var writer: Thread
        synchronized(holder) {
            // The writer takes its snapshot, then blocks on the holder's lock held here.
            writer = thread { holder.setSeriesFullyWatched(setOf("tt1"), fullyWatched = true) }
            awaitBlocked(writer)
            // A Home/CW update to another series lands in the gap (re-entrant lock).
            holder.transformWithValidation(validatedIds = emptySet()) { it + "home" }
        }
        writer.join(TIMEOUT_MS)

        assertEquals(setOf("warmup", "home", "tt1"), holder.fullyWatchedSeriesIds.value)
    }

    @Test
    fun `unwatching removes only the given ids`() {
        val holder = holder()
        holder.transformWithValidation(validatedIds = emptySet()) { setOf("other", "tt1", "tmdb:1") }

        holder.setSeriesFullyWatched(setOf("tt1", "tmdb:1"), fullyWatched = false)

        assertEquals(setOf("other"), holder.fullyWatchedSeriesIds.value)
    }

    @Test
    fun `an unchanged badge set skips the write and its validation stamp`() {
        val holder = holder()
        holder.transformWithValidation(validatedIds = emptySet()) { setOf("tt1") }

        holder.setSeriesFullyWatched(setOf("tt1"), fullyWatched = true)

        assertFalse(holder.hasBeenValidated("tt1"))
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (thread.state != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) fail("writer never blocked on the holder lock (state=${thread.state})")
            Thread.yield()
        }
    }

    private fun holder(): WatchedSeriesStateHolder {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns TestPreferencesDataStore()
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return WatchedSeriesStateHolder(factory, profileManager)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
