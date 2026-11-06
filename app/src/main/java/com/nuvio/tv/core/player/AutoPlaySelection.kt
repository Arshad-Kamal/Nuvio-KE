package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream

/**
 * The single call site for auto-play selection.
 *
 * StreamScreenViewModel had two calls to selectAutoPlayStream, each passing
 * the arguments assembled from PlayerSettingsDataStore, AddonRepository and
 * BingeGroupCacheDataStore. They differed in
 * exactly one argument. The second sits inside
 * `if (directFlowActive && persistedBingeGroup != null)`, so its
 * `preferBingeGroupInSelection = true` is equivalent to the first's
 * `persistedBingeGroup != null` -- which means the flag can be derived from
 * [Inputs] and only [bingeGroupOnly] needs to be passed.
 *
 * Why this exists rather than a third copy of the argument list: ranking
 * during the prefetch has to predict what the stream screen will pick, and one
 * implementation cannot diverge from itself.
 *
 * No selection logic lives here. This assembles arguments and delegates;
 * StreamAutoPlaySelector and StreamQualityRank are unchanged, so their existing
 * unit tests still cover the behaviour.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
object AutoPlaySelection {

    /**
     * Settings-derived selector inputs, snapshotted once per loadStreams().
     * Every field is already a snapshot in the caller: playerSettings comes
     * from a single .first(), installedAddonOrder from a single
     * getInstalledAddons().first(), and preferredBingeGroup from a single
     * BingeGroupCacheDataStore.get().
     */
    data class Inputs(
        val mode: StreamAutoPlayMode,
        val regexPattern: String,
        val source: StreamAutoPlaySource,
        val installedAddonNames: Set<String>,
        val selectedAddons: Set<String>,
        val preferredBingeGroup: String?,
        /** URLs the runtime guard rejected (RejectedStreamUrls); never auto-selected. */
        val rejectedUrls: Set<String> = emptySet(),
        /**
         * The stream list limit's hidden set (Spec 51 P5, `hiddenStreamKeys` by `limitKey`) over
         * the candidates this rank sees; never auto-selected. No default: every caller computes
         * it, so no path through [select] can skip the limit.
         */
        val hiddenKeys: Set<String>
    ) {
        companion object {
            /**
             * Inputs straight from the saved settings: the stream screen and the
             * prefetch ranking, which must predict the same winner. The player's
             * next-episode / source-switch paths build theirs field by field
             * because they apply per-play overrides.
             */
            fun fromSettings(
                settings: PlayerSettings,
                installedAddonOrder: List<String>,
                preferredBingeGroup: String?,
                rejectedUrls: Set<String>,
                hiddenKeys: Set<String>
            ): Inputs = Inputs(
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                source = settings.streamAutoPlaySource,
                installedAddonNames = installedAddonOrder.toSet(),
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                preferredBingeGroup = preferredBingeGroup,
                rejectedUrls = rejectedUrls,
                hiddenKeys = hiddenKeys
            )
        }
    }

    /**
     * The binge group a fresh play prefers: the group cached for [contentId]
     * (via [cachedGroup], BingeGroupCacheDataStore.get), only when both Prefer
     * Binge Group and Reuse Binge Group are on. The cache is not read otherwise.
     */
    suspend fun persistedBingeGroup(
        settings: PlayerSettings,
        contentId: String?,
        cachedGroup: suspend (String) -> String?
    ): String? {
        if (!settings.streamAutoPlayPreferBingeGroupForNextEpisode ||
            !settings.streamAutoPlayReuseBingeGroup
        ) return null
        return contentId?.let { cachedGroup(it) }
    }

    /**
     * Selects the stream to auto-play, or null for the picker.
     *
     * @param bingeGroupOnly when true, a binge-group miss returns null instead
     *   of falling back to the configured mode -- the eager pre-timeout check.
     */
    fun select(
        streams: List<Stream>,
        inputs: Inputs,
        bingeGroupOnly: Boolean = false
    ): Stream? = StreamAutoPlaySelector.selectAutoPlayStream(
        streams = if (inputs.rejectedUrls.isEmpty() && inputs.hiddenKeys.isEmpty()) {
            streams
        } else {
            streams.filterNot { it.getStreamUrl() in inputs.rejectedUrls || it.limitKey() in inputs.hiddenKeys }
        },
        mode = inputs.mode,
        regexPattern = inputs.regexPattern,
        source = inputs.source,
        installedAddonNames = inputs.installedAddonNames,
        selectedAddons = inputs.selectedAddons,
        preferredBingeGroup = inputs.preferredBingeGroup,
        preferBingeGroupInSelection = inputs.preferredBingeGroup != null,
        bingeGroupOnly = bingeGroupOnly
    )
}
