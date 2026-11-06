package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.hasUnsatisfiableRequiredExtra
import com.nuvio.tv.domain.model.requiredExtraDefaults
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra

/** Discover's catalogs from the enabled addons: every catalog the app can request (no unsatisfiable required extra). */
internal fun List<Addon>.toDiscoverCatalogs(): List<DiscoverCatalog> = flatMap { addon ->
    addon.catalogs
        .filter { catalog -> !catalog.hasUnsatisfiableRequiredExtra() }
        .map { catalog ->
            val genres = catalog.extra
                .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                ?.options
                .orEmpty()
            DiscoverCatalog(
                key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                addonId = addon.id,
                addonName = addon.displayName,
                addonBaseUrl = addon.baseUrl,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                genres = genres,
                supportsSkip = catalog.supportsExtra("skip"),
                skipStep = catalog.skipStep(),
                defaultExtraArgs = catalog.requiredExtraDefaults()
            )
        }
}

/** A catalog and the genre option as it lists it (its own spelling, what the request sends). */
internal data class DiscoverGenreTarget(val catalog: DiscoverCatalog, val genre: String)

/** The first [type] catalog whose genre options include [genre] (case-insensitive), or null. */
internal fun List<DiscoverCatalog>.genreTarget(type: String, genre: String): DiscoverGenreTarget? {
    val wanted = genre.trim()
    return asSequence()
        .filter { it.type.equals(type, ignoreCase = true) }
        .firstNotNullOfOrNull { catalog ->
            catalog.genres.firstOrNull { it.trim().equals(wanted, ignoreCase = true) }
                ?.let { DiscoverGenreTarget(catalog, it) }
        }
}
