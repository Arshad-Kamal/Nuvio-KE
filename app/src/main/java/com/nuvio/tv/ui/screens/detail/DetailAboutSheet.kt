package com.nuvio.tv.ui.screens.detail

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.TMDB_MARK
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.isoDisplayNames
import com.nuvio.tv.ui.util.localizedGenreLabel
import java.util.Locale

private const val SEPARATOR = " \u00B7 "

/**
 * About sheet (sandbox data-additions-marquee stage 4; Detail's Ask layer): a veil over the page
 * (the caller fades its content out; the backdrop stays), two columns in one D-pad scroll. Left:
 * eyebrow, title, original title, the full description, awards. Right: release dates in the
 * viewer's region, age rating, parents guide, scores, people and genre chips, production and
 * the collection row. Chips open the person page (TMDB credits only, Spec 26 R4) or Discover
 * on the genre (only when a catalog of the title's type lists it, R3); plain names otherwise.
 * Up/Down move focus between chips and rows, else scroll a step. Back closes.
 */
@Composable
internal fun DetailAboutSheet(
    meta: Meta,
    eyebrow: String,
    accent: () -> Color,
    extras: DetailAboutExtras,
    isTvShow: Boolean,
    showRatings: Boolean,
    collectionName: String?,
    collection: List<MetaPreview>,
    onPersonClick: (AboutPerson) -> Unit,
    onGenreClick: (String) -> Unit,
    onOpenCollection: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val detail = NuvioTheme.marquee.detail
    val people = remember(meta.castMembers, meta.director, meta.writer, meta.cast) { aboutPeople(meta) }
    val genres = remember(meta.genres) { meta.genres.map(String::trim).filter(String::isNotBlank).distinct() }
    val openableGenres = remember(genres, extras.discoverGenres) {
        genres.filter { it.lowercase(Locale.ROOT) in extras.discoverGenres }.toSet()
    }
    val showCollection = !collectionName.isNullOrBlank() && collection.isNotEmpty()
    // First focus: the first chip or row that opens something, else the scroll area itself.
    val firstFocusTarget: Any? = remember(people, genres, openableGenres, showCollection, onOpenCollection != null) {
        (people.crew + people.cast).firstOrNull { it.tmdbId != null }
            ?: genres.firstOrNull { it in openableGenres }
            ?: if (showCollection && onOpenCollection != null) CollectionFocus else null
    }
    val firstFocus = remember { FocusRequester() }

    AboutSheetScaffold(
        onDismiss = onDismiss,
        hasFocusTargets = firstFocusTarget != null,
        firstFocus = firstFocus,
        area = Modifier.padding(
            start = NuvioTheme.layout.tvSafeHorizontal,
            end = NuvioTheme.layout.tvSafeHorizontal,
            top = detail.aboutTop
        )
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(detail.aboutColumnGap)) {
            AboutStory(meta = meta, eyebrow = eyebrow, accent = accent, modifier = Modifier.width(detail.aboutLeftWidth))
            Column(modifier = Modifier.weight(1f).padding(end = NuvioTheme.spacing.lg)) {
                AboutFacts(
                    meta = meta,
                    extras = extras,
                    isTvShow = isTvShow,
                    showRatings = showRatings,
                    people = people,
                    genres = genres,
                    openableGenres = openableGenres,
                    collectionName = collectionName.takeIf { showCollection },
                    collection = collection,
                    firstFocusTarget = firstFocusTarget,
                    firstFocus = firstFocus,
                    onPersonClick = onPersonClick,
                    onGenreClick = onGenreClick,
                    onOpenCollection = onOpenCollection
                )
            }
        }
    }
}

/** [DetailAboutSheet]'s first-focus marker for the collection row. */
private object CollectionFocus

/** Left column: eyebrow, title, original title (when it differs), description, awards. */
@Composable
private fun AboutStory(meta: Meta, eyebrow: String, accent: () -> Color, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    val styles = NuvioTheme.textStyles
    val gap = marquee.detail.aboutBlockGap
    Column(modifier = modifier) {
        MarqueeEyebrow(text = stringResource(R.string.detail_about_eyebrow, eyebrow), accent = accent)
        Text(
            text = meta.name,
            style = styles.blockTitle,
            color = NuvioPrimitives.white,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        meta.originalTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(meta.name.trim(), ignoreCase = true) }?.let {
            Text(
                text = stringResource(R.string.detail_about_original_title, it),
                style = styles.meta,
                color = marquee.onImage3,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        meta.description?.trim()?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = styles.bodyLong,
                color = marquee.onImageBody,
                modifier = Modifier.padding(top = gap)
            )
        }
        meta.awards?.trim()?.takeIf { it.isNotBlank() }?.let { awards ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = gap)
            ) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEvents,
                    contentDescription = null,
                    tint = NuvioPrimitives.imdb,
                    modifier = Modifier.size(16.dp)
                )
                Text(text = awards, style = styles.meta, fontWeight = FontWeight.SemiBold, color = NuvioPrimitives.imdb)
            }
        }
    }
}

