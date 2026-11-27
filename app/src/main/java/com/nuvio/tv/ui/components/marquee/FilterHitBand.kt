package com.nuvio.tv.ui.components.marquee

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Own packs' names by pack id (Spec 122 V4: `ActiveFilter.ownPackNames`), provided app-wide by
 * MainActivity from `ActiveContentFilter.states()` (122/D5). Empty by default, so an own pack's mark
 * reads "Filtered", never a throw.
 */
internal val LocalFilterPackNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** A built-in pack's name and description strings. */
private class PackStrings(@StringRes val name: Int, @StringRes val description: Int)

// Ids as in `assets/content_filter_packs.json` (FilterHitBandTest keeps them in step).
private val BUILT_IN_PACK_STRINGS = mapOf(
    "sexual_content" to PackStrings(R.string.content_filter_pack_sexual_content, R.string.content_filter_pack_sexual_content_desc),
    "lgbtq" to PackStrings(R.string.content_filter_pack_lgbtq, R.string.content_filter_pack_lgbtq_desc),
    "religion" to PackStrings(R.string.content_filter_pack_religion, R.string.content_filter_pack_religion_desc),
    "occult" to PackStrings(R.string.content_filter_pack_occult, R.string.content_filter_pack_occult_desc),
    "horror" to PackStrings(R.string.content_filter_pack_horror, R.string.content_filter_pack_horror_desc),
    "violence" to PackStrings(R.string.content_filter_pack_violence, R.string.content_filter_pack_violence_desc),
    "drugs" to PackStrings(R.string.content_filter_pack_drugs, R.string.content_filter_pack_drugs_desc),
    "suicide" to PackStrings(R.string.content_filter_pack_suicide, R.string.content_filter_pack_suicide_desc),
    "abuse" to PackStrings(R.string.content_filter_pack_abuse, R.string.content_filter_pack_abuse_desc),
    "politics" to PackStrings(R.string.content_filter_pack_politics, R.string.content_filter_pack_politics_desc),
    "war" to PackStrings(R.string.content_filter_pack_war, R.string.content_filter_pack_war_desc),
    "crime" to PackStrings(R.string.content_filter_pack_crime, R.string.content_filter_pack_crime_desc),
    "romance" to PackStrings(R.string.content_filter_pack_romance, R.string.content_filter_pack_romance_desc),
    "reality_tv" to PackStrings(R.string.content_filter_pack_reality_tv, R.string.content_filter_pack_reality_tv_desc),
    "anime" to PackStrings(R.string.content_filter_pack_anime, R.string.content_filter_pack_anime_desc),
    "sports_awards" to PackStrings(R.string.content_filter_pack_sports_awards, R.string.content_filter_pack_sports_awards_desc),
    "gambling" to PackStrings(R.string.content_filter_pack_gambling, R.string.content_filter_pack_gambling_desc),
    "scary_kids" to PackStrings(R.string.content_filter_pack_scary_kids, R.string.content_filter_pack_scary_kids_desc)
)

/** A built-in pack's name string; null for any other id. */
@StringRes
internal fun builtInPackNameRes(packId: String): Int? = BUILT_IN_PACK_STRINGS[packId]?.name

/** A built-in pack's one-line description (the Content filters page, 117/S7a); null for any other id. */
@StringRes
internal fun builtInPackDescriptionRes(packId: String): Int? = BUILT_IN_PACK_STRINGS[packId]?.description

/** A mark's text: the first hitting pack's [name], and [more] packs that also hit (`+N`). */
internal data class FilterBadgeLabel(val name: String, val more: Int)

/**
 * The label for [hits] (pack ids in pack order), or null when nothing hit. The first id is named by
 * [builtInName], else "My words" ([myWords]) for its id, else [ownPackNames]; an id none of them
 * names reads [unknown] (Spec 122 N4, e.g. a pack since deleted).
 */
internal fun filterBadgeLabel(
    hits: List<String>,
    builtInName: (String) -> String?,
    ownPackNames: Map<String, String>,
    myWords: String,
    unknown: String
): FilterBadgeLabel? {
    val first = hits.firstOrNull() ?: return null
    return FilterBadgeLabel(filterPackName(first, builtInName, ownPackNames, myWords, unknown), hits.size - 1)
}

/** A pack's name by id, by [filterBadgeLabel]'s rule (Detail's block names every hitting pack, 117/P8). */
internal fun filterPackName(
    id: String,
    builtInName: (String) -> String?,
    ownPackNames: Map<String, String>,
    myWords: String,
    unknown: String
): String = builtInName(id)
    ?: myWords.takeIf { id == ContentFilterSettings.MY_WORDS_ID }
    ?: ownPackNames[id]
    ?: unknown

/**
 * Spec 122 D1 (badge B): an amber band across the foot of a card's art, filter icon and the first
 * pack's name in black, two lines at most, `+N` when more packs hit. Nothing for an empty [hits].
 * Placed by [MarqueeCardBandFoot], which stacks a card's title and progress bar above it.
 */
@Composable
internal fun FilterHitBandRow(hits: List<String>, modifier: Modifier = Modifier) {
    if (hits.isEmpty()) return
    val ownPackNames = LocalFilterPackNames.current
    val resources = LocalContext.current.resources
    val label = filterBadgeLabel(
        hits = hits,
        builtInName = { id -> builtInPackNameRes(id)?.let(resources::getString) },
        ownPackNames = ownPackNames,
        myWords = stringResource(R.string.content_filter_my_words),
        unknown = stringResource(R.string.content_filter_badge_unknown)
    ) ?: return
    val style = NuvioTheme.textStyles.chip.copy(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NuvioPrimitives.warning.copy(alpha = BAND_ALPHA))
            .padding(start = 7.dp, end = 7.dp, top = 6.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Filled.FilterAlt,
            contentDescription = null,
            tint = NuvioPrimitives.black,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label.name,
            style = style,
            color = NuvioPrimitives.black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (label.more > 0) {
            Text(
                text = stringResource(R.string.content_filter_badge_more, label.more),
                style = style,
                color = NuvioPrimitives.black,
                maxLines = 1
            )
        }
    }
}

private const val BAND_ALPHA = 0.94f
