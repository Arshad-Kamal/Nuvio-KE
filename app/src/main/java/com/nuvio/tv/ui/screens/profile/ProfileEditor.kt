package com.nuvio.tv.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.contentfilter.OwnTagSearchResult
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ProfileBackgroundSelection
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.ui.components.AvatarPickerGrid
import com.nuvio.tv.ui.components.AvatarTile
import com.nuvio.tv.ui.components.BundledBackgrounds
import com.nuvio.tv.ui.components.BundledCreatures
import com.nuvio.tv.ui.components.LocalLookLibrary
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.components.ProfileBackgroundPicker
import com.nuvio.tv.ui.components.ProfileEditorTab
import com.nuvio.tv.ui.components.ProfileEditorTabs
import com.nuvio.tv.ui.components.avatarCategoryLabel
import com.nuvio.tv.ui.components.parseProfileColor
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.components.marquee.MarqueeSwitchRow
import com.nuvio.tv.ui.components.marquee.MarqueeValueRow
import com.nuvio.tv.ui.components.marquee.builtInPackDescriptionRes
import com.nuvio.tv.ui.components.marquee.builtInPackNameRes
import com.nuvio.tv.ui.screens.collection.DiscardChangesPanel
import com.nuvio.tv.ui.screens.collection.EditorHeaderActions
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * The one profile editor (u10 stages 7, 8) for a new profile or an edit, full screen over the
 * colour field ([background] gets the preview colour, and on the Background tab the previewed
 * background, Spec 44 stage 6; it draws the colour field under the editor veil, a background
 * under the background veil): breadcrumb, Cancel + Create / Save (dim
 * until a name), the live preview + Name field (focused, keyboard up, on entry) on the left, the
 * avatar picker on the right (an Avatar | Background pill pair when editing). Back or
 * Cancel with changes asks "Discard changes?". [onSubmit] saves and reports back: true closes
 * (the caller), false re-arms the button (the caller shows the failure toast).
 * Under the Name field the Access block (Spec 34 D17, sandbox `l9-live-tv-profile-switch`): the
 * Live TV switch when [liveTvEditable] (Manage profiles, primary active), else a read-only note;
 * then the Rating limit (Spec 64 K2, sandbox `spec-64-k2-rating-cap` stages 1-3) per
 * [ratingLimitAccess]: a row opening [RatingLimitPanel] ([openProfiles] for its lock line), a
 * note, or nothing. Then Content filters (Spec 117 P9, sandbox `spec-117-content-filters` stages 1-4,
 * 6) per [contentFilterAccess]: a row opening [ContentFiltersPage] over the editor (its packs
 * [builtInPacks]; own-pack tags found with [tagSearch], null without a TMDB key; [filterPreview],
 * the primary's "Preview filters of" picker, Spec 122 D3), or a note.
 * My photos (Spec 44, [library]) lead the Avatar tab and follow the bundled backgrounds on the
 * Background tab; holding OK or MENU on one opens [MyPhotoPanel]
 * ([photoUserNames] for its "Used by", [onRemovePhoto] for Remove). Add from phone leads My photos on
 * both tabs ([onAddFromPhone]: the caller opens the QR card, stage 3).
 */
@Composable
internal fun ProfileEditor(
    draft: ProfileEditorDraft,
    avatarCatalog: List<AvatarCatalogItem>,
    profileBackgroundCatalog: List<ProfileBackgroundCatalogItem>,
    catalogImageUrl: (String) -> String?,
    background: @Composable (Color, ProfileBackgroundSelection?) -> Unit,
    liveTvEditable: Boolean,
    ratingLimitAccess: RatingLimitAccess,
    openProfiles: List<String>,
    contentFilterAccess: ContentFilterAccess,
    builtInPacks: List<FilterPack>,
    tagSearch: (suspend (String) -> OwnTagSearchResult)?,
    filterPreview: FilterPreviewInputs?,
    onDismiss: () -> Unit,
    onSubmit: (onResult: (Boolean) -> Unit) -> Unit,
    library: LocalLookLibrary,
    photoUserNames: (MyPhoto) -> List<String>,
    onRemovePhoto: (MyPhoto) -> Unit,
    onAddFromPhone: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.profiles
    val nameFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var confirmDiscard by remember { mutableStateOf(false) }
    // One save in flight per press; a failure re-arms it.
    var saving by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(ProfileEditorTab.Avatar) }
    // The My photo whose hold panel is open (stage 7).
    var heldPhoto by remember { mutableStateOf<MyPhoto?>(null) }
    var ratingPanelOpen by remember { mutableStateOf(false) }
    // Set when the panel closes: focus goes back to the row once the panel (which holds focus in) is gone.
    var returnToRatingRow by remember { mutableStateOf(false) }
    val ratingRowFocus = remember { FocusRequester() }
    // Set when the My photo panel closes: focus goes back into the picker, to the held tile (its restorer).
    var returnToPicker by remember { mutableStateOf(false) }
    val pickerFocus = remember { FocusRequester() }
    var filtersPageOpen by remember { mutableStateOf(false) }
    var returnToFiltersRow by remember { mutableStateOf(false) }
    val filtersRowFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val listedPacks = remember(builtInPacks, context, locale) {
        listedPacks(
            builtInPacks,
            name = { id -> builtInPackNameRes(id)?.let(context::getString) ?: id },
            description = { id -> builtInPackDescriptionRes(id)?.let(context::getString).orEmpty() },
            locale = locale
        )
    }
    // Edits always get the Background tab: it holds Normal (clear) even when the catalog is empty.
    val showTabs = !draft.isCreate
    // An in-tree layer drawn over the editor (each holds focus in itself). Its open request leaves the
    // editor's group, so the editor's trap must let it go (a requestFocus runs that trap too: 117 F2).
    val layerOpen = filtersPageOpen || ratingPanelOpen || heldPhoto != null

    val leave: () -> Unit = { if (draft.hasChanges) confirmDiscard = true else onDismiss() }
    // The discard panel is its own window with its own Back; the keyboard takes the first Back itself.
    BackHandler(enabled = !confirmDiscard, onBack = leave)

    LaunchedEffect(Unit) {
        nameFocus.requestFocusAfterFrames()
    }
    LaunchedEffect(returnToRatingRow) {
        if (returnToRatingRow) {
            ratingRowFocus.requestFocusAfterFrames()
            returnToRatingRow = false
        }
    }
    LaunchedEffect(returnToFiltersRow) {
        if (returnToFiltersRow) {
            filtersRowFocus.requestFocusAfterFrames()
            returnToFiltersRow = false
        }
    }
    LaunchedEffect(returnToPicker) {
        if (returnToPicker) {
            pickerFocus.requestFocusAfterFrames()
            returnToPicker = false
        }
    }
    LaunchedEffect(showTabs) {
        if (!showTabs) selectedTab = ProfileEditorTab.Avatar
    }

    val profilesCrumb = stringResource(R.string.settings_profiles)
    val profileCrumb = if (draft.isCreate) stringResource(R.string.profile_editor_new_crumb) else draft.original?.name.orEmpty()
    val previewBackground = draft.previewBackground.takeIf { selectedTab == ProfileEditorTab.Background }

    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        background(parseProfileColor(draft.previewColorHex), previewBackground)

        // Focus stays inside the editor (the picker row is still composed underneath), except into a layer.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { onExit = { if (!layerOpen) cancelFocusChange() } }
                .focusGroup()
        ) {
            MarqueeBreadcrumb(
                if (draft.isCreate) {
                    listOf(profilesCrumb, profileCrumb)
                } else {
                    listOf(profilesCrumb, profileCrumb, stringResource(R.string.profile_editor_edit_crumb))
                }
            )
            EditorHeaderActions(
                cancelLabel = stringResource(R.string.profile_cancel),
                saveLabel = stringResource(if (draft.isCreate) R.string.profile_create_btn else R.string.profile_save),
                // Save stays focusable while saving (focus must not drop out of the editor); the
                // live flag drops a second press.
                canSave = draft.canSave,
                onCancel = leave,
                onSave = {
                    if (!saving) {
                        saving = true
                        onSubmit { saved -> if (!saved) saving = false }
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd)
            )

            Column(
                modifier = Modifier
                    .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = EDITOR_COLUMN_TOP)
                    .editorColumnTopFade()
                    .width(tokens.editorPreviewWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tokens.editorPreviewGap)
            ) {
                ProfileAvatarCircle(
                    name = draft.name,
                    colorHex = draft.previewColorHex,
                    size = tokens.editorAvatarSize,
                    art = draft.previewArt(avatarCatalog, library, catalogImageUrl)
                )
                Text(
                    text = draft.name.ifBlank { stringResource(R.string.profile_name_placeholder) },
                    style = NuvioTheme.textStyles.profileEditorName,
                    color = if (draft.name.isBlank()) marquee.onImage3 else NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = tokens.editorFieldTop),
                    verticalArrangement = Arrangement.spacedBy(tokens.fieldLabelGap)
                ) {
                    PanelEyebrowText(
                        text = stringResource(R.string.profile_name_label),
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xs)
                    )
                    MarqueeNameField(
                        value = draft.name,
                        onValueChange = draft::updateName,
                        maxLength = ProfileEditorDraft.NAME_MAX,
                        placeholder = stringResource(R.string.profile_name_placeholder),
                        focusRequester = nameFocus,
                        // Done moves on to the picker on the right.
                        onDone = { focusManager.moveFocus(FocusDirection.Right) }
                    )
                }
                AccessBlock(
                    draft = draft,
                    liveTvEditable = liveTvEditable,
                    ratingLimitAccess = ratingLimitAccess,
                    ratingRowFocus = ratingRowFocus,
                    onOpenRatingLimit = { ratingPanelOpen = true }
                ) {
                    ContentFiltersAccess(
                        draft = draft,
                        access = contentFilterAccess,
                        listedPacks = listedPacks,
                        rowFocus = filtersRowFocus,
                        onOpen = { filtersPageOpen = true }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(start = tokens.editorRuleStart, top = tokens.editorPreviewTop, bottom = tokens.editorRuleBottom)
                    .width(NuvioTheme.spacing.hairline)
                    .fillMaxHeight()
                    .background(tokens.editorRule)
            )

            Column(
                modifier = Modifier
                    .padding(start = tokens.editorPickerStart, top = tokens.editorPickerTop, bottom = tokens.editorRuleBottom)
                    .width(tokens.editorPickerWidth)
                    .fillMaxHeight()
                    .focusRequester(pickerFocus)
                    .focusRestorer()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(tokens.editorPickerGap)
            ) {
                if (showTabs) {
                    ProfileEditorTabs(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                } else {
                    PanelEyebrowText(text = stringResource(R.string.profile_editor_tab_avatar))
                }
                when (selectedTab) {
                    ProfileEditorTab.Avatar -> AvatarColumn(
                        draft = draft,
                        avatarCatalog = avatarCatalog,
                        photos = library.avatars,
                        onPhotoLongPress = { heldPhoto = it },
                        onAddFromPhone = onAddFromPhone
                    )
                    ProfileEditorTab.Background -> {
                        ProfileBackgroundPicker(
                            backgrounds = profileBackgroundCatalog,
                            bundledBackgrounds = BundledBackgrounds.all,
                            photoBackgrounds = library.backgrounds,
                            customBackgroundUrl = draft.customBackgroundUrl,
                            selected = draft.backgroundPick,
                            standardBackgroundColor = parseProfileColor(draft.colorHex),
                            onStandardBackgroundSelected = draft::selectStandardBackground,
                            onBundledBackgroundSelected = { item ->
                                draft.toggleLocalBackground(ProfileLookPick.Bundled(item.slug))
                            },
                            onBackgroundSelected = { item -> draft.toggleCatalogBackground(item.id) },
                            onCustomBackgroundSelected = draft::toggleCustomBackground,
                            onPhotoBackgroundSelected = { photo ->
                                draft.toggleLocalBackground(ProfileLookPick.Photo(photo.id))
                            },
                            onPickFocused = draft::focusBackground,
                            onPhotoLongPress = { heldPhoto = it },
                            onAddFromPhone = onAddFromPhone,
                            // Before the scroll: the restorer applies to its focus target (the last tile comes back).
                            modifier = Modifier.weight(1f, fill = false).focusRestorer().verticalScroll(rememberScrollState())
                        )
                        EditorNote(stringResource(R.string.profile_background_member_note))
                    }
                }
            }
        }
    }

    heldPhoto?.let { photo ->
        val closePanel = {
            heldPhoto = null
            returnToPicker = true
        }
        MyPhotoPanel(
            photo = photo,
            profileName = draft.name,
            usedBy = photoUserNames(photo),
            onUse = {
                val pick = ProfileLookPick.Photo(photo.id)
                when (photo.kind) {
                    MyPhotoKind.Avatar -> draft.useLocalAvatar(pick)
                    MyPhotoKind.Background -> draft.useLocalBackground(pick)
                }
                closePanel()
            },
            onRemove = {
                closePanel()
                onRemovePhoto(photo)
            },
            onDismiss = closePanel
        )
    }

    if (filtersPageOpen) {
        ContentFiltersPage(
            crumbs = listOf(profilesCrumb, profileCrumb, stringResource(R.string.profile_content_filters_label)),
            profileName = draft.name,
            avatarColorHex = draft.colorHex,
            avatarArt = draft.selectedArt(avatarCatalog, library, catalogImageUrl),
            listed = listedPacks,
            settings = draft.contentFilters,
            onTogglePack = draft::togglePack,
            onEdit = draft::editContentFilters,
            onCreatePack = draft::createOwnPack,
            tagSearch = tagSearch,
            onDismiss = {
                filtersPageOpen = false
                returnToFiltersRow = true
            },
            preview = filterPreview
        )
    }

    if (ratingPanelOpen) {
        val close = {
            ratingPanelOpen = false
            returnToRatingRow = true
        }
        RatingLimitPanel(
            profileName = draft.name,
            avatarColorHex = draft.colorHex,
            avatarArt = draft.selectedArt(avatarCatalog, library, catalogImageUrl),
            current = draft.ratingCap,
            openProfiles = openProfiles,
            onPick = { cap ->
                draft.updateRatingCap(cap)
                close()
            },
            onDismiss = close
        )
    }

    if (confirmDiscard) {
        DiscardChangesPanel(
            body = stringResource(R.string.profile_discard_body),
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                onDismiss()
            }
        )
    }
}

