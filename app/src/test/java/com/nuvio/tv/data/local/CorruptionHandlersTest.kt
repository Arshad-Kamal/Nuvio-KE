package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Real-file corruption tests for the shared standalone handler (W4.1, W4.5 recipe:
 * plain JUnit4 + TemporaryFolder, fresh file per test, backgroundScope so the store's
 * scope is cancelled before test end — no UncompletedCorruptionsError / multi-instance
 * leaks). The 11 standalone stores share [loggedResetCorruptionHandler], so this seam
 * covers them all; profile-scoped stores use ProfileDataStoreFactory's own handler.
 */
class CorruptionHandlersTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val key = intPreferencesKey("counter")

    @Test
    fun `write read round trip works on a fresh file`() = runTest {
        val store = newStore(tmpFolder.newFile("roundtrip.preferences_pb"), backgroundScope)
        store.edit { it[key] = 7 }
        assertEquals(7, store.data.first()[key])
    }

    @Test
    fun `corrupt file recovers to empty preferences instead of throwing`() = runTest {
        val file = tmpFolder.newFile("corrupt.preferences_pb")
        // Truncated length-delimited field: tag 0x0A declares a 5-byte value with
        // only one byte present — reliably fails Preferences' protobuf parse at the
        // new instance's first read, which is where corruption is detected (a live
        // instance serves its cached state and never re-reads the file).
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x61))

        val store = newStore(file, backgroundScope)
        assertEquals(emptyPreferences(), store.data.first())
    }

    @Test
    fun `store is writable again after corruption reset`() = runTest {
        val file = tmpFolder.newFile("recovered.preferences_pb")
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x61))

        val store = newStore(file, backgroundScope)
        assertEquals(emptyPreferences(), store.data.first())
        store.edit { it[key] = 3 }
        assertEquals(3, store.data.first()[key])
    }

    @Test
    fun `handler contract is empty reset without touching DataStore APIs`() = runTest {
        // Direct invocation pins the produceNewData contract: given the swallowed
        // exception, return empty preferences (Log only — returnDefaultValues=true).
        val handler = loggedResetCorruptionHandler("test_store")
        assertEquals(emptyPreferences(), handler.handleCorruption(CorruptionException("test corruption")))
    }

    private fun newStore(file: File, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        corruptionHandler = loggedResetCorruptionHandler(file.nameWithoutExtension),
        scope = scope,
        produceFile = { file }
    )
}
