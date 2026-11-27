@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.core.player

import androidx.media3.extractor.Extractor

/**
 * An extractor wrapper that forwards everything to [wrappedExtractor] and can be rebuilt around a
 * different inner extractor. Lets the libass Matroska swap replace the innermost extractor while
 * keeping the wrappers around it (AutoSync's embedded subtitle observer, Spec 108).
 * Upstream 747ce832b.
 */
internal interface RewrappableExtractor : Extractor {
    val wrappedExtractor: Extractor

    fun rewrap(inner: Extractor): Extractor

    // The libass swap matches on the underlying extractor and replaces through this chain, so the two agree.
    override fun getUnderlyingImplementation(): Extractor = wrappedExtractor.underlyingImplementation
}

/**
 * This extractor's wrappers rebuilt around [replacement] in place of the innermost extractor.
 * A wrapper that is not [RewrappableExtractor] ends the chain and is itself replaced, so every
 * wrapper that must survive the libass swap implements it (none in KE wraps Matroska otherwise).
 */
internal fun Extractor.replaceInnermostExtractor(replacement: Extractor): Extractor =
    if (this is RewrappableExtractor) {
        rewrap(wrappedExtractor.replaceInnermostExtractor(replacement))
    } else {
        replacement
    }
