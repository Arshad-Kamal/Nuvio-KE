package com.nuvio.tv.domain.model

// Card shape used by the Continue Watching row, chosen independently of the catalog poster orientation.
// WIDE retired in U1c (Marquee Home): a stored "WIDE" decodes to CARD through the valueOf fallback
// in LayoutPreferenceDataStore.continueWatchingCardStyle and is never rewritten.
enum class ContinueWatchingCardStyle {
    CARD,
    POSTER
}
