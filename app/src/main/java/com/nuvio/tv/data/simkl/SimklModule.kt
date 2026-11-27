package com.nuvio.tv.data.simkl

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.di.Validated
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import dagger.Lazy
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
 * Simkl's bindings (Spec 106), in every build: no build carries an app id (D9); each TV gets its own pasted
 * ([SimklAppIdStore], K1-K5), and until then every call fails as signed out with nothing sent. Calls go over the shared TLS-validating client
 * ([Validated]: app DNS, 30 s call timeout, identity headers that keep Simkl's own User-Agent) minus its disk cache:
 * signed-in replies are per user and must never be stored or served from disk. OkHttp's own retry on a failed
 * connection stays on: it covers a stale keep-alive, where a write never reached Simkl.
 */
@Module
@InstallIn(SingletonComponent::class)
object SimklModule {

    /** `filesDir/simkl_app_id`: this TV's app id, every profile's (D8). */
    @Provides
    @Singleton
    fun provideSimklAppIdStore(@ApplicationContext context: Context): SimklAppIdStore =
        SimklAppIdStore(File(context.filesDir, "simkl_app_id"), Dispatchers.IO, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun provideSimklConfiguration(appIds: SimklAppIdStore): SimklApiConfiguration =
        SimklApiConfiguration(clientId = appIds::get, appVersion = BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    fun provideSimklClient(@Validated okHttpClient: OkHttpClient, configuration: SimklApiConfiguration): SimklClient =
        SimklClient(OkHttpSimklEngine(okHttpClient.newBuilder().cache(null).build()), configuration)

    /** More like this from Simkl (S4a): its own client, never following redirects (`/redirect` is read, not followed). */
    @Provides
    @Singleton
    fun provideSimklRelated(@Validated okHttpClient: OkHttpClient, configuration: SimklApiConfiguration): SimklRelated {
        val noRedirects = okHttpClient.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
        return SimklRelated(SimklClient(OkHttpSimklEngine(noRedirects), configuration), configuration)
    }

    @Provides
    @Singleton
    fun provideSimklQuota(): SimklQuota = SimklQuota()

    @Provides
    @Singleton
    fun provideSimklTokens(client: SimklClient, configuration: SimklApiConfiguration, accounts: TrackingAccountStore): SimklTokens =
        SimklTokens(client, configuration, accounts)

    @Provides
    @Singleton
    fun provideSimklSession(client: SimklClient, tokens: SimklTokens, quota: SimklQuota): SimklSession =
        SimklSession(client, tokens, quota)

    /** Per-profile snapshots under `filesDir/simkl_sync` (Spec 106 S2a). */
    @Provides
    @Singleton
    fun provideSimklSyncStore(@ApplicationContext context: Context): SimklSyncStore =
        SimklSyncStore(File(context.filesDir, "simkl_sync"), Dispatchers.IO)

    /** Marks kept while the allowance is spent, next to the snapshots (Spec 106 S2b). */
    @Provides
    @Singleton
    fun provideSimklPendingMarks(@ApplicationContext context: Context): SimklPendingMarks =
        SimklPendingMarks(File(context.filesDir, "simkl_sync"), Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSimklHistoryWriter(session: SimklSession, pending: SimklPendingMarks, store: SimklSyncStore): SimklHistoryWriter =
        SimklHistoryWriter(session, pending, snapshotOf = store::get)

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideSimklHistoryWriters(writer: SimklHistoryWriter): Set<TrackingHistoryWriter> = setOf(writer)

    @Provides
    @Singleton
    fun provideSimklSync(
        session: SimklSession,
        store: SimklSyncStore,
        accounts: TrackingAccountStore,
        writer: SimklHistoryWriter
    ): SimklSync = SimklSync(session, store, accounts, replayPending = writer::replayPending)

    @Provides
    @Singleton
    fun provideSimklProgress(
        store: SimklSyncStore,
        sync: SimklSync,
        accounts: TrackingAccountStore,
        appIds: SimklAppIdStore,
        settings: ContinueWatchingSettingsDataStore,
        profileManager: ProfileManager
    ): SimklTrackingProgressProvider = SimklTrackingProgressProvider(
        store, sync, accounts, appIds.appId, profileManager.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Default, animeSeasons = settings::simklAnimeSeasons
    )

    @Provides
    @Singleton
    fun provideSimklLibrary(
        store: SimklSyncStore,
        sync: SimklSync,
        writer: SimklHistoryWriter,
        quota: SimklQuota,
        settings: ContinueWatchingSettingsDataStore,
        profileManager: ProfileManager
    ): SimklTrackingLibraryProvider = SimklTrackingLibraryProvider(
        store, sync, writer, profileManager.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Default, isPaused = quota::isPaused,
        animeSeasons = settings::simklAnimeSeasons
    )

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideSimklLibraryProviders(library: SimklTrackingLibraryProvider): Set<TrackingLibraryProvider> = setOf(library)

    /**
     * A new grant or a disconnect drops the profile's synced data, this TV's unsent marks and its library changes (all
     * the old grant's).
     */
    @Provides
    @Singleton
    fun provideSimklDeviceAuth(
        client: SimklClient,
        configuration: SimklApiConfiguration,
        accounts: TrackingAccountStore,
        session: SimklSession,
        store: SimklSyncStore,
        pending: SimklPendingMarks,
        progress: SimklTrackingProgressProvider,
        library: SimklTrackingLibraryProvider
    ): SimklDeviceAuth = SimklDeviceAuth(client, configuration, accounts, session, onGrantChange = { profileId ->
        progress.forget(profileId)
        library.forget(profileId)
        store.clear(profileId)
        pending.clear(profileId)
    })

    /**
     * A replaced or removed app id signs every profile out: revoked with the old id, sources back to Nuvio (Spec 104
     * A4). Lazy: the source controller reads the provider registry this provider is part of.
     */
    @Provides
    @Singleton
    fun provideSimklAppIdSetup(
        appIds: SimklAppIdStore,
        client: SimklClient,
        deviceAuth: SimklDeviceAuth,
        sourceController: Lazy<TrackingSourceController>,
        profileManager: ProfileManager
    ): SimklAppIdSetup = SimklAppIdSetup(
        store = appIds,
        client = client,
        profileIds = { (profileManager.profiles.value.map { it.id } + profileManager.activeProfileId.value).distinct() },
        signOut = { profileId ->
            deviceAuth.disconnect(profileId)
            sourceController.get().onProviderDisconnected(TrackingProviderId.SIMKL, profileId)
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    /** One instance for the app: its [SimklTrackingProvider.isAuthenticated] lives on its own scope. */
    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideSimklTrackingProvider(
        accounts: TrackingAccountStore,
        deviceAuth: SimklDeviceAuth,
        setup: SimklAppIdSetup,
        session: SimklSession,
        writer: SimklHistoryWriter,
        quota: SimklQuota,
        profileManager: ProfileManager
    ): Set<TrackingProvider> = setOf(
        SimklTrackingProvider(
            accounts, deviceAuth, setup, SimklScrobbler(session, writer, profileManager.activeProfileId), quota,
            profileManager.activeProfileId,
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    )

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideSimklProgressProviders(progress: SimklTrackingProgressProvider): Set<TrackingProgressProvider> = setOf(progress)
}