/**
 * The Avatar tab: the picker (Add from phone, My photos avatars, the bundled Creatures, then the
 * server catalog: an empty or unloaded catalog leaves the rest, Spec 44), the focused tile's name +
 * category (`.l10an`; Add from phone: "Shows a QR code"), then the note: on Add from phone or a
 * photo the My photos one (stage 2), else the web-panel one.
 */
@Composable
private fun ColumnScope.AvatarColumn(
    draft: ProfileEditorDraft,
    avatarCatalog: List<AvatarCatalogItem>,
    photos: List<MyPhoto>,
    onPhotoLongPress: (MyPhoto) -> Unit,
    onAddFromPhone: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    var addFocused by remember { mutableStateOf(false) }
    val tiles = remember(avatarCatalog, photos) {
        photos.map { AvatarTile.Photo(it, formatPhotoDate(it.addedAt)) } +
            BundledCreatures.all.map(AvatarTile::Creature) +
            avatarCatalog.map(AvatarTile::Server)
    }
    AvatarPickerGrid(
        tiles = tiles,
        selectedKey = draft.selectedTileKey,
        onTileSelected = draft::toggleTile,
        onTileFocused = draft::focusTile,
        modifier = Modifier.weight(1f, fill = false),
        onPhotoLongPress = { onPhotoLongPress(it.photo) },
        onAddFromPhone = onAddFromPhone,
        onAddFromPhoneFocused = { addFocused = it }
    )
    val focused = draft.focusedTile
    if (addFocused) {
        FocusedTileLabel(
            name = stringResource(R.string.profile_add_from_phone),
            detail = stringResource(R.string.profile_add_from_phone_sub)
        )
    } else if (focused != null) {
        val category = focused.category.trim()
        FocusedTileLabel(
            name = focused.displayName,
            detail = if (category.isNotEmpty()) avatarCategoryLabel(category) else null
        )
    } else {
        Text(
            text = stringResource(R.string.profile_avatar_focus_hint),
            style = NuvioTheme.textStyles.profileField,
            color = marquee.onImage3
        )
    }
    EditorNote(
        stringResource(
            if (addFocused || focused is AvatarTile.Photo) R.string.profile_my_photos_note else R.string.profile_custom_avatar_web_panel_note
        )
    )
}

