package com.nuvio.tv.core.util

import android.net.Uri

/**
 * Host of this URL for logs (never the path or query: addon URLs carry user config such as
 * debrid keys there). Falls back to the text before "://" when the URL has no host, and to
 * "unknown" on blank or bad input. The one redaction rule for URL logging (F-9 review).
 */
internal fun String.safeHost(): String {
    return runCatching {
        // missingDelimiterValue: a scheme-less URL must not be logged whole (path/query tokens).
        Uri.parse(this).host
            ?: substringBefore("://", missingDelimiterValue = "unknown").takeIf { it.isNotBlank() }
            ?: "unknown"
    }.getOrDefault("unknown")
}
