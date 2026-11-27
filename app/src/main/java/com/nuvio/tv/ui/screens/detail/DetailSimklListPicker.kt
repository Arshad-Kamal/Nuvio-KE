package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.data.simkl.SimklLibraryTarget
import com.nuvio.tv.data.simkl.SimklListing
import com.nuvio.tv.data.simkl.SimklRelated
import com.nuvio.tv.data.simkl.SimklTrackingLibraryProvider
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.screens.library.libraryTrackerListLabel
import com.nuvio.tv.ui.screens.settings.trackers.TrackerLogo
import com.nuvio.tv.ui.screens.settings.trackers.TrackerLogoSize
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * The detail page's Simkl lists (Spec 106 D2, F1-F3): with Simkl as the active profile's Library source, OK on the
 * Library button opens a list picker instead of toggling (Simkl keeps a title on exactly one list). Reads come from this
 * TV's view of Simkl; each write decides again from Simkl's rows ([SimklTrackingLibraryProvider]).
 */
class DetailSimklLists @Inject constructor(
    private val sources: TrackingSourceController,
    private val library: SimklTrackingLibraryProvider,
    private val related: SimklRelated,
    private val profileManager: ProfileManager
) {
    /** True while Simkl is the Library source in use (chosen and connected). */
    suspend fun isLibrarySource(): Boolean = sources.activeLibraryProvider.first()?.providerId == TrackingProviderId.SIMKL

    suspend fun picker(item: LibraryEntryInput): SimklListPickerUi = SimklListPickerUi(
        isMovie = SimklLibraryTarget.of(item.itemId, item.itemType).isMovie,
        listing = library.listing(profileId(), item)
    )

    suspend fun pageAddress(item: LibraryEntryInput): String? = related.pageAddress(item.itemId, item.itemType)

    /** Returns the list the title was on before, null for none. */
    suspend fun move(item: LibraryEntryInput, to: TrackingListStatus): TrackingListStatus? =
        library.moveToList(profileId(), item, to)

    suspend fun remove(item: LibraryEntryInput, agreed: SimklListing?) = library.removeFromSimkl(profileId(), item, agreed)

    private fun profileId(): Int = profileManager.activeProfileId.value
}

/**
 * The open Simkl list picker (F1/F2), or its Remove confirm (F1b) when [confirmingRemoval]. [pageAddress] fills in when
 * Simkl answers (the link back). [focusRemove]: the picker reopens on Remove from Simkl (the confirm's Cancel).
 */
data class SimklListPickerUi(
    val isMovie: Boolean,
    val listing: SimklListing,
    val pageAddress: String? = null,
    val confirmingRemoval: Boolean = false,
    val focusRemove: Boolean = false
)

/** A film's Simkl lists (Simkl has no Watching or On hold for films), else all five in Simkl's order. */
internal fun simklPickerLists(isMovie: Boolean): List<TrackingListStatus> =
    if (isMovie) {
        listOf(TrackingListStatus.PLAN_TO_WATCH, TrackingListStatus.COMPLETED, TrackingListStatus.DROPPED)
    } else {
        TrackingListStatus.entries
    }

/** The row the picker opens on: Remove from Simkl when asked, else the current list, else Plan to watch (F2). */
internal fun simklPickerFocus(picker: SimklListPickerUi): TrackingListStatus? = when {
    picker.focusRemove && picker.listing.list != null -> null
    picker.listing.list in simklPickerLists(picker.isMovie) -> picker.listing.list
    else -> TrackingListStatus.PLAN_TO_WATCH
}

/** What F1b's body names (each part only when there is one). */
internal enum class SimklRemovalBody { EPISODES_AND_RATING, EPISODES, RATING, FILM_AND_RATING, FILM, LIST }

internal fun simklRemovalBody(listing: SimklListing, isMovie: Boolean): SimklRemovalBody {
    val rated = listing.rating != null
    return when {
        listing.watched > 0 && isMovie -> if (rated) SimklRemovalBody.FILM_AND_RATING else SimklRemovalBody.FILM
        listing.watched > 0 -> if (rated) SimklRemovalBody.EPISODES_AND_RATING else SimklRemovalBody.EPISODES
        rated -> SimklRemovalBody.RATING
        else -> SimklRemovalBody.LIST
    }
}

/** "To keep them, choose Dropped instead": only when the body names something and the title is not on Dropped already. */
internal fun simklRemovalKeepHint(listing: SimklListing, isMovie: Boolean): Boolean =
    simklRemovalBody(listing, isMovie) != SimklRemovalBody.LIST && listing.list != TrackingListStatus.DROPPED

/** F1/F2's panel: right of centre over the hero (drawn at x 500 of 960, 340 wide), on F1's .55 veil. */
private val PICKER_WIDTH = 340.dp
private val PICKER_OFFSET_X = 190.dp

/** F1b: drawn 460 wide over a .78 veil (the picker is closed under it). */
private val CONFIRM_WIDTH = 460.dp
private const val CONFIRM_VEIL_ALPHA = 0.78f

