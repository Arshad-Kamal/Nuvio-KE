package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.tv.domain.model.CollectionFolder

/**
 * Resolves the folder card's cover artwork URL. Blacklisted (load-failed)
 * URLs resolve to null so the caller falls back to the designed emoji or
 * initials tile instead of re-requesting a dead URL on every recycle.
 */
fun collectionFolderCardImageUrl(folder: CollectionFolder): String? {
    // GIF URL is only used as an animated overlay on focus (when focusGifEnabled is true).
    // When focusGifEnabled is off, fall back to cover image only — don't use the GIF
    // as a static poster since it would still animate via Coil's GIF decoder.
    return firstNonBlank(folder.coverImageUrl)?.takeUnless { it in brokenImageUrls }
}

/**
 * Tracks whether a folder cover failed to load so its card can render the
 * designed emoji/initials fallback in place of a dead URL (Arshad decision
 * 2026-08-31: restore the fallback on load error — previously only a missing
 * URL fell back and a dead URL left the tile blank). The failure is also
 * added to the shared [brokenImageUrls] blacklist, which makes it stick
 * across card recycling and lets every folder surface skip the URL.
 *
 * Reset happens automatically when the resolved URL changes
 * ([rememberFolderCoverFallback] keys on it).
 */
@Stable
internal class FolderCoverFallback {
    var failed by mutableStateOf(false)
        internal set

    fun onCoverError(url: String?) {
        if (!url.isNullOrBlank()) brokenImageUrls.add(url)
        failed = true
    }
}

@Composable
internal fun rememberFolderCoverFallback(resolvedUrl: String?): FolderCoverFallback =
    remember(resolvedUrl) { FolderCoverFallback() }

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}
