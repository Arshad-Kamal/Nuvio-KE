package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import java.util.regex.Pattern
import java.util.regex.Matcher
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

const val STREAM_BADGE_IMPORT_LIMIT = 3

@Serializable
data class StreamBadgeRules(
    val imports: List<StreamBadgeImport> = emptyList()
) {
    val hasImport: Boolean
        get() = imports.isNotEmpty()

    val activeImport: StreamBadgeImport?
        get() = imports.firstOrNull { it.isActive } ?: imports.firstOrNull()

    val enabledFilterCount: Int
        get() = activeImport?.enabledFilterCount ?: 0

    fun normalized(): StreamBadgeRules {
        val normalizedImports = mutableListOf<StreamBadgeImport>()
        imports.forEach { import ->
            val normalizedUrl = import.sourceUrl.trim()
            if (normalizedUrl.isBlank() || import.filters.isEmpty()) return@forEach
            val normalizedImport = import.copy(sourceUrl = normalizedUrl)
            val existingIndex = normalizedImports.indexOfFirst { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }
            if (existingIndex >= 0) {
                normalizedImports[existingIndex] = normalizedImport
            } else if (normalizedImports.size < STREAM_BADGE_IMPORT_LIMIT) {
                normalizedImports += normalizedImport
            }
        }
        if (normalizedImports.isEmpty()) return copy(imports = emptyList())
        val activeIndex = normalizedImports.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
        return copy(
            imports = normalizedImports.mapIndexed { index, import ->
                import.copy(isActive = index == activeIndex)
            }
        )
    }

    fun upsert(import: StreamBadgeImport, activate: Boolean = true): StreamBadgeRules {
        val normalizedUrl = import.sourceUrl.trim()
        if (normalizedUrl.isBlank()) return normalized()
        val normalizedImport = import.copy(sourceUrl = normalizedUrl, isActive = activate)
        val replaced = imports.map { existing ->
            if (existing.sourceUrl.equals(normalizedUrl, ignoreCase = true)) normalizedImport else existing
        }
        val nextImports = if (imports.any { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }) {
            replaced
        } else {
            imports + normalizedImport
        }
        val activeImports = if (activate) {
            nextImports.map { existing ->
                existing.copy(isActive = existing.sourceUrl.equals(normalizedUrl, ignoreCase = true))
            }
        } else {
            nextImports
        }
        return copy(imports = activeImports).normalized()
    }

    fun setActiveSource(sourceUrl: String): StreamBadgeRules {
        val normalizedUrl = sourceUrl.trim()
        if (normalizedUrl.isBlank() || imports.none { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }) {
            return normalized()
        }
        return copy(
            imports = imports.map { import ->
                import.copy(isActive = import.sourceUrl.equals(normalizedUrl, ignoreCase = true))
            }
        ).normalized()
    }

    fun removeSource(sourceUrl: String): StreamBadgeRules =
        copy(imports = imports.filterNot { it.sourceUrl.equals(sourceUrl.trim(), ignoreCase = true) }).normalized()

    override fun toString(): String =
        "StreamBadgeRules(imports=${imports.size}, activeFilters=$enabledFilterCount, groups=${imports.sumOf { it.groups.size }})"
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class StreamBadgeImport(
    val sourceUrl: String = "",
    val filters: List<StreamBadgeFilter> = emptyList(),
    val groups: List<StreamBadgeGroup> = emptyList(),
    @EncodeDefault
    @SerialName("isActive")
    @JsonNames("active")
    val isActive: Boolean = true
) {
    val enabledFilterCount: Int
        get() = filters.count { it.isEnabled }

    override fun toString(): String =
        "StreamBadgeImport(sourceUrl=$sourceUrl, filters=${filters.size}, groups=${groups.size}, isActive=$isActive)"
}

@Serializable
data class StreamBadgeFilter(
    val id: String = "",
    val groupId: String = "",
    val name: String = "",
    val pattern: String = "",
    val imageURL: String = "",
    val isEnabled: Boolean = true,
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = ""
)

@Serializable
data class StreamBadgeGroup(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val isExpanded: Boolean = true
)

sealed interface StreamBadgeImportResult {
    data class Success(val rules: StreamBadgeRules) : StreamBadgeImportResult
    data class Error(val message: String) : StreamBadgeImportResult
}

internal object StreamBadgeRulesParser {
    /**
     * Size caps on owner-imported packs, whose regexes run against every stream row. The
     * length cap bounds pattern size, not backtracking; matching cannot be interrupted once
     * started (see StreamBadgePresentation), so these are not a ReDoS guard.
     */
    const val MAX_BADGE_PATTERN_LENGTH = 200
    const val MAX_BADGE_FILTERS_PER_IMPORT = 300

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(sourceUrl: String, payload: String): StreamBadgeImport {
        val decoded = try {
            json.decodeFromString<StreamBadgePayload>(payload)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        }

        val filters = decoded.filters.mapNotNull { filter ->
            val name = filter.name.orEmpty().trim()
            val pattern = filter.pattern.orEmpty().trim()
            if (name.isBlank() || pattern.isBlank()) {
                return@mapNotNull null
            }
            if (pattern.length > MAX_BADGE_PATTERN_LENGTH || name.length > MAX_BADGE_PATTERN_LENGTH) {
                throw IllegalArgumentException(
                    "Badge pattern/name exceeds $MAX_BADGE_PATTERN_LENGTH characters: ${name.take(40)}"
                )
            }

            StreamBadgeFilter(
                id = filter.id.orEmpty(),
                groupId = filter.groupId.orEmpty(),
                name = name,
                pattern = pattern,
                imageURL = filter.imageURL.orEmpty(),
                isEnabled = filter.isEnabled ?: true,
                tagColor = filter.tagColor.orEmpty(),
                tagStyle = filter.tagStyle.orEmpty(),
                textColor = filter.textColor.orEmpty(),
                borderColor = filter.borderColor.orEmpty()
            )
        }

        if (filters.isEmpty()) {
            throw IllegalArgumentException("Badge import did not contain any usable filters.")
        }
        if (filters.size > MAX_BADGE_FILTERS_PER_IMPORT) {
            throw IllegalArgumentException(
                "Badge import exceeds $MAX_BADGE_FILTERS_PER_IMPORT filters (${filters.size})."
            )
        }

        val groups = decoded.groups.map { group ->
            StreamBadgeGroup(
                id = group.id.orEmpty(),
                name = group.name.orEmpty(),
                color = group.color.orEmpty(),
                isExpanded = group.isExpanded ?: true
            )
        }

        return StreamBadgeImport(
            sourceUrl = sourceUrl.trim(),
            filters = filters,
            groups = groups
        )
    }
}

internal object StreamBadgeMatcher {
    fun compile(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> {
        if (!rules.hasImport) return emptyList()
        return rules.normalized().imports.filter { it.isActive }.flatMap { import ->
            import.filters.mapNotNull { filter ->
                if (!filter.isEnabled || filter.name.isBlank() || filter.pattern.isBlank()) {
                    return@mapNotNull null
                }
                val pattern = runCatching {
                    Pattern.compile(filter.pattern)
                }.getOrNull() ?: return@mapNotNull null
                CompiledStreamBadgeFilter(
                    name = filter.name,
                    badge = StreamBadge(
                        name = filter.name,
                        imageURL = filter.imageURL,
                        tagColor = filter.tagColor,
                        tagStyle = filter.tagStyle,
                        textColor = filter.textColor,
                        borderColor = filter.borderColor
                    ),
                    pattern = pattern,
                    // Extract a short literal hint from the pattern for fast pre-screening.
                    // If the candidate doesn't contain this literal (case-insensitive),
                    // we skip the expensive regex entirely.
                    literalHint = extractLiteralHint(filter.pattern)
                )
            }
        }
    }

    private fun extractLiteralHint(pattern: String): String? {
        // For simple patterns without regex metacharacters, use the whole thing.
        val metaChars = "\\[](){}*+?|^$."
        if (pattern.length >= 2 && pattern.none { it in metaChars }) {
            return pattern.lowercase()
        }
        if ('|' in pattern) return null
        val stripped = pattern
            .replace("\\b", "")
            .replace("(?i)", "")
            .replace("(?:", "")
            .replace("(", "")
            .replace(")", "")
        if (stripped.length >= 2 && stripped.none { it in metaChars }) {
            return stripped.lowercase()
        }
        return null
    }

    /**
     * Badges of the [filters] matching [stream], deduped by [dedupeKey] in filter order. Each
     * match resolves to its canonical instance in the read-only [pool] when present.
     */
    fun matchedBadges(
        stream: Stream,
        filters: List<CompiledStreamBadgeFilter>,
        pool: Map<String, StreamBadge> = emptyMap()
    ): List<StreamBadge> {
        if (filters.isEmpty()) return emptyList()
        val candidates = badgeMatchCandidates(stream)
        if (candidates.isEmpty()) return emptyList()

        // Fast path: most rows match at most one filter, so hold the first match alone and
        // allocate a map only on a second distinct key.
        var first: StreamBadge? = null
        var firstKey = ""
        var matched: LinkedHashMap<String, StreamBadge>? = null

        for (filter in filters) {
            if (!matchesAnyCandidateReusable(filter, candidates)) continue
            val key = filter.badge.dedupeKey()
            val badge = pool[key] ?: filter.badge
            val map = matched
            val head = first
            when {
                map != null -> map.putIfAbsent(key, badge)
                head == null -> {
                    first = badge
                    firstKey = key
                }
                key != firstKey -> matched = LinkedHashMap<String, StreamBadge>(4).apply {
                    put(firstKey, head)
                    put(key, badge)
                }
            }
        }

        val map = matched
        val head = first
        return when {
            map != null -> map.values.toList()
            head != null -> listOf(head)
            else -> emptyList()
        }
    }

    private fun matchesAnyCandidateReusable(
        filter: CompiledStreamBadgeFilter,
        candidates: List<String>
    ): Boolean {
        val hint = filter.literalHint
        val matcher = filter.threadLocalMatcher.get() ?: return false
        for (candidate in candidates) {
            // Fast pre-screen: skip regex if the literal hint isn't present.
            if (hint != null && !containsIgnoreCase(candidate, hint)) continue
            matcher.reset(candidate)
            if (matcher.find()) return true
        }
        return false
    }

    /**
     * Case-insensitive contains check without allocating a lowercase copy.
     * Scans for [hint] (already lowercase) within [text] using char-by-char comparison.
     */
    private fun containsIgnoreCase(text: String, hint: String): Boolean {
        val hintLen = hint.length
        val textLen = text.length
        if (hintLen > textLen) return false
        val limit = textLen - hintLen
        outer@ for (i in 0..limit) {
            for (j in 0 until hintLen) {
                val tc = text[i + j].lowercaseChar()
                if (tc != hint[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun badgeMatchCandidates(stream: Stream): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val candidates = listOfNotNull(
            raw?.filename,
            resolve?.filename,
            stream.behaviorHints?.filename,
            raw?.torrentName,
            resolve?.torrentName,
            stream.name,
            stream.title,
            stream.description,
            parsed?.rawTitle,
            parsed?.parsedTitle,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.edition,
            parsed?.audio?.joinToString(" "),
            parsed?.channels?.joinToString(" "),
            parsed?.hdr?.joinToString(" "),
            parsed?.group,
            stream.addonName
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return if (candidates.size <= 1) {
            candidates
        } else {
            candidates + candidates.joinToString(" ")
        }
    }
}

class CompiledStreamBadgeFilter(
    val name: String,
    val badge: StreamBadge,
    val pattern: Pattern,
    val literalHint: String?
) {
    /**
     * Per-thread reusable Matcher. [Matcher.reset] is called before each use
     * to rebind to a new input without allocating a new Matcher object.
     * ThreadLocal ensures thread-safety when matching runs on Dispatchers.Default
     * (which may use multiple threads from the coroutine pool).
     */
    internal val threadLocalMatcher: ThreadLocal<Matcher> = ThreadLocal.withInitial {
        pattern.matcher("")
    }
}

internal fun StreamBadge.dedupeKey(): String =
    imageURL.takeIf { it.isNotBlank() } ?: name

@Serializable
private data class StreamBadgePayload(
    val filters: List<StreamBadgeFilterPayload> = emptyList(),
    val groups: List<StreamBadgeGroupPayload> = emptyList()
)

@Serializable
private data class StreamBadgeFilterPayload(
    val id: String? = null,
    val groupId: String? = null,
    val name: String? = null,
    val pattern: String? = null,
    val imageURL: String? = null,
    val isEnabled: Boolean? = null,
    val tagColor: String? = null,
    val tagStyle: String? = null,
    val textColor: String? = null,
    val borderColor: String? = null,
    val type: String? = null
)

@Serializable
private data class StreamBadgeGroupPayload(
    val id: String? = null,
    val name: String? = null,
    val color: String? = null,
    val isExpanded: Boolean? = null
)
