package com.nuvio.tv.livetv

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.source.LiveSourceManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LiveTvReleaseEntryPoint {
    fun liveSourceManager(): LiveSourceManager
}

/**
 * Spec 146 D2 (F19): a movie or episode stream starts, so Live TV's channels, metadata and guides
 * leave the heap before the decoder and buffer ramp up (the next GC reclaims them); the next Live TV
 * open reads them from disk again. Called from the VOD player's init only (Live TV's own player,
 * [com.nuvio.tv.livetv.player.LivePlayer], releases itself when the VOD player opens: Spec 147 D1).
 * Skipped while a source refresh runs ([LiveSourceManager.unload]).
 */
internal suspend fun releaseLiveTvForPlayback(context: Context) {
    // A failure here costs only the memory, never the stream that is starting.
    try {
        val manager = EntryPointAccessors.fromApplication(context.applicationContext, LiveTvReleaseEntryPoint::class.java)
            .liveSourceManager()
        val released = manager.unload()
        Log.i(TAG, "LIVE_TV_RELEASE ran=$released")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "LIVE_TV_RELEASE failed: ${e.javaClass.simpleName}")
    }
}

private const val TAG = "LiveTvRelease"
