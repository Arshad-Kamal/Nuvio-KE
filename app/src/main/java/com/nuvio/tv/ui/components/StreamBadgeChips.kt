package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.nuvio.tv.domain.model.StreamBadge
import okio.Path.Companion.toOkioPath
import kotlin.math.round

/**
 * Shared badge ImageRequest cache. Badges with the same URL and target decode
 * dimensions share a single [ImageRequest] instance across all composables in
 * the stream list. This avoids per-item allocations of builder objects and
 * intermediate strings that pressure the GC on low-memory TV devices.
 *
 * The cache is keyed by the Coil memory-cache key (url + dimensions) and is
 * bounded by the number of unique badge images (typically 10-30 in practice).
 * Entries are never evicted — they live for the process lifetime which is fine
 * because they hold no bitmaps, only request metadata.
 */
private val badgeImageRequestCache = HashMap<String, ImageRequest>(32)

private var badgeImageLoader: ImageLoader? = null

private fun getBadgeImageLoader(context: android.content.Context): ImageLoader {
    badgeImageLoader?.let { return it }
    val loader = ImageLoader.Builder(context.applicationContext)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context.applicationContext, 0.05)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.applicationContext.cacheDir.resolve("badge_cache").toOkioPath())
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .precision(Precision.INEXACT)
        .crossfade(false)
        .allowHardware(true)
        .allowRgb565(true)
        .build()
    badgeImageLoader = loader
    return loader
}

/** Shared shape instance — all badge chips use the same corner radius. */
private val BadgeChipShape = RoundedCornerShape(6.dp)

/** "58.2 GB" (one decimal) from 1 GiB up, else whole "MB"; templates are `unit_size_gb/mb`. */
internal fun formatStreamSizeLabel(bytes: Long, gbTemplate: String, mbTemplate: String): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        val roundedGiB = round(gib * 10.0) / 10.0
        gbTemplate.format(roundedGiB.toString())
    } else {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        mbTemplate.format(round(mib).toInt().toString())
    }
}

@Composable
internal fun StreamImportedBadgeChip(badge: StreamBadge) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val backgroundColor = remember(badge.tagColor, badge.tagStyle) {
        badge.tagColor.toBadgeColorOrNull()
            ?.takeIf { badge.tagStyle.equals("filled", ignoreCase = true) }
    }
    val outlineColor = remember(badge.borderColor) {
        badge.borderColor.toBadgeColorOrNull()
    }
    // Pre-upscale: decode at 2× target pixels so the hardware compositor
    // has enough pixel data for smooth edges inside Card RenderNodes.
    val decodeHeight = remember(density) {
        with(density) { NuvioTheme.spacing.lg.roundToPx() } * 2
    }
    // Use a wide max-width to let Coil decode at aspect ratio constrained by height.
    val decodeWidth = remember(density) {
        with(density) { 92.dp.roundToPx() } * 2
    }
    // Reuse ImageRequest instances across composables via a shared HashMap.
    // This avoids creating a new Builder + intermediate strings per badge per
    // recomposition and ensures Coil's internal equality checks hit the same
    // object reference — preventing redundant decode dispatches.
    val cacheKey = "${badge.imageURL}_${decodeWidth}x${decodeHeight}"
    val imageRequest = remember(cacheKey) {
        badgeImageRequestCache.getOrPut(cacheKey) {
            ImageRequest.Builder(context)
                .data(badge.imageURL)
                .size(width = decodeWidth, height = decodeHeight)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(badge.imageURL)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }
    val chipModifier = Modifier
        .height(20.dp)
        .then(if (backgroundColor != null) Modifier.background(backgroundColor, BadgeChipShape) else Modifier)
        .then(if (outlineColor != null) Modifier.border(NuvioTheme.spacing.hairline, outlineColor, BadgeChipShape) else Modifier)

    Box(
        modifier = chipModifier
            .padding(horizontal = 3.dp, vertical = NuvioTheme.spacing.xxs),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            imageLoader = getBadgeImageLoader(context),
            contentDescription = badge.name,
            modifier = Modifier
                .height(NuvioTheme.spacing.lg)
                .widthIn(min = 34.dp, max = 92.dp)
                .clip(BadgeChipShape),
            contentScale = ContentScale.Fit
        )
    }
}

private fun String.toBadgeColorOrNull(): Color? {
    val hex = trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)?.let { Color(it) }
}
