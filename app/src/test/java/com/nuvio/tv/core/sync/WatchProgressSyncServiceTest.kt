package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L9-02/L9-69 + hybrid ruling: a single-entry push advances only its own profile's watermark,
 * and not while an earlier push is unconfirmed (that entry would fall under it and be deleted
 * by the next snapshot); a full push stamps only its own profile and confirms.
 */
class WatchProgressSyncServiceTest {
    private val activeProfileId = MutableStateFlow(1)
    private val stores = mutableMapOf<Pair<Int, String>, TestPreferencesDataStore>()

    private val preferences: WatchProgressPreferences
    private val service: WatchProgressSyncService

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            synchronized(stores) {
                stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesDataStore() }
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        preferences = WatchProgressPreferences(factory, profileManager)

        val postgrest = mockk<Postgrest>()
        coEvery { postgrest.rpc(any<String>(), any<JsonObject>(), any()) } returns
            mockk<PostgrestResult>(relaxed = true)
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-test-client-id"
        service = WatchProgressSyncService(
            authManager = mockk<AuthManager>(relaxed = true),
            postgrest = postgrest,
            watchProgressPreferences = preferences,
            profileManager = profileManager,
            syncClientIdentity = identity,
            pushHealth = SyncPushHealth()
        )
    }

    @Test
    fun `single push stamps its start time only once a full push confirmed the profile`() = runTest {
        val entry = progress("tt1", lastWatched = 500L)
        preferences.saveProgress(entry, profileId = 2)

        service.pushSingleToRemote("tt1", entry, profileId = 2).getOrThrow()
        assertEquals(0L, preferences.getLastSuccessfulPushMs(profileId = 2)) // new process: unconfirmed

        service.pushToRemote(profileId = 2).getOrThrow()
        val afterFull = preferences.getLastSuccessfulPushMs(profileId = 2)
        Thread.sleep(2)
        val beforePush = System.currentTimeMillis()
        service.pushSingleToRemote("tt1", entry, profileId = 2).getOrThrow()

        val stamped = preferences.getLastSuccessfulPushMs(profileId = 2)
        assertTrue(stamped > afterFull && stamped in beforePush..System.currentTimeMillis())
        assertEquals(0L, preferences.getLastSuccessfulPushMs(profileId = 1))
    }

    @Test
    fun `a skipped push blocks single-push stamps until a full push confirms`() = runTest {
        val entry = progress("tt1", lastWatched = 500L)
        preferences.saveProgress(entry, profileId = 2)
        service.markPushSkipped(profileId = 2)

        service.pushSingleToRemote("tt1", entry, profileId = 2).getOrThrow()
        assertEquals(0L, preferences.getLastSuccessfulPushMs(profileId = 2))

        service.pushToRemote(profileId = 2).getOrThrow()
        val afterFull = preferences.getLastSuccessfulPushMs(profileId = 2)
        assertTrue(afterFull > 0L)

        Thread.sleep(2)
        service.pushSingleToRemote("tt1", entry, profileId = 2).getOrThrow()
        assertTrue(preferences.getLastSuccessfulPushMs(profileId = 2) > afterFull)
    }

    @Test
    fun `full push stamps only its own profile with the pre-read time`() = runTest {
        preferences.saveProgress(progress("tt1", lastWatched = 500L), profileId = 2)
        val beforePush = System.currentTimeMillis()

        service.pushToRemote(profileId = 2).getOrThrow()

        val stamped = preferences.getLastSuccessfulPushMs(profileId = 2)
        assertTrue(stamped in beforePush..System.currentTimeMillis())
        assertEquals(0L, preferences.getLastSuccessfulPushMs(profileId = 1))
    }

    private fun progress(contentId: String, lastWatched: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 10_000L,
        duration = 100_000L,
        lastWatched = lastWatched
    )
}
