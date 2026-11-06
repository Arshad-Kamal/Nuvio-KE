package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import kotlinx.coroutines.flow.Flow

/** The app's own (local, Supabase-synced) library. */
interface LibraryRepository {
    val isSyncing: Flow<Boolean>
    val libraryItems: Flow<List<LibraryEntry>>

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean>

    /** Adds the item to the library, or removes it if already present. */
    suspend fun toggleDefault(item: LibraryEntryInput)

    /**
     * Brings the item to the desired membership: adds it when [inLibrary] is true,
     * removes it otherwise. No-op (no write, no sync) when it is already there.
     */
    suspend fun setInLibrary(item: LibraryEntryInput, inLibrary: Boolean)
}
