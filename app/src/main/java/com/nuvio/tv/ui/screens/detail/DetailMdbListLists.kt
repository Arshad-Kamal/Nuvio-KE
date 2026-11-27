package com.nuvio.tv.ui.screens.detail

import android.text.format.DateFormat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.data.mdblist.MdbListException
import com.nuvio.tv.data.mdblist.MdbListLibraryTarget
import com.nuvio.tv.data.mdblist.MdbListLists
import com.nuvio.tv.data.mdblist.MdbListOwnedList
import com.nuvio.tv.data.mdblist.MdbListQuota
import com.nuvio.tv.data.mdblist.MdbListTrackingLibraryProvider
import com.nuvio.tv.data.mdblist.isMdbListUnmatchable
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanel
import com.nuvio.tv.ui.screens.profile.ProfilePanelHead
import com.nuvio.tv.ui.screens.settings.trackers.TrackerLogo
import com.nuvio.tv.ui.screens.settings.trackers.TrackerLogoSize
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The detail page's MDBList lists (Spec 105 S3b, D4, D20-D23; mockup v3 C1-C3): the ON N LISTS chip, the Lists button and
 * its panel. The watchlist is S3a's library provider (this TV's view, no call); the own static lists come from
 * [MdbListLists] (membership by call). Works whatever the Library source is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailMdbListLists @Inject constructor(
    private val lists: MdbListLists,
    private val library: MdbListTrackingLibraryProvider,
    private val accounts: TrackingAccountStore,
    private val quota: MdbListQuota,
    private val sources: TrackingSourceController,
    private val profileManager: ProfileManager
) {
    /** Numbers each key the page sees, so a key replaced in place (another account) starts a new session. */
    private val sessions = AtomicLong()

    /**
     * The page's watchlist state and the daily pause, for [item]; nothing while not connected. Each working key of the
     * active profile is its own [DetailMdbListLive.session]: a new key (even replacing one in place) is a new session.
     */
    fun observe(item: LibraryEntryInput): Flow<DetailMdbListLive> = accounts.account(TrackingProviderId.MDBLIST)
        .map { account -> account?.takeUnless { it.revoked }?.accessToken }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            if (key == null || isMdbListUnmatchable(item.itemId)) {
                flowOf(DetailMdbListLive())
            } else {
                val session = sessions.incrementAndGet()
                combine(
                    library.isInLibrary(item.itemId, item.itemType),
                    library.items.map { it.size }.distinctUntilChanged(),
                    quota.all.map { it[profileId()]?.pausedUntilEpochMs }.distinctUntilChanged()
                ) { onWatchlist, count, pausedUntil -> DetailMdbListLive(session, onWatchlist, count, pausedUntil) }
            }
        }

    suspend fun membership(item: LibraryEntryInput): Set<Long> = lists.membership(profileId(), item.itemId, item.itemType)

    /** The lists the panel shows: fresh, or while paused the last ones read under the same key. Throws when neither. */
    suspend fun panelLists(item: LibraryEntryInput): List<MdbListOwnedList> {
        val profileId = profileId()
        val owned = if (quota.state(profileId).isPaused(System.currentTimeMillis())) {
            lists.cachedOwnedLists(profileId) ?: throw MdbListException("MDBList lists not loaded")
        } else {
            lists.ownedLists(profileId)
        }
        val isMovie = MdbListLibraryTarget.of(item.itemId, item.itemType).isMovie
        return owned.filter { it.takes(isMovie) }
    }

    /** D20: the Watchlist row names the Library only while MDBList is the Library source. */
    suspend fun watchlistIsLibrary(): Boolean = sources.activeLibraryProvider.first()?.providerId == TrackingProviderId.MDBLIST

    suspend fun setOnWatchlist(item: LibraryEntryInput, on: Boolean) = library.setInLibrary(profileId(), item, on)

    suspend fun setOnList(listId: Long, item: LibraryEntryInput, on: Boolean) =
        lists.setOnList(profileId(), listId, item.itemId, item.itemType, on)

    private fun profileId(): Int = profileManager.activeProfileId.value
}

/**
 * What the page follows live: [session] (0 = not connected or the title not placeable; else the key's session), the
 * watchlist, and the daily pause's end.
 */
data class DetailMdbListLive(
    val session: Long = 0L,
    val onWatchlist: Boolean = false,
    val watchlistCount: Int = 0,
    val pausedUntilEpochMs: Long? = null
) {
    val connected: Boolean get() = session != 0L
}

/**
 * The page's MDBList lists state. [membership]: the own static lists holding the title, null while unknown (not loaded,
 * failed, or paused at load). [panel]: the open Lists panel. [pending]: rows whose write runs ([MDBLIST_WATCHLIST_ROW] or
 * a list id).
 */
