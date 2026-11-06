package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        if (installedOrder.isEmpty()) return streams
        val (addonEntries, otherEntries) = streams.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return orderedAddons + otherEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        return stream.getStreamUrl() != null || stream.isTorrent()
    }

    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false
    ): Stream? {
        if (streams.isEmpty()) return null

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
        }
        // The addon allow-list only narrows installed-addon streams; groups under
        // other names are not subject to it.
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            !isAddonStream || selectedAddons.isEmpty() || stream.addonName in selectedAddons
        }
        if (candidateStreams.isEmpty()) return null

        // Binge group matching takes priority over mode — even in MANUAL mode,
        // a persisted binge group should auto-play without showing the picker.
        // A group that names no release (BingeGroups) counts as absent.
        val targetBingeGroup = preferredBingeGroup?.trim()
            ?.takeIf(BingeGroups::namesRelease).orEmpty()
        if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = candidateStreams.firstOrNull { stream ->
                stream.behaviorHints?.bingeGroup == targetBingeGroup && isPlayable(stream)
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
        }

        // When bingeGroupOnly is set (MANUAL mode with only binge-group
        // preference enabled), don't fall back to a non-matching stream —
        // return null so the caller shows the stream picker instead.
        if (bingeGroupOnly) return null

        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams.firstOrNull { isPlayable(it) }
            StreamAutoPlayMode.QUALITY_RANK ->
                StreamQualityRank.rank(candidateStreams.filter { isPlayable(it) }).firstOrNull()
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (userRegex == null) return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                // Exclusion words are extracted from the user's pattern, so they are
                // escaped before joining: a metachar word ("(", "+") must degrade to
                // literal matching, not make the whole alternation uncompilable and
                // silently drop EVERY exclusion. The runCatching stays as a last
                // resort (batch-1 C).
                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    runCatching {
                        Regex("\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b", RegexOption.IGNORE_CASE)
                    }.getOrNull()
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = candidateStreams.filter { stream ->
                    if (!isPlayable(stream)) return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(stream.getStreamUrl().orEmpty())
                        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
                        // The release name often lives only here (upstream `b4a70e6f0` reads it
                        // for media servers; addons set it too).
                        stream.behaviorHints?.filename?.let { append(' ').append(it) }
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                matchingStreams.firstOrNull()
            }

        }
    }
}
