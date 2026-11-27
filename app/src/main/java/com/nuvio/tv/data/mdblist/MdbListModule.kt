package com.nuvio.tv.data.mdblist

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.core.di.Validated
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * MDBList's bindings (Spec 105). Every build gets them: the user pastes their own key (D6, D8), so there is no client id
 * to gate on; the provider, its progress provider and history writer join the tracker registries (S1b), the watchlist
 * library provider the Library sources (S3a), [MdbListLists] the detail Lists panel (S3b), [MdbListHomeLists] and
 * [MdbListHomeRows] Home's list rows (S5). Calls go over the shared TLS-validating client
 * ([Validated]) minus its disk cache (replies are per user and every URL carries the key, so nothing may be stored) and
 * with `retryOnConnectionFailure` off: OkHttp would otherwise resend a write after a stale keep-alive dropped it, and
 * [MdbListClient] already retries reads itself.
 */
@Module
@InstallIn(SingletonComponent::class)
object MdbListModule {

    @Provides
    @Singleton
    fun provideMdbListConfiguration(): MdbListApiConfiguration = MdbListApiConfiguration(appVersion = BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    fun provideMdbListEngine(@Validated okHttpClient: OkHttpClient): MdbListHttpEngine =
        OkHttpMdbListEngine(okHttpClient.newBuilder().cache(null).retryOnConnectionFailure(false).build())

    @Provides
    @Singleton
    fun provideMdbListClient(engine: MdbListHttpEngine, configuration: MdbListApiConfiguration): MdbListClient =
        MdbListClient(engine, configuration)

    @Provides
    @Singleton
    fun provideMdbListQuota(): MdbListQuota = MdbListQuota()

    @Provides
    @Singleton
    fun provideMdbListSession(client: MdbListClient, accounts: TrackingAccountStore, quota: MdbListQuota): MdbListSession =
        MdbListSession(client, accounts, quota)

    /** Per-profile snapshots under `filesDir/mdblist_sync` (Spec 105 S2a). */
    @Provides
    @Singleton
    fun provideMdbListSyncStore(@ApplicationContext context: Context): MdbListSyncStore =
        MdbListSyncStore(File(context.filesDir, "mdblist_sync"), Dispatchers.IO)

    @Provides
    @Singleton
    fun provideMdbListSync(session: MdbListSession, store: MdbListSyncStore, accounts: TrackingAccountStore): MdbListSync =
        MdbListSync(session, store, accounts)

    /**
     * MDBList's TVDB maps (S6): the same for every user, so app-wide and kept across keys and profiles. The file sits in
     * `mdblist_sync`, which backups leave out (a cache, fetched again); [MdbListSyncStore.clear] deletes only snapshots.
     */
    @Provides
    @Singleton
    fun provideMdbListTvdbMapStore(@ApplicationContext context: Context): MdbListTvdbMapStore = MdbListTvdbMapStore(
        File(File(context.filesDir, "mdblist_sync"), "tvdb_maps.json"), Dispatchers.IO,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    /** S4: app-wide scores cache beside the maps (same folder, so backups skip it too). */
    @Provides
    @Singleton
    fun provideMdbListRatings(@ApplicationContext context: Context, session: MdbListSession): MdbListRatings = MdbListRatings(
        session,
        MdbListRatingsCache(
            File(File(context.filesDir, "mdblist_sync"), "ratings.json"), Dispatchers.IO,
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    )

    @Provides
    @Singleton
    fun provideMdbListEpisodeNumbering(session: MdbListSession, maps: MdbListTvdbMapStore): MdbListEpisodeNumbering =
        MdbListEpisodeNumbering(session, maps, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun provideMdbListProgress(
        store: MdbListSyncStore,
        sync: MdbListSync,
        accounts: TrackingAccountStore,
        profileManager: ProfileManager,
        numbering: MdbListEpisodeNumbering
    ): MdbListTrackingProgressProvider = MdbListTrackingProgressProvider(
        store, sync, accounts, profileManager.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Default, numbering
    )

    @Provides
    @Singleton
    fun provideMdbListScrobbler(
        session: MdbListSession,
        configuration: MdbListApiConfiguration,
        profileManager: ProfileManager,
        numbering: MdbListEpisodeNumbering
    ): MdbListScrobbler = MdbListScrobbler(session, configuration, profileManager.activeProfileId, numbering::toMdbList)

    @Provides
    @Singleton
    fun provideMdbListHistoryWriter(session: MdbListSession, numbering: MdbListEpisodeNumbering): MdbListHistoryWriter =
        MdbListHistoryWriter(session, numbering::toMdbList)

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideMdbListHistoryWriters(writer: MdbListHistoryWriter): Set<TrackingHistoryWriter> = setOf(writer)

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideMdbListProgressProviders(progress: MdbListTrackingProgressProvider): Set<TrackingProgressProvider> = setOf(progress)

    @Provides
    @Singleton
    fun provideMdbListLibrary(
        store: MdbListSyncStore,
        sync: MdbListSync,
        session: MdbListSession,
        progress: MdbListTrackingProgressProvider,
        profileManager: ProfileManager
    ): MdbListTrackingLibraryProvider = MdbListTrackingLibraryProvider(
        store, sync, session, profileManager.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Default, refreshSync = progress::refresh
    )

    /** The detail Lists panel's own static lists (Spec 105 S3b). */
    @Provides
    @Singleton
    fun provideMdbListLists(session: MdbListSession): MdbListLists = MdbListLists(session)

    @Provides
    @Singleton
    fun provideMdbListHomeLists(session: MdbListSession): MdbListHomeLists = MdbListHomeLists(session)

    /** Home's rows (S5b1): one instance, so what was read outlives Home's ViewModel. */
    @Provides
    @Singleton
    fun provideMdbListHomeRows(
        @ApplicationContext context: Context,
        lists: MdbListHomeLists,
        library: MdbListTrackingLibraryProvider,
        accounts: TrackingAccountStore,
        profileManager: ProfileManager,
        ratingCap: ActiveRatingCap,
        layout: LayoutPreferenceDataStore
    ): MdbListHomeRows = MdbListHomeRows(
        lists, library, accounts, profileManager, ratingCap, layout, CoroutineScope(SupervisorJob() + Dispatchers.IO),
        watchlistTitle = { context.getString(R.string.detail_mdblist_watchlist) }
    )

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideMdbListLibraryProviders(library: MdbListTrackingLibraryProvider): Set<TrackingLibraryProvider> = setOf(library)

    /**
     * A new key or a disconnect drops the profile's synced data and this TV's unsynced plays and library changes (all
     * the old key's).
     */
    @Provides
    @Singleton
    fun provideMdbListKeyAuth(
        engine: MdbListHttpEngine,
        configuration: MdbListApiConfiguration,
        session: MdbListSession,
        accounts: TrackingAccountStore,
        store: MdbListSyncStore,
        progress: MdbListTrackingProgressProvider,
        library: MdbListTrackingLibraryProvider
    ): MdbListKeyAuth = MdbListKeyAuth(MdbListClient(engine, configuration), session, accounts, onKeyChange = { profileId ->
        progress.forget(profileId)
        library.forget(profileId)
        store.clear(profileId)
    })

    /** One instance for the app: its [MdbListTrackingProvider.isAuthenticated] lives on its own scope. */
    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideMdbListTrackingProvider(
        accounts: TrackingAccountStore,
        keyAuth: MdbListKeyAuth,
        scrobbler: MdbListScrobbler,
        quota: MdbListQuota,
        profileManager: ProfileManager
    ): Set<TrackingProvider> = setOf(
        MdbListTrackingProvider(
            accounts, keyAuth, scrobbler, quota, profileManager.activeProfileId,
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    )
}
