package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingLibraryEntry
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRateLimitException
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.core.tracking.TrackingSource.MDBLIST
import com.nuvio.tv.core.tracking.TrackingSource.NUVIO
import com.nuvio.tv.core.tracking.TrackingSource.SIMKL
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 104 S4c: the transfer service over a fake tracker and the real local library + account stores. */
class LibraryTransferServiceTest {

    private val stores = mutableMapOf<Pair<Int, String>, TestPreferencesDataStore>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg()) { TestPreferencesDataStore() } }
    }
    private val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(ACTIVE) }
    private val library = LibraryPreferences(factory, profileManager)
    private val accounts = TrackingAccountStore(factory, profileManager)
    private val mdblist = FakeLibraryProvider(TrackingProviderId.MDBLIST)
    private val simkl = FakeLibraryProvider(TrackingProviderId.SIMKL)

    private fun TestScope.service() = LibraryTransferService(
        library,
        TrackingLibraryProviderRegistry(setOf(mdblist, simkl)),
        accounts,
        this
    )

    private suspend fun connect(id: TrackingProviderId, name: String? = "arshadk") =
        accounts.save(PROFILE, id, TrackingAccount("token", accountName = name))

    private suspend fun addLocal(id: String, type: String = "movie") =
        library.addItem(SavedLibraryItem(id, type, id, null, PosterShape.POSTER, null, null, null, null, emptyList(), null), PROFILE)

    private suspend fun localIds() = library.getAllItems(PROFILE).map { it.id }.sorted()

    private fun LibraryTransferService.result() = (state.value as LibraryTransferState.Finished).result

    private suspend fun TestScope.transfer(
        service: LibraryTransferService,
        from: TrackingSource,
        to: TrackingSource,
        mode: LibraryTransferMode,
        beforeStart: suspend () -> Unit = {}
    ): LibraryTransferResult {
        val preview = service.preview(PROFILE, from, to)
        beforeStart()
        assertTrue(service.start(preview, mode))
        advanceUntilIdle()
        return service.result()
    }

    @Test
    fun `directions never offer Simkl as To, and Move only from Nuvio`() {
        assertEquals(listOf(MDBLIST), transferDestinations(NUVIO, setOf(MDBLIST, SIMKL)))
        assertEquals(listOf(NUVIO, MDBLIST), transferDestinations(SIMKL, setOf(MDBLIST, SIMKL)))
        assertEquals(listOf(SIMKL), transferSources(setOf(SIMKL)))
        assertEquals(listOf(NUVIO, MDBLIST, SIMKL), transferSources(setOf(MDBLIST, SIMKL)))
        assertTrue(transferMoveAllowed(NUVIO))
        assertFalse(transferMoveAllowed(MDBLIST))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a preview into Simkl is refused`() = runTest {
        connect(TrackingProviderId.SIMKL)
        service().preview(PROFILE, NUVIO, SIMKL)
    }

    @Test
    fun `a copy into a tracker writes what is missing and counts Copied from the destination read`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1"); addLocal("tmdb:2"); addLocal("tt3"); addLocal("48213")
        mdblist.library += tracked("tt3")
        mdblist.rejected += "tmdb:2"

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.COPY)

        assertEquals(LibraryTransferOutcome.DONE, result.outcome)
        assertEquals(LibraryTransferCounts(copied = 1, alreadyThere = 1, couldNotCopy = 1, notCopiedYet = 0), result.counts)
        assertEquals(listOf("tt1", "tmdb:2"), mdblist.attempted)
        assertEquals(listOf("48213", "tmdb:2", "tt1", "tt3"), localIds())
        assertFalse(result.checkFailed)
    }

    @Test
    fun `a copy into Nuvio writes the tt id before tmdb, typed movie or series`() = runTest {
        connect(TrackingProviderId.SIMKL)
        simkl.library += tracked("tmdb:603", "movie", "tt0133093")
        simkl.library += tracked("tmdb:1399", "anime")
        addLocal("tt9")

        val result = transfer(service(), SIMKL, NUVIO, LibraryTransferMode.COPY)

        assertEquals(LibraryTransferCounts(copied = 2, alreadyThere = 0, couldNotCopy = 0, notCopiedYet = 0), result.counts)
        val saved = library.getAllItems(PROFILE).associate { it.id to it.type }
        assertEquals(mapOf("tt9" to "movie", "tt0133093" to "movie", "tmdb:1399" to "series"), saved)
        assertEquals(listOf("tmdb:603", "tmdb:1399"), simkl.library.map { it.entry.id })
    }

    @Test
    fun `a move removes every entry of the titles the destination lists, as pending deletes`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1"); addLocal("tt2"); addLocal("tmdb:22"); addLocal("tt4"); addLocal("kitsu:5", "anime")
        mdblist.library += tracked("tt2", "movie", "tmdb:22")
        mdblist.rejected += "tt4"

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferOutcome.DONE, result.outcome)
        assertEquals(
            LibraryTransferCounts(copied = 1, alreadyThere = 1, couldNotCopy = 1, notCopiedYet = 0, removed = 2, notRemoved = 1),
            result.counts
        )
        assertEquals(listOf("kitsu:5", "tt4"), localIds())
        val pendingDeletes = library.getSyncState(PROFILE).pendingDeleteKeys.map { it.contentId }.sorted()
        assertEquals(listOf("tmdb:22", "tt1", "tt2"), pendingDeletes)
    }

    @Test
    fun `a move never removes a title its confirm did not name`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1")
        val service = service()

        val result = transfer(service, NUVIO, MDBLIST, LibraryTransferMode.MOVE) { addLocal("tt7") }

        assertEquals(listOf("tt1", "tt7"), mdblist.attempted)
        // tt7 was copied but never named: neither removed nor counted Not removed (Removed + Not removed = the confirm).
        assertEquals(1, result.counts?.removed)
        assertEquals(0, result.counts?.notRemoved)
        assertEquals(listOf("tt7"), localIds())
    }

    @Test
    fun `five failed titles in a row stop the run, and a stopped move removes nothing`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        (1..12).forEach { addLocal("tt$it") }
        mdblist.rejected += (1..12).map { "tt$it" }

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferOutcome.STOPPED_AFTER_ERRORS, result.outcome)
        assertEquals(LibraryTransferStopReason.FAILURES, result.reason)
        assertEquals(LibraryTransferCounts(copied = 0, alreadyThere = 0, couldNotCopy = 10, notCopiedYet = 2), result.counts)
        assertEquals(12, localIds().size)
    }

    @Test
    fun `a rate limit stops the run with its reason`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        (1..12).forEach { addLocal("tt$it") }
        mdblist.rateLimitAt = "tt3"

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.COPY)

        assertEquals(LibraryTransferStopReason.RATE_LIMIT, result.reason)
        assertEquals(MDBLIST, result.failedEnd)
        // tt1 and tt2 went in, tt3 got the slow-down, tt4-tt12 were never sent.
        assertEquals(LibraryTransferCounts(copied = 2, alreadyThere = 0, couldNotCopy = 1, notCopiedYet = 9), result.counts)
    }

    @Test
    fun `Stop keeps what was copied and reports the rest as not copied yet`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        (1..25).forEach { addLocal("tt$it") }
        val service = service()
        mdblist.onAdd = { service.stop() }

        val result = transfer(service, NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferOutcome.STOPPED, result.outcome)
        assertEquals(LibraryTransferCounts(copied = 10, alreadyThere = 0, couldNotCopy = 0, notCopiedYet = 15), result.counts)
        assertEquals(25, localIds().size)
    }

    @Test
    fun `another tracker account mid-run stops before the next write and removes nothing`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        (1..15).forEach { addLocal("tt$it") }
        mdblist.onAdd = { connect(TrackingProviderId.MDBLIST, name = "someone-else") }

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferStopReason.ACCOUNT_CHANGED, result.reason)
        assertTrue(result.checkFailed)
        assertEquals(10, mdblist.attempted.size)
        assertEquals(LibraryTransferCounts(copied = 10, alreadyThere = 0, couldNotCopy = 0, notCopiedYet = 5), result.counts)
        assertEquals(15, localIds().size)
    }

    @Test
    fun `a move into a tracker with no account name is refused at Start`() = runTest {
        connect(TrackingProviderId.MDBLIST, name = null)
        addLocal("tt1")

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferStopReason.ACCOUNT_UNCONFIRMED, result.reason)
        assertNull(result.counts)
        assertTrue(mdblist.attempted.isEmpty())
    }

    @Test
    fun `a failed read at Start writes nothing`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1")
        val service = service()

        val result = transfer(service, NUVIO, MDBLIST, LibraryTransferMode.COPY) { mdblist.readFailure = IOException() }

        assertEquals(LibraryTransferStopReason.READ_FAILED, result.reason)
        assertNull(result.counts)
        assertTrue(mdblist.attempted.isEmpty())
    }

    @Test
    fun `a failed read after the copy removes nothing and says the check failed`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1"); addLocal("tt2")
        mdblist.onAdd = { mdblist.readFailure = IOException() }

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferOutcome.DONE, result.outcome)
        assertTrue(result.checkFailed)
        assertEquals(LibraryTransferCounts(copied = 2, alreadyThere = 0, couldNotCopy = 0, notCopiedYet = 0, notRemoved = 2), result.counts)
        assertEquals(listOf("tt1", "tt2"), localIds())
    }

    @Test
    fun `another account during the destination read removes nothing`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1"); addLocal("tt2")
        // Reads: the preview, Start, then the check after the copy.
        mdblist.onRead = { n -> if (n == 3) connect(TrackingProviderId.MDBLIST, name = "someone-else") }

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferStopReason.ACCOUNT_CHANGED, result.reason)
        assertEquals(MDBLIST, result.failedEnd)
        assertTrue(result.checkFailed)
        assertEquals(listOf("tt1", "tt2"), localIds())
    }

    @Test
    fun `Stop pressed on the last title still removes nothing`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1"); addLocal("tt2")
        val service = service()
        mdblist.onAdd = { if (mdblist.attempted.last() == "tt2") service.stop() }

        val result = transfer(service, NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferOutcome.STOPPED, result.outcome)
        assertEquals(LibraryTransferCounts(copied = 2, alreadyThere = 0, couldNotCopy = 0, notCopiedYet = 0), result.counts)
        assertEquals(listOf("tt1", "tt2"), localIds())
    }

    @Test
    fun `a failed removal edit removes nothing and says so`() = runTest {
        val failing = FailingWritesDataStore()
        stores[PROFILE to "library_preferences"] = failing
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1")
        mdblist.onRead = { n -> if (n == 3) failing.failWrites = true }

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.MOVE)

        assertEquals(LibraryTransferStopReason.REMOVE_FAILED, result.reason)
        assertEquals(LibraryTransferCounts(copied = 1, alreadyThere = 0, couldNotCopy = 0, notCopiedYet = 0, notRemoved = 1), result.counts)
        assertEquals(listOf("tt1"), localIds())
    }

    @Test
    fun `failures stop only when five titles in a row fail, across batches too`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        (1..9).forEach { addLocal("tt$it") }
        mdblist.rejected += listOf("tt1", "tt2", "tt3", "tt4", "tt6", "tt7", "tt8", "tt9")

        val apart = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.COPY)
        assertEquals(LibraryTransferOutcome.DONE, apart.outcome)
        assertEquals(LibraryTransferCounts(copied = 1, alreadyThere = 0, couldNotCopy = 8, notCopiedYet = 0), apart.counts)
    }

    @Test
    fun `five failures spanning two batches stop after the second`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        (10..34).forEach { addLocal("tt$it") }
        // tt17-tt19 end the first batch, tt20-tt21 open the second; tt22 succeeds again, but five in a row already failed.
        mdblist.rejected += listOf("tt17", "tt18", "tt19", "tt20", "tt21")

        val result = transfer(service(), NUVIO, MDBLIST, LibraryTransferMode.COPY)

        assertEquals(LibraryTransferStopReason.FAILURES, result.reason)
        assertEquals(LibraryTransferCounts(copied = 15, alreadyThere = 0, couldNotCopy = 5, notCopiedYet = 5), result.counts)
    }

    @Test
    fun `a tracker that never answers ends the run on the read bound`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1")
        val service = service()

        val result = transfer(service, NUVIO, MDBLIST, LibraryTransferMode.COPY) { mdblist.hangReads = true }

        assertEquals(LibraryTransferStopReason.READ_FAILED, result.reason)
        assertEquals(MDBLIST, result.failedEnd)
        assertTrue(mdblist.attempted.isEmpty())
    }

    @Test
    fun `one transfer at a time, and no move from a tracker`() = runTest {
        connect(TrackingProviderId.MDBLIST)
        addLocal("tt1")
        val service = service()
        val preview = service.preview(PROFILE, NUVIO, MDBLIST)
        val fromTracker = service.preview(PROFILE, MDBLIST, NUVIO)

        assertFalse(service.start(fromTracker, LibraryTransferMode.MOVE))
        assertTrue(service.start(preview, LibraryTransferMode.COPY))
        assertFalse(service.start(preview, LibraryTransferMode.COPY))
        advanceUntilIdle()
        assertTrue(service.state.value is LibraryTransferState.Finished)
        service.dismiss()
        assertEquals(LibraryTransferState.Idle, service.state.value)
    }

    private class FailingWritesDataStore : TestPreferencesDataStore() {
        var failWrites = false

        override suspend fun updateData(
            transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
        ): androidx.datastore.preferences.core.Preferences {
            if (failWrites) throw IOException("disk full")
            return super.updateData(transform)
        }
    }

    private class FakeLibraryProvider(override val providerId: TrackingProviderId) : TrackingLibraryProvider {
        val library = mutableListOf<TrackingLibraryEntry>()
        val attempted = mutableListOf<String>()
        val rejected = mutableSetOf<String>()
        var rateLimitAt: String? = null
        var readFailure: Exception? = null
        var onAdd: suspend () -> Unit = {}
        var reads = 0
        var onRead: suspend (Int) -> Unit = {}
        var hangReads = false

        override val isRefreshing: Flow<Boolean> = flowOf(false)
        override val items: Flow<List<LibraryEntry>> = flowOf(emptyList())
        override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> = flowOf(false)
        override suspend fun refresh(intent: TrackingRefreshIntent) = Unit

        override suspend fun setInLibrary(profileId: Int, item: LibraryEntryInput, inLibrary: Boolean) {
            assertEquals(PROFILE, profileId)
            attempted += item.itemId
            onAdd()
            if (item.itemId == rateLimitAt) throw TrackingRateLimitException()
            if (item.itemId in rejected) throw IOException("rejected")
            library += tracked(item.itemId, item.itemType)
        }

        override suspend fun readLibrary(profileId: Int): List<TrackingLibraryEntry> {
            assertEquals(PROFILE, profileId)
            onRead(++reads)
            if (hangReads) awaitCancellation()
            readFailure?.let { throw it }
            return library.toList()
        }
    }

    private companion object {
        const val PROFILE = 2
        const val ACTIVE = 1

        fun tracked(id: String, type: String = "movie", vararg ids: String) = TrackingLibraryEntry(
            LibraryEntry(
                id = id, type = type, name = id, poster = null, background = null, logo = null, description = null,
                releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
            ),
            ids.toSet()
        )
    }
}
