package com.nuvio.tv.ui.components.marquee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.ReleaseFacts
import com.nuvio.tv.domain.model.ReleaseAudioChannel
import com.nuvio.tv.domain.model.ReleaseAudioTag
import com.nuvio.tv.domain.model.ReleaseQuality
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.ReleaseVisualTag
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.components.StreamImportedBadgeChip
import com.nuvio.tv.ui.components.formatStreamSizeLabel

/** Badges before the release-group chip (data-additions stage 6: "cap 5 + group chip"). */
internal const val MAX_SOURCE_BADGES = 5

/** One badge in a [MarqueeSourceBadges] row: an addon's image badge, or a [MarqueeTechBadge] label. */
internal sealed interface SourceBadgeItem {
    @Immutable
    data class Image(val badge: StreamBadge) : SourceBadgeItem

    @Immutable
    data class Tech(val label: String) : SourceBadgeItem
}

/**
 * A stream's tech badges on one row (Detail source line, Stream screen rows): [sourceBadgeItems]
 * [gap] apart, then the release-group chip (filled), which is always kept. Greedy tail-drop:
 * badges keep their priority order until the width is spent, so overflow sheds the tail instead
 * of clipping. [showSize] adds the size badge (Detail; stream rows show the size beside the row).
 * Emits nothing when there is no badge and no group, so a caller's `spacedBy` adds no gap.
 */
