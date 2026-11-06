package com.nuvio.tv.ui.screens.welcome

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.RowText
import com.nuvio.tv.core.assessment.DeviceSetupCoordinator
import com.nuvio.tv.core.assessment.WelcomeRow
import com.nuvio.tv.core.assessment.WelcomeSummary
import com.nuvio.tv.core.assessment.welcomeSummary
import com.nuvio.tv.core.player.AudioCapabilityReport
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.screens.profile.ProfileWordmark
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.findActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the card needs from the first-launch step (strings are built in composition, in the Activity's locale). */
@OptIn(UnstableApi::class)
internal data class WelcomeData(
    val firstLaunch: DeviceSetupCoordinator.FirstLaunch,
    val audio: AudioCapabilityReport.DirectSupport?
)

/**
 * Runs the first-launch step once per process (Spec 33 D8). Activity-scoped, so a recreate
 * (language change) keeps a card that is showing.
 */
@HiltViewModel
class DeviceWelcomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    private val _welcome = MutableStateFlow<WelcomeData?>(null)
    internal val welcome: StateFlow<WelcomeData?> = _welcome.asStateFlow()
    private var started = false

    /** [activity] lets the setup read the display modes (frame-rate matching). */
    @OptIn(UnstableApi::class)
    fun start(activity: Activity?) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                _welcome.value = withContext(Dispatchers.Default) {
                    val facts = DeviceAssessmentEngine.gatherDeviceFacts(context, activity)
                    DeviceSetupCoordinator.firstLaunch(facts, playerSettingsDataStore)
                        ?.let { WelcomeData(it, AudioCapabilityReport.probeDirectSupport()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Before the record is written the next start tries again; after it, the card is skipped.
                Log.w(TAG, "first-launch setup failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun dismiss() {
        _welcome.value = null
    }

    private companion object {
        const val TAG = "DeviceWelcome"
    }
}

/**
 * The welcome card host (Spec 33 D8): starts the first-launch step and, once it has a result,
 * shows the card while [onHome] (a launcher or deep-link start lands elsewhere; the card waits
 * for Home). [onReview] opens Settings › Playback › Buffer & network; null (Settings locked for
 * this profile) hides Review in Settings.
 */
@Composable
internal fun DeviceWelcomeHost(onHome: Boolean, onReview: (() -> Unit)?) {
    val viewModel: DeviceWelcomeViewModel = hiltViewModel()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.start(context.findActivity()) }
    val data by viewModel.welcome.collectAsState()
    val current = data ?: return
    if (!onHome) return
    val summary = remember(current, context) { summaryOf(current, context) }
    DeviceWelcomeCard(
        summary = summary,
        onContinue = viewModel::dismiss,
        onReview = onReview?.let { review -> { viewModel.dismiss(); review() } }
    )
}

@OptIn(UnstableApi::class)
private fun summaryOf(data: WelcomeData, context: Context): WelcomeSummary =
    welcomeSummary(
        device = data.firstLaunch.device,
        settings = data.firstLaunch.settings,
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        audio = data.audio,
        t = RowText { id, args -> context.getString(id, *args) }
    )

/**
 * `.l11qc.l33c` (sandbox u33-welcome-setup v3, stage a-summary): the glass card over the veil,
 * the wordmark top-left; Continue focused; Back = Continue.
 */
@Composable
private fun DeviceWelcomeCard(summary: WelcomeSummary, onContinue: () -> Unit, onReview: (() -> Unit)?) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.welcome
    val card = marquee.settings
    val continueFocus = remember { FocusRequester() }
    MarqueeVeilDialog(onDismissRequest = onContinue, veil = tokens.veil, initialFocus = continueFocus) {
        ProfileWordmark()
        val shape = RoundedCornerShape(card.signInCardRadius)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = tokens.cardTop, bottom = tokens.cardBottom)
                .width(tokens.cardWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(card.signInCardFill, shape)
                .border(NuvioTheme.spacing.hairline, card.signInCardEdge, shape)
                .padding(
                    start = tokens.cardPaddingHorizontal,
                    end = tokens.cardPaddingHorizontal,
                    top = tokens.cardPaddingTop,
                    bottom = tokens.cardPaddingBottom
                ),
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap)
        ) {
            PanelEyebrowText(text = stringResource(R.string.welcome_eyebrow))
            Text(
                text = summary.deviceName,
                style = NuvioTheme.textStyles.welcomeDevice,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (summary.deviceLine.isNotEmpty()) {
                Text(
                    text = summary.deviceLine,
                    style = NuvioTheme.textStyles.welcomeRowValue,
                    color = marquee.onImage2
                )
            }
            // The rows scroll inside the card if a large UI scale overflows it; the buttons stay put.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(tokens.cardGap)
            ) {
                summary.rows.forEachIndexed { index, row ->
                    WelcomeRowLine(row, if (index == 0) Modifier.padding(top = tokens.rowsTop) else Modifier)
                }
                if (summary.showNetworkNote) {
                    Text(
                        text = stringResource(R.string.welcome_note),
                        style = NuvioTheme.textStyles.settingsNote,
                        color = marquee.onImage2
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = tokens.actionsTop),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                MarqueePrimaryButton(
                    label = stringResource(R.string.welcome_continue),
                    icon = null,
                    onClick = onContinue,
                    focusRequester = continueFocus
                )
                if (onReview != null) {
                    MarqueeGlassTextButton(label = stringResource(R.string.welcome_review), onClick = onReview)
                }
            }
        }
    }
}

/** `.l33r`: tick (set now) or circle arrows (decided at play time), label, value at the end. */
@Composable
private fun WelcomeRowLine(row: WelcomeRow, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.welcome
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = tokens.rowMinHeight)
            .background(tokens.rowFill, RoundedCornerShape(tokens.rowRadius))
            .padding(horizontal = tokens.rowPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.rowIconGap)
    ) {
        Icon(
            imageVector = if (row.applied) Icons.Filled.Check else Icons.Filled.Sync,
            contentDescription = null,
            tint = if (row.applied) marquee.readyText else marquee.onImage3,
            modifier = Modifier.size(tokens.rowIconSize)
        )
        Text(text = row.label, style = NuvioTheme.textStyles.welcomeRowLabel, color = NuvioPrimitives.white)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = row.value,
            style = NuvioTheme.textStyles.welcomeRowValue,
            color = marquee.onImage2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