/**
 * Right column: release, rating and guide; scores; people; genres; production and collection.
 * [firstFocusTarget] (a person, a genre or [CollectionFocus]) carries [firstFocus].
 */
@Composable
private fun AboutFacts(
    meta: Meta,
    extras: DetailAboutExtras,
    isTvShow: Boolean,
    showRatings: Boolean,
    people: AboutPeople,
    genres: List<String>,
    openableGenres: Set<String>,
    collectionName: String?,
    collection: List<MetaPreview>,
    firstFocusTarget: Any?,
    firstFocus: FocusRequester,
    onPersonClick: (AboutPerson) -> Unit,
    onGenreClick: (String) -> Unit,
    onOpenCollection: (() -> Unit)?
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    // The first section head drops its top margin.
    var anySection = false
    @Composable
    fun SectionHead(text: String) {
        AboutSectionHead(text = text, first = !anySection)
        anySection = true
    }
    fun focusFor(target: Any): Modifier =
        if (target == firstFocusTarget) Modifier.focusRequester(firstFocus) else Modifier

    // Release · <country>: the region's dated releases, else the addon's years.
    val country = remember(meta.releaseDates, extras.releaseRegions) {
        selectReleaseCountry(meta.releaseDates, extras.releaseRegions)
    }
    val releases = remember(country) { aboutReleases(country) }
    val released = meta.releaseInfo?.trim()?.takeIf { it.isNotBlank() }
    val rated = meta.ageRating?.trim()?.takeIf { it.isNotBlank() }
    val guide = extras.parentalWarnings
    if (releases.isNotEmpty() || released != null || rated != null || guide.isNotEmpty()) {
        SectionHead(
            if (country != null && releases.isNotEmpty()) {
                stringResource(R.string.detail_about_release_in, isoDisplayNames(country.countryCode, isCountry = true))
            } else {
                stringResource(R.string.detail_about_release)
            }
        )
        if (releases.isNotEmpty()) {
            releases.forEach { release ->
                AboutFactRow(label = releaseTypeLabel(release.type)) {
                    AboutValue(boldThenMuted(formatShortDate(release.date), release.note))
                }
            }
        } else if (released != null) {
            AboutFactRow(label = stringResource(R.string.detail_about_released)) { AboutValue(boldThenMuted(released, null)) }
        }
        if (rated != null) {
            AboutFactRow(label = stringResource(R.string.detail_about_rated)) {
                AboutValue(boldThenMuted(rated, meta.ageRatingDescriptors.joinToString(", ").takeIf { it.isNotBlank() }))
            }
        }
        if (guide.isNotEmpty()) {
            AboutFactRow(label = stringResource(R.string.detail_about_parents_guide)) {
                AboutValue(
                    remember(guide) {
                        buildAnnotatedString {
                            guide.forEachIndexed { index, warning ->
                                if (index > 0) append(SEPARATOR)
                                append(warning.label)
                                append(' ')
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    append(warning.severity.lowercase())
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    // Scores: IMDb and TMDB, each under its own mark (26/B2), behind the ratings setting.
    val imdb = formatRatingScore(meta.imdbRating)
    val tmdb = formatRatingScore(meta.tmdbRating)
    if (showRatings && (imdb != null || tmdb != null)) {
        SectionHead(stringResource(R.string.detail_about_ratings))
        AboutFactRow(label = stringResource(R.string.detail_about_scores)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (imdb != null) {
                    ImdbRatingSourceLabel()
                    AboutValue(boldThenMuted(imdb, null))
                }
                if (tmdb != null) {
                    if (imdb != null) Box(Modifier.width(6.dp))
                    Text(
                        text = TMDB_MARK,
                        style = NuvioTheme.textStyles.meta,
                        fontWeight = FontWeight.Bold,
                        color = NuvioPrimitives.tmdb,
                        maxLines = 1
                    )
                    val votes = meta.tmdbVoteCount?.takeIf { it > 0 }?.let {
                        stringResource(R.string.detail_about_votes, compactCount(it))
                    }
                    AboutValue(boldThenMuted(tmdb, votes))
                }
            }
        }
    }

    // People: crew, then cast with characters; chips with a TMDB id open the person page.
    if (people.crew.isNotEmpty() || people.cast.isNotEmpty()) {
        val linked = (people.crew + people.cast).any { it.tmdbId != null }
        SectionHead(stringResource(if (linked) R.string.detail_about_people_hint else R.string.detail_about_people))
        listOf(people.crew, people.cast).filter { it.isNotEmpty() }.forEachIndexed { index, group ->
            AboutChipRow(modifier = Modifier.padding(top = if (index > 0) detail.aboutChipGap else 0.dp)) {
                group.forEach { person ->
                    AboutChip(
                        text = person.name,
                        role = person.role,
                        onClick = if (person.tmdbId != null) ({ onPersonClick(person) }) else null,
                        modifier = focusFor(person)
                    )
                }
            }
        }
    }

    // Genres: chips open Discover on the genre when a catalog of this type lists it.
    if (genres.isNotEmpty()) {
        SectionHead(
            stringResource(if (openableGenres.isNotEmpty()) R.string.detail_about_genres_hint else R.string.detail_about_genres)
        )
        AboutChipRow {
            genres.forEach { genre ->
                AboutChip(
                    text = localizedGenreLabel(genre),
                    role = null,
                    onClick = if (genre in openableGenres) ({ onGenreClick(genre) }) else null,
                    modifier = focusFor(genre)
                )
            }
        }
    }

    // Production: studios or networks · country · language (the facts that left the page's meta
    // line), then the collection row.
    val companies = remember(meta.networks, meta.productionCompanies, isTvShow) {
        (if (isTvShow) meta.networks + meta.productionCompanies else meta.productionCompanies + meta.networks)
            .map { it.name }
            .distinct()
    }
    val origin = listOfNotNull(
        meta.country?.trim()?.takeIf { it.isNotBlank() }?.let { isoDisplayNames(it, isCountry = true) },
        meta.language?.trim()?.takeIf { it.isNotBlank() }?.let { isoDisplayNames(it, isCountry = false) }
    ).joinToString(SEPARATOR).takeIf { it.isNotBlank() }
    if (companies.isNotEmpty() || origin != null || collectionName != null) {
        SectionHead(stringResource(R.string.detail_about_production))
        if (companies.isNotEmpty()) {
            val label = stringResource(
                if (isTvShow && meta.networks.isNotEmpty()) R.string.detail_section_network else R.string.detail_section_studios
            )
            AboutFactRow(label = label) { AboutValue(boldThenMuted(companies.joinToString(", "), origin)) }
        } else if (origin != null) {
            AboutFactRow(label = stringResource(R.string.detail_about_origin)) { AboutValue(boldThenMuted(origin, null)) }
        }
        if (collectionName != null) {
            AboutCollectionRow(
                name = collectionName,
                collection = collection,
                onOpen = onOpenCollection,
                modifier = focusFor(CollectionFocus)
            )
        }
    }
}

/** Section head: eyebrow style in `onImage3`; [first] drops the top margin. */
@Composable
private fun AboutSectionHead(text: String, first: Boolean) {
    val detail = NuvioTheme.marquee.detail
    Text(
        text = text.uppercase(),
        style = NuvioTheme.textStyles.eyebrow,
        color = NuvioTheme.marquee.onImage3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = if (first) 0.dp else detail.aboutSectionTop, bottom = detail.aboutSectionBottom)
    )
}

/** Fact row: the [labelWidth] label column in `onImage3`, then [value]; a hairline on top. */
@Composable
internal fun AboutFactRow(
    label: String,
    labelWidth: Dp = NuvioTheme.marquee.detail.aboutLabelWidth,
    value: @Composable RowScope.() -> Unit
) {
    val detail = NuvioTheme.marquee.detail
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(detail.aboutLabelGap),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(detail.aboutRule, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .padding(vertical = detail.aboutRowPadding)
    ) {
        Text(
            text = label,
            style = NuvioTheme.textStyles.meta,
            color = NuvioTheme.marquee.onImage3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelWidth)
        )
        value()
    }
}

@Composable
internal fun AboutValue(text: AnnotatedString) {
    Text(text = text, style = NuvioTheme.textStyles.meta, color = NuvioPrimitives.white)
}

/** "[bold] · [muted]": [bold] semibold, the muted part in `onImage3`. */
@Composable
private fun boldThenMuted(bold: String, muted: String?): AnnotatedString {
    val mutedColor = NuvioTheme.marquee.onImage3
    return remember(bold, muted, mutedColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            if (muted != null) withStyle(SpanStyle(color = mutedColor)) { append(SEPARATOR + muted) }
        }
    }
}

@Composable
private fun AboutChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val gap = NuvioTheme.marquee.detail.aboutChipGap
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        modifier = modifier
    ) { content() }
}

/**
 * A chip: name + [role] (`onImage3`) in `panelPill`. With [onClick]: `glassFill`, focused white
 * with black text. Without: the same text unfilled and never focused (a plain name, Spec 26 R4).
 */
@Composable
private fun AboutChip(text: String, role: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val style = NuvioTheme.textStyles.panelPill
    if (onClick == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.height(detail.aboutChipHeight).padding(horizontal = detail.aboutChipPadding)
        ) {
            Text(text = chipLabel(text, role, marquee.onImage3), style = style, color = marquee.onImage2, maxLines = 1)
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.height(detail.aboutChipHeight),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = marquee.glassFill,
            contentColor = NuvioPrimitives.white,
            focusedContainerColor = NuvioPrimitives.white,
            focusedContentColor = NuvioPrimitives.black,
            pressedContainerColor = NuvioPrimitives.white,
            pressedContentColor = NuvioPrimitives.black
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        contentPadding = PaddingValues(horizontal = detail.aboutChipPadding)
    ) {
        val roleColor = if (focused) NuvioPrimitives.black.copy(alpha = 0.55f) else marquee.onImage3
        Text(text = chipLabel(text, role, roleColor), style = style, maxLines = 1)
    }
}

@Composable
private fun chipLabel(name: String, role: String?, roleColor: Color): AnnotatedString =
    remember(name, role, roleColor) {
        buildAnnotatedString {
            append(name)
            if (role != null) {
                append(' ')
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = roleColor)) { append(role) }
            }
        }
    }

