package com.nuvio.tv.data.simkl

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * Each profile's [SimklSnapshot] (Spec 106 S2a): one JSON file per profile in [directory] (a library with its episodes
 * runs to megabytes, too big for a Preferences DataStore), plus the copy in memory every read uses.
 *
 * A snapshot belongs to one Simkl grant: [clear] (sign-in approval, disconnect) deletes it and bumps the profile's
 * generation, and [saveIf] only writes for the generation a sync started with, so a sync that was running when the
 * account changed can never bring the old account's data back.
 */
class SimklSyncStore(private val directory: File, private val io: CoroutineDispatcher) {
    private class Slot(val snapshot: SimklSnapshot?)

    private val mutex = Mutex()

    /** Loaded profiles only: a missing key means the file was not read yet. */
    private val slots = MutableStateFlow<Map<Int, Slot>>(emptyMap())

    // Read and written under [mutex].
    private val generations = mutableMapOf<Int, Long>()

    /** The profile's snapshot as it changes; the first value is the stored one (null: none), read from disk once. */
    fun snapshot(profileId: Int): Flow<SimklSnapshot?> = flow {
        load(profileId)
        emitAll(slots.mapNotNull { it[profileId] }.distinctUntilChanged { old, new -> old === new }.map { it.snapshot })
    }

    suspend fun get(profileId: Int): SimklSnapshot? {
        load(profileId)
        return slots.value[profileId]?.snapshot
    }

    suspend fun generation(profileId: Int): Long = mutex.withLock { generations[profileId] ?: 0L }

    /** Writes [snapshot] unless the profile was cleared since [generation] was read; false = dropped. */
    suspend fun saveIf(profileId: Int, generation: Long, snapshot: SimklSnapshot): Boolean = mutex.withLock {
        if ((generations[profileId] ?: 0L) != generation) return@withLock false
        withContext(io) { write(profileId, simklJson.encodeToString(SimklSnapshot.serializer(), snapshot)) }
        slots.update { it + (profileId to Slot(snapshot)) }
        true
    }

    suspend fun clear(profileId: Int) {
        mutex.withLock {
            generations[profileId] = (generations[profileId] ?: 0L) + 1L
            slots.update { it + (profileId to Slot(null)) }
            withContext(io) {
                file(profileId).delete()
                tempFile(profileId).delete()
            }
        }
    }

    private suspend fun load(profileId: Int) {
        if (slots.value.containsKey(profileId)) return
        mutex.withLock {
            if (slots.value.containsKey(profileId)) return@withLock
            val snapshot = withContext(io) { read(profileId) }
            slots.update { it + (profileId to Slot(snapshot)) }
        }
    }

    /** An unreadable or outdated file is no snapshot: the next sync pulls everything again. */
    private fun read(profileId: Int): SimklSnapshot? {
        val file = file(profileId)
        if (!file.isFile) return null
        return try {
            simklJson.decodeFromString(SimklSnapshot.serializer(), file.readText())
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Temp file then rename, so a kill mid-write leaves the previous snapshot whole. */
    private fun write(profileId: Int, text: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the Simkl sync folder")
        val temp = tempFile(profileId)
        temp.writeText(text)
        if (!temp.renameTo(file(profileId))) {
            temp.delete()
            throw IOException("Cannot replace the Simkl snapshot")
        }
    }

    private fun file(profileId: Int) = File(directory, "snapshot_$profileId.json")

    private fun tempFile(profileId: Int) = File(directory, "snapshot_$profileId.json.tmp")
}
