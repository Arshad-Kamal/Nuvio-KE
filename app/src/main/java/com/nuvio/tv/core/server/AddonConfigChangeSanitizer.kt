package com.nuvio.tv.core.server

import com.nuvio.tv.core.util.withAcceptedAddonScheme

/** Max addon URLs accepted from one web-config proposal (CWE-770). */
internal const val MAX_PROPOSED_ADDON_URLS = 100

/**
 * Boundary filter for web-proposed addon URLs (CWE-918): keeps only schemes the
 * TV-side install accepts ([withAcceptedAddonScheme]), plus [installedUrls]
 * verbatim so echoing the served list can never drop an installed addon on
 * confirm. Capped at [MAX_PROPOSED_ADDON_URLS].
 */
internal fun sanitizeProposedAddonUrls(urls: List<String>, installedUrls: Set<String>): List<String> =
    urls.asSequence()
        .mapNotNull { url -> withAcceptedAddonScheme(url) ?: url.takeIf { it in installedUrls } }
        .distinct()
        .take(MAX_PROPOSED_ADDON_URLS)
        .toList()

internal fun sanitizePendingAddonChange(
    mode: AddonWebConfigMode,
    proposedChange: PendingAddonChange,
    currentState: PageState
): PendingAddonChange {
    if (
        mode.allowAddonManagement &&
        mode.allowCatalogManagement &&
        mode.allowCollectionManagement
    ) {
        return proposedChange
    }

    return proposedChange.copy(
        proposedUrls = if (mode.allowAddonManagement) {
            proposedChange.proposedUrls
        } else {
            currentState.addons.map { it.url }
        },
        proposedCatalogOrderKeys = if (mode.allowCatalogManagement) {
            proposedChange.proposedCatalogOrderKeys
        } else {
            currentState.catalogs.map { it.key }
        },
        proposedDisabledCatalogKeys = if (mode.allowCatalogManagement) {
            proposedChange.proposedDisabledCatalogKeys
        } else {
            currentState.catalogs
                .filter { it.isDisabled }
                .map { it.disableKey }
        },
        proposedCollectionsJson = if (mode.allowCollectionManagement) {
            proposedChange.proposedCollectionsJson
        } else {
            null
        },
        proposedDisabledCollectionKeys = if (mode.allowCollectionManagement) {
            proposedChange.proposedDisabledCollectionKeys
        } else {
            currentState.disabledCollectionKeys
        },
        // Follow-addons-order is a catalog-ordering setting; null = no change.
        proposedFollowAddonsOrder = if (mode.allowCatalogManagement) {
            proposedChange.proposedFollowAddonsOrder
        } else {
            null
        }
    )
}
