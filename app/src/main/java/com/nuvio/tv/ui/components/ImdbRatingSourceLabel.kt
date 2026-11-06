package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** IMDb's mark beside its score: the plain word, never IMDb's logo. */
internal const val IMDB_MARK = "IMDb"

/**
 * The source label before an IMDb score (Spec 49 D17, sandbox spec-49-spinner-and-imdb-label v2):
 * [IMDB_MARK] in bold IMDb yellow, in the [style] of the line it sits on, as TMDB's mark is its
 * name in TMDB green. Text, not the logo: IMDb's Conditions of Use bar the logo without permission.
 */
@Composable
fun ImdbRatingSourceLabel(
    style: TextStyle = NuvioTheme.textStyles.meta
) {
    Text(
        text = IMDB_MARK,
        style = style,
        fontWeight = FontWeight.Bold,
        color = NuvioPrimitives.imdb,
        maxLines = 1
    )
}
