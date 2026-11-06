package com.nuvio.tv.core.streams

data class StreamBadgeSettings(
    val rules: StreamBadgeRules = StreamBadgeRules(),
    val showFileSizeBadges: Boolean = true,
    /** Retired (U5m) with [badgePlacement]: stored values still read, nothing writes them. */
    val showAddonLogo: Boolean = true,
    val badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    /** Stream screen rows show the addon's own name + description instead of Nuvio's badges. */
    val useAddonFormatting: Boolean = false
)

enum class StreamBadgePlacement {
    TOP,
    BOTTOM
}
