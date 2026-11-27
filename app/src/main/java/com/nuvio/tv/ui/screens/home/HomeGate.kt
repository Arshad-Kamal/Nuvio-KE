package com.nuvio.tv.ui.screens.home

/**
 * Spec 145: whether Home's first-paint gate may release before its timeout. It waits until
 * installed addons have been seen ([addonsSeen]), loading is done, the CW pipeline has emitted
 * once and the presentation is built. Then:
 * - home catalogs expected ([homeCatalogsExpected]): one catalog row must hold items, so Home
 *   never flashes without its catalog rows;
 * - none expected (Continue Watching, Picked for you and collections only, F18): Home's rows
 *   must reflect the collections' first load ([collectionsLoaded]; D3), so Home never shows
 *   the "no catalog addons" message while its collections are still being read.
 *
 * Input contracts: [HomeUiState.homeCatalogsExpected], [HomeUiState.collectionsLoaded].
 * A profile with no addons never sets [addonsSeen] and is released by the timeout.
 */
internal fun homeGateReady(
    addonsSeen: Boolean,
    isLoading: Boolean,
    initialCwResolved: Boolean,
    modernPresentationReady: Boolean,
    hasCatalogContent: Boolean,
    homeCatalogsExpected: Boolean,
    collectionsLoaded: Boolean,
): Boolean =
    addonsSeen &&
        !isLoading &&
        initialCwResolved &&
        modernPresentationReady &&
        (hasCatalogContent || (!homeCatalogsExpected && collectionsLoaded))
