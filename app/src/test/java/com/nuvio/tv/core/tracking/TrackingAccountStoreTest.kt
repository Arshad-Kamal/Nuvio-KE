package com.nuvio.tv.core.tracking

import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.profileSettingsSyncedFeatures
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.YieldingPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingAccountStoreTest {

    private val profiles = mutableMapOf(1 to TestPreferencesDataStore(), 2 to TestPreferencesDataStore())
    private val activeIdFlow = MutableStateFlow(1)
    private val store = TrackingAccountStore(
        factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), TrackingAccountStore.FEATURE) } answers { profiles.getValue(firstArg()) }
        },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeIdFlow }
    )
    private val account = TrackingAccount("access-1", "refresh-1", 1_900_000_000_000L, "arshadk")

    @Test
    fun `tokens stay on this TV`() {
        assertEquals("tracking_accounts", TrackingAccountStore.FEATURE)
        assertFalse(TrackingAccountStore.FEATURE in profileSettingsSyncedFeatures)
    }

    @Test
    fun `an account is kept per profile and per tracker`() = runTest {
        store.save(1, TrackingProviderId.MDBLIST, account)

        assertEquals(account, store.get(1, TrackingProviderId.MDBLIST))
        assertNull(store.get(1, TrackingProviderId.SIMKL))
        assertNull(store.get(2, TrackingProviderId.MDBLIST))
        assertEquals(account, store.account(TrackingProviderId.MDBLIST).first())

        activeIdFlow.value = 2
        assertNull(store.account(TrackingProviderId.MDBLIST).first())

        store.save(1, TrackingProviderId.SIMKL, account.copy(accountName = "arshad.k"))
        store.clear(1, TrackingProviderId.MDBLIST)
        assertNull(store.get(1, TrackingProviderId.MDBLIST))
        assertEquals("arshad.k", store.get(1, TrackingProviderId.SIMKL)?.accountName)
    }

    private fun refreshedFrom(refreshToken: String, newAccess: String): (TrackingAccount?) -> TrackingAccount? = { current ->
        current?.takeIf { it.refreshToken == refreshToken }?.copy(accessToken = newAccess)
    }

    @Test
    fun `update writes only what the transform returns, and null changes nothing`() = runTest {
        store.save(1, TrackingProviderId.SIMKL, account)
        store.update(1, TrackingProviderId.SIMKL, refreshedFrom("refresh-1", "access-2"))
        assertEquals("access-2", store.get(1, TrackingProviderId.SIMKL)?.accessToken)

        // A newer sign-in (another refresh token) landed meanwhile: a stale refresh leaves it alone.
        val newer = account.copy(accessToken = "access-9", refreshToken = "refresh-9")
        store.save(1, TrackingProviderId.SIMKL, newer)
        store.update(1, TrackingProviderId.SIMKL, refreshedFrom("refresh-1", "access-3"))
        assertEquals(newer, store.get(1, TrackingProviderId.SIMKL))

        // Signed out meanwhile: nothing is written back.
        store.clear(1, TrackingProviderId.SIMKL)
        store.update(1, TrackingProviderId.SIMKL, refreshedFrom("refresh-9", "access-4"))
        assertNull(store.get(1, TrackingProviderId.SIMKL))
    }

    @Test
    fun `a sign-out racing a refresh is never undone (atomic read and write)`() = runTest {
        val yielding = YieldingPreferencesDataStore()
        val racing = TrackingAccountStore(
            factory = mockk<ProfileDataStoreFactory> { every { get(any(), TrackingAccountStore.FEATURE) } returns yielding },
            profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeIdFlow }
        )
        racing.save(1, TrackingProviderId.SIMKL, account)

        // Pins atomicity only: a read-then-save refresh would read the account, let the sign-out run, then write it
        // back. The null-changes-nothing rule is pinned by the sequential test above (a newer sign-in survives).
        val refresh = launch { racing.update(1, TrackingProviderId.SIMKL, refreshedFrom("refresh-1", "access-2")) }
        val signOut = launch { racing.clear(1, TrackingProviderId.SIMKL) }
        refresh.join()
        signOut.join()

        assertNull(racing.get(1, TrackingProviderId.SIMKL))
    }

    @Test
    fun `an unreadable or empty entry reads as signed out`() = runTest {
        profiles.getValue(1).updateData { prefs ->
            prefs.toMutablePreferences().apply { this[stringPreferencesKey("simkl")] = "{not json" }
        }
        assertNull(store.get(1, TrackingProviderId.SIMKL))
        assertNull(decodeTrackingAccount("""{"accessToken":""}"""))
        assertNull(decodeTrackingAccount("""{"refreshToken":"r"}"""))
        assertEquals("a", decodeTrackingAccount("""{"accessToken":"a","extra":1}""")?.accessToken)
    }

    @Test
    fun `an entry stored before the revoked and last-sync fields reads as connected, never synced`() {
        val decoded = decodeTrackingAccount("""{"accessToken":"a","refreshToken":"r","accountName":"arshadk"}""")!!
        assertEquals(false, decoded.revoked)
        assertNull(decoded.lastSyncedAtEpochMs)
        assertEquals("arshadk", decoded.accountName)
    }

    @Test
    fun `the account's text form carries no token`() {
        val text = account.toString()
        assertFalse(text.contains("access-1"))
        assertFalse(text.contains("refresh-1"))
    }
}