/** `.l10an`: the focused tile's name with its detail (category, or what Add from phone does) beside it. */
@Composable
private fun FocusedTileLabel(name: String, detail: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = name,
            style = NuvioTheme.textStyles.profileName,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (detail != null) {
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = detail,
                style = NuvioTheme.textStyles.profileAvatarCategory,
                color = NuvioTheme.marquee.onImage3,
                maxLines = 1
            )
        }
    }
}

/**
 * The Access block: the Live TV switch, or (not editable here) the value as a note (D17 Q3); then
 * the Rating limit row (OK = [onOpenRatingLimit]), its note, or nothing ([ratingLimitAccess]); then
 * [trailing] (Content filters).
 */
@Composable
private fun AccessBlock(
    draft: ProfileEditorDraft,
    liveTvEditable: Boolean,
    ratingLimitAccess: RatingLimitAccess,
    ratingRowFocus: FocusRequester,
    onOpenRatingLimit: () -> Unit,
    trailing: @Composable () -> Unit
) {
    val name = draft.name.trim().ifEmpty { stringResource(R.string.profile_live_tv_unnamed) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.marquee.profiles.editorFieldTop),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.profiles.fieldLabelGap)
    ) {
        PanelEyebrowText(
            text = stringResource(R.string.profile_access_label),
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xs)
        )
        if (liveTvEditable) {
            MarqueeSwitchRow(
                label = stringResource(R.string.nav_live_tv),
                checked = draft.liveTvEnabled,
                onCheckedChange = draft::updateLiveTv,
                helper = stringResource(
                    if (draft.liveTvEnabled) R.string.profile_live_tv_on_helper else R.string.profile_live_tv_off_helper,
                    name
                ),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            EditorNote(
                stringResource(
                    if (draft.liveTvEnabled) R.string.profile_live_tv_note_on else R.string.profile_live_tv_note_off,
                    name
                )
            )
        }
        val cap = draft.ratingCap
        when (ratingLimitAccess) {
            RatingLimitAccess.EDIT -> MarqueeValueRow(
                label = stringResource(R.string.profile_rating_limit_label),
                value = stringResource(cap?.labelRes() ?: R.string.rating_limit_none),
                helper = ratingLimitHelper(cap),
                onClick = onOpenRatingLimit,
                modifier = Modifier.focusRequester(ratingRowFocus)
            )
            RatingLimitAccess.NOTE -> EditorNote(
                if (cap == null) {
                    stringResource(R.string.rating_limit_note_none)
                } else {
                    val (movie, tv) = cap.ceiling()
                    stringResource(R.string.rating_limit_note_set, stringResource(cap.labelRes()), movie, tv)
                }
            )
            RatingLimitAccess.NONE -> Unit
        }
        trailing()
    }
}

