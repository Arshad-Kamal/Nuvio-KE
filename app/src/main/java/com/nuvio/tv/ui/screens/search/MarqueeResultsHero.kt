package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.marquee.BACKDROP_FOCAL
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.screens.home.MODERN_HERO_FOCUS_DEBOUNCE_MS
import com.nuvio.tv.ui.screens.home.MarqueeHeroBlock
import com.nuvio.tv.ui.screens.home.MarqueeHeroContent
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.heroBackdropOf
import com.nuvio.tv.ui.screens.home.heroSettleDelayMs
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/**
 * Which result the hero shows (Search/Discover; approved sandbox `u4-search-discover-marquee`
 * Q4): the focused card's key, settled after Home's debounce ([heroSettleDelayMs]) and once the
 * band stops scrolling. Before the first settle the caller falls back to [seedIndex].
 */
@Stable
internal class MarqueeHeroSettle(seedIndex: Int) {
    var seedIndex by mutableStateOf(seedIndex)
        private set
    var settledKey by mutableStateOf<String?>(null)
        private set
    /** Written per D-pad press; observed only by the settle effect (no recomposition). */
    internal var focusedKey by mutableStateOf<String?>(null)
        private set
    internal var settleDelayMs = MODERN_HERO_FOCUS_DEBOUNCE_MS
        private set
    private var lastNavAtMs = 0L

    fun onFocused(key: String) {
        if (key == focusedKey) return
        val now = System.currentTimeMillis()
        settleDelayMs = heroSettleDelayMs(lastNavAtMs, now)
        lastNavAtMs = now
        focusedKey = key
    }

    internal fun settle(key: String) {
        settledKey = key
    }

    /** New results (a filter change): the hero starts again from the first item. */
    fun reset() {
        settledKey = null
        focusedKey = null
        seedIndex = 0
    }
}

/** A [MarqueeHeroSettle] whose settles wait for [scrollState] to stop scrolling. */
@Composable
internal fun rememberMarqueeHeroSettle(seedIndex: Int, scrollState: ScrollableState): MarqueeHeroSettle {
    val settle = remember { MarqueeHeroSettle(seedIndex) }
    LaunchedEffect(settle, scrollState) {
        snapshotFlow { settle.focusedKey }.collectLatest { target ->
            if (target == null) return@collectLatest
            delay(settle.settleDelayMs)
            snapshotFlow { scrollState.isScrollInProgress }.first { !it }
            settle.settle(target)
        }
    }
    return settle
}

/** What [MarqueeResultsHero] draws, and the ambient accent the result cards glow with. */
@Stable
internal class ResultsHeroState(
    val backdropUrl: String?,
    val content: MarqueeHeroContent?,
    val accent: () -> Color
)

/**
 * The hero for [settledItem]: its backdrop (the last one stays while an item has none, so the art
 * never blanks), that backdrop's ambient accent, and the results hero text ([eyebrow], else the item's
 * type eyebrow with seasons/status when the result carries them; no logo).
 */
@Composable
internal fun rememberResultsHeroState(settledItem: ModernCarouselItem?, eyebrow: String? = null): ResultsHeroState {
    val candidateBackdrop = heroBackdropOf(settledItem?.heroPreview)
    var displayedBackdrop by remember { mutableStateOf(candidateBackdrop) }
    if (candidateBackdrop != null && candidateBackdrop != displayedBackdrop) displayedBackdrop = candidateBackdrop
    val accent = rememberAmbientAccentProvider(displayedBackdrop)
    val content = remember(settledItem, eyebrow) {
        settledItem?.let {
            MarqueeHeroContent(
                preview = it.heroPreview.copy(logo = null),
                eyebrow = eyebrow,
                progressFraction = null
            )
        }
    }
    return remember(displayedBackdrop, content, accent) { ResultsHeroState(displayedBackdrop, content, accent) }
}

/**
 * Search/Discover results layer (sandbox `u4-search-discover-marquee` stages 5 and 9): the settled
 * result's backdrop full-bleed under Home's scrims and a bottom-anchored results hero
 * (`stateTitle`, text title, IMDb when present). Not Home's `MarqueeBackdrop`, which records its
 * art as Home's next hero seed. [heroBottomInset]: hero bottom from the screen bottom;
 * [heroMaxHeight]: room above it (the description drops first).
 *
 * [compact] (Search past its first row, Spec 38 Q4 B): the hero moves to the top instead
 * (`search.compactHeroTop`, 26 dp title, the eyebrow followed by [compactContext]) over one
 * vertical scrim, leaving the screen under `search.compactBandTop` to the band.
 */
@Composable
internal fun MarqueeResultsHero(
    hero: ResultsHeroState,
    heroBottomInset: Dp,
    heroMaxHeight: Dp,
    drawTopScrim: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    compactContext: String? = null
) {
    val marquee = NuvioTheme.marquee
    val search = marquee.search
    val content = hero.content
    Box(modifier = modifier.fillMaxSize()) {
        MarqueeBackdropImage(url = hero.backdropUrl, focal = BACKDROP_FOCAL, modifier = Modifier.fillMaxSize())
        if (compact) {
            val scrim = remember(search.compactScrimStops) {
                Brush.verticalGradient(
                    *search.compactScrimStops.map { (at, alpha) -> at to NuvioPrimitives.black.copy(alpha = alpha) }.toTypedArray()
                )
            }
            Box(modifier = Modifier.fillMaxSize().background(scrim))
        } else {
            MarqueeScrims(
                leftStops = marquee.scrimLeftStops,
                bottomStops = marquee.scrimBottomStops,
                drawTopScrim = drawTopScrim
            )
        }
        if (content != null) {
            val home = marquee.home
            val safeStart = NuvioTheme.layout.tvSafeHorizontal
            MarqueeHeroBlock(
                content = content,
                accent = hero.accent,
                showImdbRatings = true,
                titleStyle = if (compact) NuvioTheme.textStyles.heroTitleCompact else NuvioTheme.textStyles.stateTitle,
                gap = if (compact) search.compactHeroGap else home.heroGap,
                width = if (compact) search.compactHeroWidth else home.heroWidth,
                descriptionWidth = if (compact) search.compactHeroDescriptionWidth else home.heroDescriptionWidth,
                eyebrowContext = compactContext.takeIf { compact },
                modifier = if (compact) {
                    Modifier
                        .padding(start = safeStart, top = search.compactHeroTop)
                        .heightIn(max = (search.compactBandTop - search.compactHeroTop - search.compactHeroGap).coerceAtLeast(0.dp))
                } else {
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = safeStart, bottom = heroBottomInset)
                        .heightIn(max = heroMaxHeight)
                }
            )
        }
    }
}
