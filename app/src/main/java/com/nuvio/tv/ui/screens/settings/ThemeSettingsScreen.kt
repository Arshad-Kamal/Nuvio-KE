package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.data.local.EpisodeOptionsStyle
import com.nuvio.tv.data.local.UiScalePreference
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.theme.getFontFamily
import com.nuvio.tv.ui.util.findActivity
import kotlinx.coroutines.delay
import java.util.Locale

private const val ROW_LANGUAGE = "appearance_language"
private const val ADVANCED_GROUP_KEY = "appearance"

/** Interface scale stepper step, in percent (range: UiScalePreference). */
private const val UI_SCALE_STEP = 5

/**
 * The app languages the picker offers, stored as this tag. `res/xml/locale_config.xml` (read by the
 * system on API 33+) lists the same set, with Indonesian as `id` where this keeps the legacy `in`
 * that saved choices already hold (AppLocaleListTest pins the parity).
 */
internal val APP_LOCALE_TAGS = listOf(
    "en", "ru", "ar", "bg", "bs", "da", "de", "el", "es", "es-419", "hu", "fr", "in", "it",
    "no", "pl", "pt-PT", "pt-BR", "tr", "uk", "cs", "sk", "sl", "sq", "sv", "ta", "ro", "ja",
    "nl", "vi", "hi", "lt", "he", "zh-CN", "zh-TW"
)

private val SCREENSAVER_TIMEOUT_MINUTES = listOf(2, 5, 10)

/** Dim strength options: percent to its description. */
private val SCREENSAVER_DIM_OPTIONS = listOf(
    50 to R.string.appearance_screensaver_dim_gentle,
    70 to R.string.appearance_screensaver_dim_balanced,
    85 to R.string.appearance_screensaver_dim_strong
)

/**
 * Spec 31, Appearance pane (sandbox u11-settings-marquee v6, stage `appearance`): theme swatches,
 * AMOLED, font and language, Screen (interface scale, idle dimming), and an Advanced options group
 * (Dim strength, Pure black surfaces, Episode options style). Rows whose prerequisite is off dim
 * with the reason.
 */
