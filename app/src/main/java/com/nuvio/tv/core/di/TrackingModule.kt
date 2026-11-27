package com.nuvio.tv.core.di

import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * The tracker sets the registries read (Spec 104 S1c). Declared here so they may be empty: a build with no tracker
 * key binds no provider. Each tracker's own module (Specs 105, 106) contributes through `@ElementsIntoSet`, returning
 * an empty set when its client id is blank (D6), so those modules touch only their own packages.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {
    @Multibinds
    abstract fun trackingProviders(): Set<TrackingProvider>

    @Multibinds
    abstract fun trackingProgressProviders(): Set<TrackingProgressProvider>

    @Multibinds
    abstract fun trackingHistoryWriters(): Set<TrackingHistoryWriter>

    @Multibinds
    abstract fun trackingLibraryProviders(): Set<TrackingLibraryProvider>
}