data class DetailMdbListUi(
    val live: DetailMdbListLive = DetailMdbListLive(),
    val membership: Set<Long>? = null,
    val panel: MdbListPanelUi? = null,
    /** Rows whose write runs (kept across a close and reopen, so a second OK cannot resend it). */
    val pending: Set<Long> = emptySet()
) {
    /** C1's ON N LISTS: only while connected and membership is known; null hides it (none, or unknown). */
    val chipCount: Int?
        get() = membership?.takeIf { live.connected }?.let { it.size + if (live.onWatchlist) 1 else 0 }?.takeIf { it > 0 }

    fun isPaused(nowEpochMs: Long): Boolean = live.pausedUntilEpochMs?.let { nowEpochMs < it } == true

    /** A new [next]: the same session keeps what the page knows; a new one (key change, disconnect) starts empty. */
    fun withLive(next: DetailMdbListLive): DetailMdbListUi =
        if (next.session == live.session) copy(live = next) else DetailMdbListUi(next)

    /**
     * A membership read started in [session]: kept only in that session and only where none is known yet, so an older
     * read never overwrites what a confirmed write since set.
     */
    fun withLoadedMembership(session: Long, loaded: Set<Long>): DetailMdbListUi =
        if (session != live.session || !live.connected || membership != null) this else copy(membership = loaded)

    /**
     * MDBList confirmed [listId] now holds the title ([on]) or not: membership follows, and the list's count moves by
     * one when membership changed (an idempotent write of the state already known moves nothing).
     */
    fun withListChange(session: Long, listId: Long, on: Boolean): DetailMdbListUi {
        if (session != live.session) return this
        val known = membership ?: return this
        if ((listId in known) == on) return this
        val step = if (on) 1 else -1
        return copy(
            membership = if (on) known + listId else known - listId,
            panel = panel?.copy(lists = panel.lists.map { list ->
                if (list.id == listId) list.copy(itemCount = (list.itemCount + step).coerceAtLeast(0)) else list
            })
        )
    }
}

/** The open panel: [lists] (own static lists taking the title's type); [watchlistIsLibrary] picks D20's row line. */
data class MdbListPanelUi(
    val lists: List<MdbListOwnedList>,
    val watchlistIsLibrary: Boolean
)

/** The Watchlist row's key in [DetailMdbListUi.pending] (list ids are positive). */
internal const val MDBLIST_WATCHLIST_ROW = 0L

/** C1: the MDBList glyph and ON N LISTS, in the tech badges' outline. */
@Composable
internal fun MdbListListsChip(count: Int, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.techBadge
    val shape = RoundedCornerShape(NuvioTheme.radii.xs)
    Row(
        modifier = modifier
            .border(tokens.outlineWidth, tokens.outline, shape)
            .padding(horizontal = tokens.horizontalPadding, vertical = tokens.outlineWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GLYPH_GAP)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_tracker_mdblist),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(CHIP_GLYPH).clip(RoundedCornerShape(CHIP_GLYPH_RADIUS))
        )
        Text(
            text = pluralStringResource(R.plurals.detail_mdblist_chip, count, count).uppercase(),
            style = NuvioTheme.textStyles.techBadge,
            color = androidx.compose.ui.graphics.Color.White,
            maxLines = 1
        )
    }
}

/**
 * C3 (D20-D21): eyebrow, the title's head (on how many lists, or the pause's end), the Watchlist row then the own lists
 * with a check box each, and the note. Paused (D5): rows dim and OK does nothing. A row whose write runs ignores OK.
 */
@Composable
internal fun MdbListListsPanel(
    title: String,
    state: DetailMdbListUi,
    panel: MdbListPanelUi,
    onToggle: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val membership = state.membership.orEmpty()
    // Re-read when the pause ends, so the rows wake up on time with nothing else recomposing.
    val pausedUntil = state.live.pausedUntilEpochMs
    val paused by produceState(state.isPaused(System.currentTimeMillis()), pausedUntil) {
        val left = (pausedUntil ?: 0L) - System.currentTimeMillis()
        if (left > 0) {
            delay(left)
            value = false
        }
    }
    val first = remember { FocusRequester() }
    val onCount = membership.size + if (state.live.onWatchlist) 1 else 0
    val headLine = when {
        paused -> stringResource(
            R.string.detail_mdblist_paused_until,
            DateFormat.getTimeFormat(context).format(Date(pausedUntil!!))
        )
        onCount == 0 -> stringResource(R.string.detail_mdblist_on_none)
        else -> pluralStringResource(R.plurals.detail_mdblist_on_lists, onCount, onCount)
    }
    MarqueeSidePanel(onDismiss = onDismiss, initialFocus = first, fillHeight = true) {
        PanelEyebrow(text = stringResource(R.string.detail_mdblist_lists_title))
        ProfilePanelHead(title = title, subtitle = headLine) {
            TrackerLogo(logoRes = R.drawable.ic_tracker_mdblist, name = "MDBList", size = TrackerLogoSize.ROW)
        }
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            val count = pluralStringResource(R.plurals.detail_mdblist_titles, state.live.watchlistCount, state.live.watchlistCount)
            ListRow(
                title = stringResource(R.string.detail_mdblist_watchlist),
                subtitle = if (panel.watchlistIsLibrary) stringResource(R.string.detail_mdblist_watchlist_library, count) else count,
                checked = state.live.onWatchlist,
                enabled = !paused,
                focusRequester = first,
                onClick = { if (MDBLIST_WATCHLIST_ROW !in state.pending) onToggle(MDBLIST_WATCHLIST_ROW) }
            )
            panel.lists.forEach { list ->
                ListRow(
                    title = list.name,
                    subtitle = pluralStringResource(R.plurals.detail_mdblist_titles, list.itemCount, list.itemCount),
                    checked = list.id in membership,
                    enabled = !paused,
                    focusRequester = null,
                    onClick = { if (list.id !in state.pending) onToggle(list.id) }
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.detail_mdblist_lists_note),
            style = NuvioTheme.textStyles.caption,
            color = NuvioTheme.marquee.onImage3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = NOTE_PADDING, vertical = NOTE_PADDING)
        )
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    PlayerPanelRow(
        title = title,
        subtitle = subtitle,
        selected = false,
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        trailingIcon = if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank
    )
}

private val CHIP_GLYPH = 13.dp
private val CHIP_GLYPH_RADIUS = 3.dp
private val CHIP_GLYPH_GAP = 6.dp
private val NOTE_PADDING = 6.dp