/**
 * Content filters under the Access block (Spec 117 P9): with [ContentFilterAccess.EDIT] the row
 * ("N on" and the first names, stage 1; OK = [onOpen]), else the note (stage 6). Filters that could
 * not be read, or a pack file that failed ([listedPacks] empty), read as unreadable: the row dims and
 * opens nothing (the page could only show, and save, the wrong filters), the note says so.
 */
@Composable
private fun ContentFiltersAccess(
    draft: ProfileEditorDraft,
    access: ContentFilterAccess,
    listedPacks: List<ListedPack>,
    rowFocus: FocusRequester,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val readable = contentFiltersReadable(draft.originalContentFilters, listedPacks)
    val more = { n: Int -> context.getString(R.string.content_filter_badge_more, n) }
    val onNames = enabledPackNames(draft.contentFilters, listedPacks)
    when (access) {
        ContentFilterAccess.EDIT -> MarqueeValueRow(
            label = stringResource(R.string.profile_content_filters_label),
            value = if (readable) stringResource(R.string.content_filters_on_count, onNames.size) else null,
            helper = when {
                !readable -> stringResource(R.string.content_filters_read_failed)
                onNames.isEmpty() -> stringResource(R.string.content_filters_none_helper)
                else -> enabledNamesSummary(onNames, more)
            },
            onClick = onOpen,
            enabled = readable,
            modifier = Modifier.focusRequester(rowFocus)
        )
        ContentFilterAccess.NOTE -> EditorNote(
            when {
                !readable -> stringResource(R.string.content_filters_note_unreadable)
                onNames.isEmpty() -> stringResource(R.string.content_filters_note_none)
                else -> stringResource(
                    R.string.content_filters_note_set,
                    onNames.size,
                    enabledNamesSummary(onNames, more, shown = NOTE_NAMES_SHOWN)
                )
            }
        )
    }
}

// Spec 117 M1 stages 1, 6 (`.l10pv` top:6px, mask transparent 52px -> opaque 84px): the column rises
// so the third Access row (Content filters) fits on screen; the avatar's top fades under the breadcrumb.
private val EDITOR_COLUMN_TOP = 6.dp
private val EDITOR_FADE_START = 52.dp
private val EDITOR_FADE_END = 84.dp

private fun Modifier.editorColumnTopFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black),
                startY = EDITOR_FADE_START.toPx(),
                endY = EDITOR_FADE_END.toPx()
            ),
            blendMode = BlendMode.DstIn
        )
    }

/** The row's helper: "Up to PG and TV-PG", or what No limit means. */
@Composable
private fun ratingLimitHelper(cap: ContentRatingCap?): String {
    if (cap == null) return stringResource(R.string.rating_limit_none_sub)
    val (movie, tv) = cap.ceiling()
    return stringResource(R.string.rating_limit_up_to, movie, tv)
}

@Composable
private fun EditorNote(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.profileAvatarCategory,
        color = NuvioTheme.marquee.onImage3
    )
}
