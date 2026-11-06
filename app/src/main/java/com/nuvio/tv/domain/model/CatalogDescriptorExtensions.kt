package com.nuvio.tv.domain.model

private const val DEFAULT_SKIP_STEP = 100

/**
 * Whether this catalog declares support for an extra argument (e.g. "skip", "search").
 *
 * Stremio manifests declare extras in either form:
 *  - long form:  extra: [{ "name": "skip" }, ...]        (parsed into [extra])
 *  - short form: extraSupported: ["skip", ...] / extraRequired: [...]
 *
 * AddonMapper.parseCatalogExtras only populates [extra] from the long form, so
 * short-form catalogs previously reported false here. That silently disabled
 * pagination (hasMore never set) and hid the catalog from search.
 */
fun CatalogDescriptor.supportsExtra(name: String): Boolean {
    if (extra.any { it.name.equals(name, ignoreCase = true) }) return true
    if (extraSupported.any { it.equals(name, ignoreCase = true) }) return true
    if (extraRequired.any { it.equals(name, ignoreCase = true) }) return true
    return false
}

/**
 * The catalog demands a "search" argument (a required search extra): it only
 * answers searches, so home/browse rows must hide it. G6 consolidation home
 * (2026-08-30): previously three identical copies under the name
 * `isSearchOnlyCatalog` (two private — HomeCatalogOrdering,
 * AddonManagerScreen; one internal — HomeViewModelCatalogUtils). Long-form
 * `extra` only: together with [shouldShowOnHome] this defines the cloud-sync
 * home-catalog list, kept unchanged on purpose, so a short-form
 * "extraRequired": ["search"] catalog still passes here. Browse surfaces (home
 * rows, hero, Discover) additionally hide such catalogs via
 * [hasUnsatisfiableRequiredExtra].
 *
 * NOT the same question as [isSearchable]: this asks "must we hide it from
 * browsing", that asks "can we usefully query it" — the two predicates
 * coexisted under near-identical private names before this split.
 */
fun CatalogDescriptor.requiresSearchExtra(): Boolean =
    extra.any { extra ->
        extra.name.equals("search", ignoreCase = true) && extra.isRequired
    }

/**
 * A catalog is only searchable if a search is all it needs. One that also
 * requires something we cannot supply, a mandatory genre for instance, answers
 * with an error or nothing at all, so querying it just costs a request per
 * keystroke and leaves a row that never fills in. (Formerly SearchViewModel's
 * private isSearchable.)
 */
fun CatalogDescriptor.isSearchable(): Boolean =
    supportsExtra("search") &&
        extra.none { property ->
            property.isRequired && !property.name.equals("search", ignoreCase = true)
        } &&
        extraRequired.none { name -> !name.equals("search", ignoreCase = true) }

/**
 * Home-row visibility: hidden when a search extra is required (search-only
 * catalog), otherwise honors an explicit showInHome flag when the manifest
 * set one. G6 consolidation (2026-08-30): formerly twin copies —
 * HomeViewModelCatalogUtils `shouldShowOnHome` (internal) and
 * HomeCatalogSyncSupport `shouldShowOnHomeForSync` (private), identical
 * bodies — now one domain predicate. With [requiresSearchExtra] it defines the
 * cloud-sync home-catalog list (kept unchanged on purpose); browse surfaces
 * (home rows, hero, Discover) additionally hide catalogs via
 * [hasUnsatisfiableRequiredExtra], so the two lists can legitimately differ.
 */
fun CatalogDescriptor.shouldShowOnHome(): Boolean {
    if (requiresSearchExtra()) return false
    return !hasExplicitShowInHome || showInHome
}

private fun isSkipExtra(name: String): Boolean = name.equals("skip", ignoreCase = true)

private fun CatalogExtra.hasFallbackValue(): Boolean =
    !options.isNullOrEmpty() || !defaultValue.isNullOrBlank()

/**
 * The one requiredness rule for a long-form extra: flagged `isRequired`, or
 * named (case-insensitive) in the short-form `extraRequired` list. Manifests
 * mix the forms, e.g. extraRequired ["genre"] plus a long-form genre entry
 * carrying the options but isRequired=false.
 */
private fun CatalogDescriptor.isExtraRequired(property: CatalogExtra): Boolean =
    property.isRequired || extraRequired.any { it.equals(property.name, ignoreCase = true) }

/**
 * A browse fetch (home rows, hero, Discover) cannot satisfy this catalog: it
 * requires an extra other than `skip` with neither options nor a default to
 * send (e.g. AIOMetadata's calendar catalog requires `calendarVideosIds`;
 * search-only catalogs count too). Such a catalog answers with an error or
 * nothing, so browse surfaces hide it and never fetch it. Reads both manifest
 * forms (long `extra` and short `extraRequired`). UI-only on purpose: the
 * cloud-sync home-catalog list (core/sync, via [shouldShowOnHome]) is not
 * filtered by it, so the synced list stays unchanged.
 */
fun CatalogDescriptor.hasUnsatisfiableRequiredExtra(): Boolean =
    extra.any { property ->
        isExtraRequired(property) && !isSkipExtra(property.name) && !property.hasFallbackValue()
    } ||
        // A short-form required name with no long-form entry has nothing to send.
        extraRequired.any { name ->
            !isSkipExtra(name) && extra.none { it.name.equals(name, ignoreCase = true) }
        }

/**
 * A literal "None" option is an addon's "no filter" choice: it is never sent
 * as a value (no `genre=None`); the extra is omitted instead.
 */
fun isNoFilterExtraValue(value: String): Boolean =
    value.trim().equals("None", ignoreCase = true)

/**
 * Values for a browse fetch the user made no choice for. The Stremio spec
 * says a required extra with options must always be passed, so each required
 * long-form extra (other than skip/search; requiredness per [isExtraRequired],
 * the same rule [hasUnsatisfiableRequiredExtra] uses) gets its `default`, else
 * its first option. A "None" value ([isNoFilterExtraValue]) means no filter and
 * is left out.
 */
fun CatalogDescriptor.requiredExtraDefaults(): Map<String, String> = buildMap {
    extra.forEach { property ->
        if (!isExtraRequired(property) || isSkipExtra(property.name)) return@forEach
        if (property.name.equals("search", ignoreCase = true)) return@forEach
        val value = property.defaultValue?.takeIf { it.isNotBlank() }
            ?: property.options?.firstOrNull { it.isNotBlank() }
            ?: return@forEach
        if (isNoFilterExtraValue(value)) return@forEach
        if (property.name !in this) put(property.name, value)
    }
}

fun CatalogDescriptor.skipStep(defaultStep: Int = DEFAULT_SKIP_STEP): Int {
    if (pageSize != null && pageSize > 0) return pageSize
    // Some manifests omit pageSize but enumerate the skip extra's own options
    // (e.g. ["0", "50", "100"]). The smallest positive gap between consecutive
    // options is the real page size; without it we assume DEFAULT_SKIP_STEP and
    // request skips the addon never serves.
    val skipOptions = extra
        .firstOrNull { it.name.equals("skip", ignoreCase = true) }
        ?.options
        .orEmpty()
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .distinct()
        .sorted()
    if (skipOptions.size >= 2) {
        val inferred = skipOptions
            .zipWithNext()
            .mapNotNull { (lower, upper) -> (upper - lower).takeIf { gap -> gap > 0 } }
            .minOrNull()
        if (inferred != null && inferred > 0) return inferred
    }
    return defaultStep
}