@Composable
internal fun MarqueeSourceBadges(
    facts: ReleaseFacts,
    fusionBadges: List<StreamBadge>,
    gap: Dp,
    modifier: Modifier = Modifier,
    showSize: Boolean = false
) {
    val gbTemplate = stringResource(R.string.unit_size_gb)
    val mbTemplate = stringResource(R.string.unit_size_mb)
    val items = remember(facts, fusionBadges, showSize, gbTemplate, mbTemplate) {
        val sizeLabel: ((Long) -> String)? =
            if (showSize) { bytes -> formatStreamSizeLabel(bytes, gbTemplate, mbTemplate) } else null
        sourceBadgeItems(facts, fusionBadges, sizeLabel)
    }
    val group = facts.releaseGroup
    val hasChip = group.isNotBlank()
    if (items.isEmpty() && !hasChip) return
    Layout(
        modifier = modifier,
        content = {
            for (item in items) {
                when (item) {
                    is SourceBadgeItem.Image -> StreamImportedBadgeChip(badge = item.badge)
                    is SourceBadgeItem.Tech -> MarqueeTechBadge(text = item.label)
                }
            }
            if (hasChip) MarqueeTechBadge(text = group, filled = true)
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val chip = if (hasChip) placeables.last() else null
        val badges = if (hasChip) placeables.dropLast(1) else placeables
        val gapPx = gap.roundToPx()
        val budget = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val kept = mutableListOf<Placeable>()
        var used = chip?.width ?: 0
        for (badge in badges) {
            val candidate = used + badge.width + if (used > 0) gapPx else 0
            if (candidate > budget) break
            kept.add(badge)
            used = candidate
        }
        val rowWidth = used.coerceIn(constraints.minWidth, budget)
        val rowHeight = ((kept + listOfNotNull(chip)).maxOfOrNull { it.height } ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(rowWidth, rowHeight) {
            var x = 0
            for (p in kept) {
                p.placeRelative(x, (rowHeight - p.height) / 2)
                x += p.width + gapPx
            }
            chip?.placeRelative(x, (rowHeight - chip.height) / 2)
        }
    }
}

/**
 * The badges before the group chip, at most [MAX_SOURCE_BADGES]: the addon's own (AIOStreams
 * fusion) badges when the stream carries any (image badges keep their artwork, text badges become
 * tech badges, blank ones are skipped), else [sourceTechBadgeLabels].
 */
internal fun sourceBadgeItems(
    facts: ReleaseFacts,
    fusionBadges: List<StreamBadge>,
    sizeLabel: ((Long) -> String)?
): List<SourceBadgeItem> {
    val fusion = fusionBadges.mapNotNull { badge ->
        when {
            badge.imageURL.isNotBlank() -> SourceBadgeItem.Image(badge)
            badge.name.isNotBlank() -> SourceBadgeItem.Tech(badge.name)
            else -> null
        }
    }
    val items = fusion.ifEmpty { sourceTechBadgeLabels(facts, sizeLabel).map(SourceBadgeItem::Tech) }
    return items.take(MAX_SOURCE_BADGES)
}

/**
 * Parsed tech badges in priority order: resolution ("4K", else "1080P"), HDR format, audio (codec
 * + Atmos + channels as one badge), source (REMUX, WEB-DL, ...), editions, IMAX, then the size
 * when [sizeLabel] is given. Unknown parts are skipped; the caller caps the count.
 */
internal fun sourceTechBadgeLabels(facts: ReleaseFacts, sizeLabel: ((Long) -> String)?): List<String> = buildList {
    resolutionLabel(facts.resolution)?.let(::add)
    hdrLabel(facts.visualTags)?.let(::add)
    audioLabel(facts.audioTags, facts.audioChannels)?.let(::add)
    sourceLabel(facts.quality)?.let(::add)
    addAll(facts.editions)
    if (ReleaseVisualTag.IMAX in facts.visualTags) add(ReleaseVisualTag.IMAX.label)
    if (sizeLabel != null) facts.size?.takeIf { it > 0 }?.let { add(sizeLabel(it)) }
}

private fun resolutionLabel(resolution: ReleaseResolution): String? = when (resolution) {
    ReleaseResolution.UNKNOWN -> null
    ReleaseResolution.P2160 -> "4K"
    else -> resolution.label.uppercase()
}

// The parser adds DV / HDR whenever any Dolby Vision / HDR form is present, so DV plus the
// HDR10 family covers HDR_DV, DV_ONLY and HDR_ONLY. A dual-layer release names its base (P5).
private fun hdrLabel(tags: List<ReleaseVisualTag>): String? = when {
    ReleaseVisualTag.DV in tags -> when {
        ReleaseVisualTag.HDR10_PLUS in tags -> "DV · ${ReleaseVisualTag.HDR10_PLUS.label}"
        ReleaseVisualTag.HDR10 in tags -> "DV · ${ReleaseVisualTag.HDR10.label}"
        else -> "DOLBY VISION"
    }
    ReleaseVisualTag.HDR10_PLUS in tags -> ReleaseVisualTag.HDR10_PLUS.label
    ReleaseVisualTag.HDR10 in tags -> ReleaseVisualTag.HDR10.label
    ReleaseVisualTag.HLG in tags -> ReleaseVisualTag.HLG.label
    ReleaseVisualTag.HDR in tags -> ReleaseVisualTag.HDR.label
    else -> null
}

/**
 * Leading codec: DTS:X first, then lossless-first so a TrueHD + DD compat pairing reads TrueHD.
 */
private val AUDIO_CODEC_ORDER = listOf(
    ReleaseAudioTag.DTS_X, ReleaseAudioTag.TRUEHD, ReleaseAudioTag.DTS_HD_MA, ReleaseAudioTag.DTS_HD,
    ReleaseAudioTag.DTS_ES, ReleaseAudioTag.DTS, ReleaseAudioTag.FLAC, ReleaseAudioTag.DD_PLUS,
    ReleaseAudioTag.DD, ReleaseAudioTag.OPUS, ReleaseAudioTag.AAC
)
private val CHANNEL_ORDER = listOf(
    ReleaseAudioChannel.CH_7_1, ReleaseAudioChannel.CH_6_1, ReleaseAudioChannel.CH_5_1, ReleaseAudioChannel.CH_2_0
)

private fun audioLabel(tags: List<ReleaseAudioTag>, channels: List<ReleaseAudioChannel>): String? {
    val parts = listOfNotNull(
        AUDIO_CODEC_ORDER.firstOrNull { it in tags }?.label,
        ReleaseAudioTag.ATMOS.label.takeIf { ReleaseAudioTag.ATMOS in tags },
        CHANNEL_ORDER.firstOrNull { it in channels }?.label
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")?.uppercase()
}

private fun sourceLabel(quality: ReleaseQuality): String? = when (quality) {
    ReleaseQuality.UNKNOWN -> null
    ReleaseQuality.BLURAY_REMUX -> "REMUX"
    else -> quality.label.uppercase()
}