@Composable
fun ThemeSettingsContent(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    // Null until loaded (see the ViewModel); rows wait for it.
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val toast = LocalMarqueeToast.current
    val languageRow = rememberSettingsReturnTarget(ROW_LANGUAGE)
    val advancedGroups = settingsAdvancedGroups()

    var showFontDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showDimDialog by remember { mutableStateOf(false) }
    var showEpisodeStyleDialog by remember { mutableStateOf(false) }
    var pendingLanguageRestart by remember { mutableStateOf(false) }


    val strLanguageSystem = stringResource(R.string.appearance_language_system)
    val supportedLocales = remember(strLanguageSystem) {
        listOf(null to strLanguageSystem) + APP_LOCALE_TAGS.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            tag to locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
        }.sortedBy { it.second }
    }
    var selectedTag by remember {
        mutableStateOf(
            context.getSharedPreferences(LocaleCache.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LocaleCache.PREFS_KEY_TAG, null)?.takeIf { it.isNotEmpty() }
        )
    }
    val currentLocaleName = supportedLocales.firstOrNull { it.first == selectedTag }?.second ?: strLanguageSystem
    val strRestartHint = stringResource(R.string.appearance_language_restart_hint)

    LaunchedEffect(pendingLanguageRestart, showLanguageDialog) {
        if (pendingLanguageRestart && !showLanguageDialog) {
            // Let the dialog window detach before recreating the Activity to avoid focus/window ANRs.
            delay(150)
            context.findActivity()?.recreate() ?: toast?.show(strRestartHint)
            pendingLanguageRestart = false
        }
    }

    val thisTvEveryProfile = stringResource(R.string.settings_this_tv_every_profile)
    val needsIdleDim = stringResource(R.string.appearance_needs_idle_dim)

    SettingsPane(
        title = stringResource(R.string.appearance_title),
        subtitle = stringResource(R.string.appearance_pane_subtitle)
    ) {
        if (uiState == null) return@SettingsPane

        item(key = "appearance_theme_section") {
            SettingsSectionLabel(text = stringResource(R.string.appearance_section_theme))
        }
        item(key = "appearance_themes") {
            SettingsThemeSwatches(
                themes = uiState.availableThemes,
                selected = uiState.selectedTheme,
                onSelect = { viewModel.onEvent(ThemeSettingsEvent.SelectTheme(it)) },
                initialFocusRequester = initialFocusRequester
            )
        }
        item(key = "appearance_amoled") {
            SettingsSwitchRow(
                title = stringResource(R.string.appearance_amoled_mode_title),
                subtitle = stringResource(R.string.appearance_amoled_mode_subtitle),
                checked = uiState.amoledMode,
                onCheckedChange = { viewModel.onEvent(ThemeSettingsEvent.ToggleAmoledMode(it)) }
            )
        }

        item(key = "appearance_font_section") {
            SettingsSectionLabel(text = stringResource(R.string.appearance_section_font_language))
        }
        item(key = "appearance_font") {
            SettingsValueRow(
                title = stringResource(R.string.appearance_font_title),
                value = uiState.selectedFont.displayName,
                onClick = { showFontDialog = true }
            )
        }
        item(key = ROW_LANGUAGE) {
            SettingsValueRow(
                title = stringResource(R.string.appearance_language_title),
                subtitle = thisTvEveryProfile,
                value = currentLocaleName,
                onClick = { showLanguageDialog = true },
                modifier = Modifier.focusRequester(languageRow.requester)
            )
        }

        item(key = "appearance_screen_section") {
            SettingsSectionLabel(text = stringResource(R.string.appearance_section_screen))
        }
        item(key = "appearance_scale") {
            SettingsStepperRow(
                title = stringResource(R.string.ui_scale_title),
                subtitle = thisTvEveryProfile,
                value = uiState.interfaceScale,
                min = UiScalePreference.MIN_PERCENT,
                max = UiScalePreference.MAX_PERCENT,
                step = UI_SCALE_STEP,
                valueText = "${uiState.interfaceScale}%",
                onValueChange = { viewModel.onEvent(ThemeSettingsEvent.StepInterfaceScale(it)) }
            )
        }
        item(key = "appearance_idle_dim") {
            SettingsSwitchRow(
                title = stringResource(R.string.appearance_screensaver_enabled),
                subtitle = stringResource(R.string.appearance_idle_dim_subtitle),
                checked = uiState.screensaverEnabled,
                onCheckedChange = { viewModel.onEvent(ThemeSettingsEvent.ToggleScreensaver(it)) }
            )
        }
        item(key = "appearance_idle_timeout") {
            SettingsValueRow(
                title = stringResource(R.string.appearance_screensaver_timeout),
                subtitle = if (uiState.screensaverEnabled) null else needsIdleDim,
                value = stringResource(R.string.appearance_screensaver_timeout_option, uiState.screensaverTimeoutMinutes),
                enabled = uiState.screensaverEnabled,
                onClick = { showTimeoutDialog = true }
            )
        }

        settingsAdvancedGroup(
            key = ADVANCED_GROUP_KEY,
            count = 3,
            open = advancedGroups.isOpen(ADVANCED_GROUP_KEY),
            onToggle = { advancedGroups.toggle(ADVANCED_GROUP_KEY) }
        ) {
            item(key = "appearance_dim_strength") {
                SettingsValueRow(
                    title = stringResource(R.string.appearance_screensaver_dim),
                    subtitle = if (uiState.screensaverEnabled) {
                        stringResource(R.string.appearance_screensaver_dim_subtitle)
                    } else {
                        needsIdleDim
                    },
                    value = stringResource(R.string.appearance_screensaver_dim_option, uiState.screensaverDimPercent),
                    enabled = uiState.screensaverEnabled,
                    onClick = { showDimDialog = true }
                )
            }
            item(key = "appearance_pure_black_surfaces") {
                SettingsSwitchRow(
                    title = stringResource(R.string.appearance_pure_black_surfaces_title),
                    subtitle = stringResource(
                        if (uiState.amoledMode) {
                            R.string.appearance_amoled_surfaces_mode_subtitle
                        } else {
                            R.string.appearance_needs_amoled
                        }
                    ),
                    checked = uiState.amoledSurfacesMode,
                    enabled = uiState.amoledMode,
                    onCheckedChange = { viewModel.onEvent(ThemeSettingsEvent.ToggleAmoledSurfacesMode(it)) }
                )
            }
            item(key = "appearance_episode_style") {
                SettingsValueRow(
                    title = stringResource(R.string.episode_options_style_title),
                    subtitle = stringResource(R.string.episode_options_style_subtitle),
                    value = stringResource(uiState.episodeOptionsStyle.labelRes()),
                    onClick = { showEpisodeStyleDialog = true }
                )
            }
        }
    }

    if (uiState == null) return

    if (showFontDialog) {
        SettingsPickerPanel(
            title = stringResource(R.string.appearance_font_dialog_title),
            options = uiState.availableFonts.map { font ->
                SettingsPickerOption(font, font.displayName, titleFontFamily = getFontFamily(font))
            },
            selectedValue = uiState.selectedFont,
            onSelect = { font ->
                viewModel.onEvent(ThemeSettingsEvent.SelectFont(font))
            },
            onDismiss = { showFontDialog = false }
        )
    }

    if (showLanguageDialog) {
        SettingsPickerPanel(
            title = stringResource(R.string.appearance_language_dialog_title),
            options = supportedLocales.map { (tag, name) -> SettingsPickerOption(tag, name) },
            selectedValue = selectedTag,
            onSelect = { tag ->
                val previousTag = selectedTag
                val newTag = tag ?: ""
                context.getSharedPreferences(LocaleCache.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(LocaleCache.PREFS_KEY_TAG, newTag).apply()
                LocaleCache.localeTag = newTag
                selectedTag = tag
                if (previousTag != tag) {
                    // The restart rebuilds Settings: focus comes back to this row.
                    languageRow.mark()
                    pendingLanguageRestart = true
                }
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showTimeoutDialog) {
        SettingsPickerPanel(
            title = stringResource(R.string.appearance_screensaver_timeout),
            options = SCREENSAVER_TIMEOUT_MINUTES.map { minutes ->
                SettingsPickerOption(minutes, stringResource(R.string.appearance_screensaver_timeout_option, minutes))
            },
            selectedValue = uiState.screensaverTimeoutMinutes,
            onSelect = { minutes ->
                viewModel.onEvent(ThemeSettingsEvent.SelectScreensaverTimeout(minutes))
            },
            onDismiss = { showTimeoutDialog = false }
        )
    }

    if (showDimDialog) {
        SettingsPickerPanel(
            title = stringResource(R.string.appearance_screensaver_dim),
            options = SCREENSAVER_DIM_OPTIONS.map { (percent, descriptionRes) ->
                SettingsPickerOption(
                    percent,
                    stringResource(R.string.appearance_screensaver_dim_option, percent),
                    stringResource(descriptionRes)
                )
            },
            selectedValue = uiState.screensaverDimPercent,
            onSelect = { percent ->
                viewModel.onEvent(ThemeSettingsEvent.SelectScreensaverDim(percent))
            },
            onDismiss = { showDimDialog = false }
        )
    }

    if (showEpisodeStyleDialog) {
        SettingsPickerPanel(
            title = stringResource(R.string.episode_options_style_title),
            options = listOf(
                SettingsPickerOption(
                    EpisodeOptionsStyle.RICH,
                    stringResource(R.string.episode_options_style_rich),
                    stringResource(R.string.episode_options_style_rich_desc)
                ),
                SettingsPickerOption(
                    EpisodeOptionsStyle.CLASSIC,
                    stringResource(R.string.episode_options_style_classic),
                    stringResource(R.string.episode_options_style_classic_desc)
                )
            ),
            selectedValue = uiState.episodeOptionsStyle,
            onSelect = { style ->
                viewModel.onEvent(ThemeSettingsEvent.SelectEpisodeOptionsStyle(style))
            },
            onDismiss = { showEpisodeStyleDialog = false }
        )
    }
}

private fun EpisodeOptionsStyle.labelRes(): Int = when (this) {
    EpisodeOptionsStyle.CLASSIC -> R.string.episode_options_style_classic
    EpisodeOptionsStyle.RICH -> R.string.episode_options_style_rich
}

/**
 * `.l11sw`: one swatch per theme, OK selects; under them the focused (else selected) theme's name
 * and what its accent colours. Entering the row lands on the selected swatch, which also carries
 * the shell's entry requester.
 */
@Composable
private fun SettingsThemeSwatches(
    themes: List<AppTheme>,
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val tokens = NuvioTheme.marquee.settings
    val requesters = remember(themes) { themes.associateWith { FocusRequester() } }
    val latestSelected by rememberUpdatedState(selected)
    var focusedTheme by remember { mutableStateOf<AppTheme?>(null) }
    // Swatch boxes include the ring, so the circles keep the mockup's gap.
    val ringInset = tokens.swatchRingGap + tokens.swatchRingWidth
    Column(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxs)) {
        Row(
            modifier = Modifier
                .focusProperties { onEnter = { requesters[latestSelected]?.requestFocus() } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(tokens.swatchGap - ringInset * 2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            themes.forEach { theme ->
                key(theme) {
                    val focused = focusedTheme == theme
                    val isSelected = theme == selected
                    val name = theme.localizedName()
                    val ring = when {
                        focused -> NuvioPrimitives.white
                        isSelected -> tokens.swatchSelectedRing
                        else -> Color.Transparent
                    }
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val scale = if (focused) tokens.swatchFocusScale else 1f
                                scaleX = scale
                                scaleY = scale
                            }
                            .size(tokens.swatchSize + ringInset * 2)
                            .border(tokens.swatchRingWidth, ring, CircleShape)
                            .focusRequester(requesters.getValue(theme))
                            .then(
                                if (isSelected && initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    focusedTheme = theme
                                } else if (focusedTheme == theme) {
                                    focusedTheme = null
                                }
                            }
                            .semantics {
                                contentDescription = name
                                this.selected = isSelected
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(theme) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(tokens.swatchSize)
                                .clip(CircleShape)
                                .background(ThemeColors.getColorPalette(theme).accentBrush())
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (focusedTheme ?: selected).localizedName(),
                // Same 13/18 semibold as the stepper value (`.l11sw em`).
                style = NuvioTheme.textStyles.settingsStepValue,
                color = NuvioPrimitives.white
            )
            Text(
                text = stringResource(R.string.appearance_theme_caption),
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioTheme.marquee.onImage3
            )
        }
    }
}

@Composable
private fun AppTheme.localizedName(): String = when (this) {
    AppTheme.GOLD -> stringResource(R.string.theme_color_gold)
    AppTheme.JADE -> stringResource(R.string.theme_color_jade)
    AppTheme.ROSE_GOLD -> stringResource(R.string.theme_color_rose_gold)
    AppTheme.ARCTIC_BLUE -> stringResource(R.string.theme_color_arctic_blue)
    AppTheme.GRAPHITE -> stringResource(R.string.theme_color_graphite)
    AppTheme.CRIMSON -> stringResource(R.string.theme_color_crimson)
    AppTheme.OCEAN -> stringResource(R.string.theme_color_ocean)
    AppTheme.VIOLET -> stringResource(R.string.theme_color_violet)
    AppTheme.EMERALD -> stringResource(R.string.theme_color_emerald)
    AppTheme.AMBER -> stringResource(R.string.theme_color_amber)
    AppTheme.ROSE -> stringResource(R.string.theme_color_rose)
    AppTheme.WHITE -> stringResource(R.string.theme_color_white)
}
