package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** L4-78: read-modify-write of the fully-watched IDs runs under the holder's lock. */
class WatchedSeriesStateHolderTest {

    @Test
    fun `concurrent transform updates lose no ids`() {
        val holder = holder()

        val writers = (0 until 4).map { writer ->
            thread {
                repeat(250) { index ->
                    holder.transformWithValidation(validatedIds = emptySet()) { current ->
                        current + "w$writer-$index"
                    }
                }
            }
        }
        writers.forEach { it.join() }

        assertEquals(1_000, holder.fullyWatchedSeriesIds.value.size)
    }

    @Test
    fun `default transform keeps the ids and records the deadline`() {
        val holder = holder()
        holder.transformWithValidation(validatedIds = emptySet()) { setOf("a") }

        holder.transformWithValidation(
            validatedIds = setOf("b"),
            revalidateAt = mapOf("b" to Long.MAX_VALUE)
        )

        assertEquals(setOf("a"), holder.fullyWatchedSeriesIds.value)
        assertTrue(holder.isSeriesValidationFresh("b"))
    }

    @Test
    fun `a disk write that lands after a newer one still stores the latest ids`() = runBlocking {
        // FR-05: each update launches its own IO write; the older one is held back here so it
        // lands last. It must not overwrite the newer state with the ids it was launched with.
        val store = FirstWriteGatedStore()
        val holder = holder(store)

        holder.transformWithValidation(validatedIds = emptySet()) { setOf("old") }
        withTimeout(TIMEOUT_MS) { store.firstWriteEntered.await() }
        holder.transformWithValidation(validatedIds = emptySet()) { setOf("new") }
        withTimeout(TIMEOUT_MS) { store.completedWrites.receive() }
        store.releaseFirstWrite.complete(Unit)
        withTimeout(TIMEOUT_MS) { store.completedWrites.receive() }

        assertEquals(setOf("new"), store.value[stringSetPreferencesKey("fully_watched_ids")])
    }

    @Test
    fun `loading another profile replaces the previous profile's series`() = runBlocking {
        // Spec 48 P2 (upstream NM b39ce87d4): a switch must not keep profile 1's ids in memory,
        // where the next update would persist them into profile 2's store.
        val stores = mapOf(
            1 to TestPreferencesDataStore(preferencesOf(stringSetPreferencesKey("fully_watched_ids") to setOf("tt0000001"))),
            2 to TestPreferencesDataStore()
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers { stores.getValue(firstArg()) }
        val activeProfile = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        val holder = WatchedSeriesStateHolder(factory, profileManager)

        holder.loadFromDisk(profileId = 1)
        assertEquals(setOf("tt0000001"), holder.fullyWatchedSeriesIds.value)

        activeProfile.value = 2
        holder.loadFromDisk(profileId = 2)
        assertEquals(emptySet<String>(), holder.fullyWatchedSeriesIds.value)

        activeProfile.value = 1
        holder.loadFromDisk(profileId = 1)
        assertEquals(setOf("tt0000001"), holder.fullyWatchedSeriesIds.value)
    }

    private fun holder(store: TestPreferencesDataStore = TestPreferencesDataStore()): WatchedSeriesStateHolder {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return WatchedSeriesStateHolder(factory, profileManager)
    }

    /** Holds the first edit until released; signals every completed edit. */
    private class FirstWriteGatedStore : TestPreferencesDataStore() {
        val firstWriteEntered = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val completedWrites = Channel<Unit>(Channel.UNLIMITED)
        private val calls = AtomicInteger(0)

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (calls.getAndIncrement() == 0) {
                firstWriteEntered.complete(Unit)
                releaseFirstWrite.await()
            }
            return super.updateData(transform).also { completedWrites.send(Unit) }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
