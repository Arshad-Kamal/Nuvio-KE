package com.nuvio.tv.ui.screens.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * The collection screens' focus targets: one [FocusRequester] per key (a row id, a source key or
 * a fixed target) and at most one [pending] request, which [ServePendingFocus] serves once its
 * target is composed.
 */
@Stable
internal class CollectionFocusTargets {
    private val requesters = mutableMapOf<String, FocusRequester>()

    /** The target to focus next; null when served. */
    var pending by mutableStateOf<String?>(null)

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun registered(key: String): FocusRequester? = requesters[key]
}

@Composable
internal fun rememberCollectionFocusTargets(): CollectionFocusTargets = remember { CollectionFocusTargets() }

/**
 * Serves [CollectionFocusTargets.pending]: [resolve] maps it to a key; a key with no requester yet
 * falls back to [fallback], else waits until [content] changes. The request clears [pending] only
 * after it ran, since the effect is keyed on it.
 */
@Composable
internal fun CollectionFocusTargets.ServePendingFocus(
    content: Any?,
    fallback: String? = null,
    resolve: (String) -> String = { it }
) {
    val target = pending
    LaunchedEffect(target, content) {
        if (target == null) return@LaunchedEffect
        val requester = registered(resolve(target)) ?: fallback?.let(::registered) ?: return@LaunchedEffect
        requester.requestFocusAfterFrames(frames = 3)
        pending = null
    }
}