@Composable
internal fun SimklListPickerPanel(
    picker: SimklListPickerUi,
    onPick: (TrackingListStatus) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.stream
    val context = LocalContext.current
    val lists = simklPickerLists(picker.isMovie)
    val focus = simklPickerFocus(picker)
    val requesters = remember(lists) { lists.associateWith { FocusRequester() } }
    val removeRequester = remember { FocusRequester() }
    val initial = focus?.let(requesters::get) ?: removeRequester
    MarqueeVeilDialog(onDismissRequest = onDismiss, veil = tokens.confirmDim, initialFocus = initial) {
        CompactPanel(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = PICKER_OFFSET_X)
                .width(PICKER_WIDTH),
            contentPadding = PaddingValues(
                start = tokens.panelPaddingHorizontal,
                top = tokens.panelPaddingTop,
                end = tokens.panelPaddingHorizontal,
                bottom = tokens.panelPaddingHorizontal
            )
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackerLogo(logoRes = R.drawable.ic_tracker_simkl, name = "Simkl", size = TrackerLogoSize.LABEL)
                PanelEyebrowText(text = stringResource(R.string.detail_simkl_list_title))
            }
            PanelRule()
            lists.forEach { list ->
                PlayerPanelRow(
                    title = libraryTrackerListLabel(context, list),
                    selected = list == picker.listing.list,
                    onClick = { onPick(list) },
                    titleEnd = stringResource(R.string.detail_simkl_completed_marks_watched)
                        .takeIf { picker.isMovie && list == TrackingListStatus.COMPLETED },
                    titleEndInline = true,
                    focusRequester = requesters.getValue(list)
                )
            }
            if (picker.listing.list != null) {
                PlayerPanelRow(
                    title = stringResource(R.string.detail_simkl_remove),
                    selected = false,
                    onClick = onRemove,
                    destructive = true,
                    focusRequester = removeRequester
                )
            }
            picker.pageAddress?.let { address ->
                Text(
                    text = styled(stringResource(R.string.detail_simkl_page, address), listOf(address), LINK_ADDRESS),
                    style = NuvioTheme.textStyles.caption.copy(fontWeight = FontWeight.Normal),
                    color = NuvioPrimitives.white.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp)
                )
            }
        }
    }
}

/** F1b: Cancel focused; Back and Cancel return to the picker ([onCancel]). */
@Composable
internal fun SimklRemoveConfirmPanel(
    title: String,
    picker: SimklListPickerUi,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val tokens = NuvioTheme.marquee.stream
    val cancelFocus = remember { FocusRequester() }
    val body = removalBody(title, picker)
    MarqueeCentredPanel(
        onDismissRequest = onCancel,
        veil = Color.Black.copy(alpha = CONFIRM_VEIL_ALPHA),
        width = CONFIRM_WIDTH,
        initialFocus = cancelFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.detail_simkl_remove_title, title))
        Text(
            text = body,
            style = NuvioTheme.textStyles.panelBody,
            color = tokens.dialogBody,
            modifier = Modifier.padding(horizontal = tokens.dialogTextInset)
        )
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancelFocus)
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.detail_simkl_remove_confirm),
                onClick = onConfirm,
                restingLabelColor = NuvioPrimitives.errorOnImage
            )
        }
    }
}

@Composable
private fun removalBody(title: String, picker: SimklListPickerUi): AnnotatedString {
    val listing = picker.listing
    val context = LocalContext.current
    val episodes = pluralStringResource(R.plurals.detail_simkl_remove_episodes_count, listing.watched, listing.watched)
    val rating = listing.rating?.let { stringResource(R.string.detail_simkl_rating, it) }.orEmpty()
    val list = listing.list?.let { libraryTrackerListLabel(context, it) }.orEmpty()
    val text = when (simklRemovalBody(listing, picker.isMovie)) {
        SimklRemovalBody.EPISODES_AND_RATING -> stringResource(R.string.detail_simkl_remove_body_episodes_rating, episodes, title, rating)
        SimklRemovalBody.EPISODES -> stringResource(R.string.detail_simkl_remove_body_episodes, episodes, title)
        SimklRemovalBody.RATING -> stringResource(R.string.detail_simkl_remove_body_rating, rating, title)
        SimklRemovalBody.FILM_AND_RATING -> stringResource(R.string.detail_simkl_remove_body_film_rating, rating)
        SimklRemovalBody.FILM -> stringResource(R.string.detail_simkl_remove_body_film)
        SimklRemovalBody.LIST -> stringResource(R.string.detail_simkl_remove_body_list, title, list)
    }
    val keep = stringResource(R.string.detail_simkl_remove_keep).takeIf { simklRemovalKeepHint(listing, picker.isMovie) }
    val bold = listOfNotNull(episodes.takeIf { listing.watched > 0 && !picker.isMovie }, rating.takeIf(String::isNotEmpty))
    return styled(if (keep != null) "$text\n\n$keep" else text, bold, EMPHASIS)
}

/** [text] with the first match of each of [parts] in [style]. */
private fun styled(text: String, parts: List<String>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    parts.forEach { part ->
        val at = text.indexOf(part)
        if (at >= 0) addStyle(style, at, at + part.length)
    }
}

/** F1b's count and rating: semibold white. */
private val EMPHASIS = SpanStyle(fontWeight = FontWeight.SemiBold, color = NuvioPrimitives.white)

/** F1's link line: the address in medium white .75 (`.s106link b`). */
private val LINK_ADDRESS = SpanStyle(fontWeight = FontWeight.Medium, color = NuvioPrimitives.white.copy(alpha = 0.75f))
