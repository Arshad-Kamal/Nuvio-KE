package com.nuvio.tv.core.player

import android.media.AudioAttributes

/**
 * Framework USAGE_MEDIA + CONTENT_TYPE_MOVIE attributes: the class the player's own media3
 * AudioAttributes use, so capability probes, route detection and the IEC61937 MAT sink ask
 * about (and open) the same output route playback takes.
 */
internal fun mediaMovieAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
    .build()