/**
 * Collection row: the first title's poster, the name, "<n> titles · years, OK opens it". OK
 * ([onOpen]) shows the band's Collection tab; without it the row only reads.
 */
@Composable
private fun AboutCollectionRow(
    name: String,
    collection: List<MetaPreview>,
    onOpen: (() -> Unit)?,
    modifier: Modifier
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val count = pluralStringResource(R.plurals.detail_about_collection_titles, collection.size, collection.size)
    val years = remember(collection) { collectionYears(collection) }
    val summary = listOfNotNull(count, years).joinToString(SEPARATOR)
    AboutFactRow(label = stringResource(R.string.detail_about_collection)) {
        Surface(
            onClick = { onOpen?.invoke() },
            enabled = onOpen != null,
            modifier = modifier,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(detail.descriptionFocusRadius)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = marquee.glassFill,
                pressedContainerColor = marquee.glassFill,
                disabledContainerColor = Color.Transparent
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(end = 10.dp)
            ) {
                AsyncImage(
                    model = collection.firstOrNull()?.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = detail.aboutPosterWidth, height = detail.aboutPosterHeight)
                        .clip(RoundedCornerShape(detail.aboutPosterRadius))
                        .background(marquee.glassFill)
                )
                Column {
                    Text(text = name, style = NuvioTheme.textStyles.meta, fontWeight = FontWeight.SemiBold, color = NuvioPrimitives.white)
                    Text(
                        text = if (onOpen != null) stringResource(R.string.detail_about_collection_detail, summary) else summary,
                        style = NuvioTheme.textStyles.meta,
                        color = marquee.onImage3
                    )
                }
            }
        }
    }
}

@Composable
private fun releaseTypeLabel(type: Int?): String = stringResource(
    when (type) {
        1 -> R.string.detail_about_release_premiere
        2 -> R.string.detail_about_release_limited
        3 -> R.string.detail_about_release_theatrical
        4 -> R.string.detail_about_release_digital
        5 -> R.string.detail_about_release_physical
        6 -> R.string.detail_about_release_tv
        else -> R.string.detail_about_released
    }
)

/** 1234 -> "1.2K" in the device locale. */
private fun compactCount(count: Int): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(count)
