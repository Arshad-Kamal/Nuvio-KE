package com.nuvio.tv.ui.screens.tmdb

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb

/**
 * Spec 52 T1 (sandbox `spec-52-public-build-ui`, stage t1-person): a page that comes from TMDB, on a
 * copy with no key. Add a TMDB key opens Settings › Integrations with the key panel open; the page
 * loads by itself once a key is saved. Add takes focus on entry unless [takesFocus] is false (a
 * folder's tabs select on focus, so focus stays on them). [breadcrumb] is drawn over the message
 * (null where the page draws its own).
 */
@Composable
internal fun TmdbKeyNeededMessage(
    eyebrow: String?,
    onAddKey: () -> Unit,
    onBack: () -> Unit,
    breadcrumb: List<String>? = null,
    takesFocus: Boolean = true
) {
    val focusRequester = remember { FocusRequester() }
    Box(modifier = Modifier.fillMaxSize()) {
        MarqueeMessage(
            eyebrow = eyebrow,
            title = stringResource(R.string.tmdb_key_needed_title),
            body = AnnotatedString(stringResource(R.string.tmdb_key_needed_body)),
            actionLabel = stringResource(R.string.tmdb_key_needed_add),
            actionIcon = Icons.Filled.VpnKey,
            onAction = onAddKey,
            focusRequester = focusRequester.takeIf { takesFocus },
            secondaryLabel = stringResource(R.string.action_back),
            onSecondary = onBack
        )
        breadcrumb?.let { MarqueeBreadcrumb(it) }
    }
}
