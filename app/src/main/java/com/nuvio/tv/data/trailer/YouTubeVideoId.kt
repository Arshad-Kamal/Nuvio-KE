package com.nuvio.tv.data.trailer

import java.net.URI

private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

/** True when [s] is exactly a bare 11-char YouTube video id (no trimming, no URL parsing). */
internal fun isYoutubeVideoId(s: String): Boolean = YOUTUBE_VIDEO_ID_REGEX.matches(s)

/**
 * The 11-char YouTube video id from a bare id or a youtu.be / youtube.com (watch?v=, embed/,
 * shorts/, live/) URL; null for anything else. Pure JVM (java.net.URI) so it is unit-testable.
 */
internal fun youtubeVideoId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.matches(YOUTUBE_VIDEO_ID_REGEX)) return trimmed

    return runCatching {
        val uri = URI(trimmed)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
        when {
            host == "youtu.be" -> {
                val id = uri.path?.trim('/')?.substringBefore('/')?.trim().orEmpty()
                id.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
            }

            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                val path = uri.path.orEmpty()
                val query = uri.rawQuery.orEmpty()

                if (path.startsWith("/watch")) {
                    query.split("&")
                        .asSequence()
                        .mapNotNull { entry ->
                            val index = entry.indexOf('=')
                            if (index <= 0) return@mapNotNull null
                            val key = entry.substring(0, index)
                            val value = entry.substring(index + 1)
                            if (key == "v") value else null
                        }
                        .firstOrNull { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                } else {
                    val segments = path.trim('/').split("/")
                    val candidate = when (segments.firstOrNull()?.lowercase()) {
                        "embed", "shorts", "live" -> segments.getOrNull(1)
                        else -> null
                    }
                    candidate?.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                }
            }

            else -> null
        }
    }.getOrNull()
}
