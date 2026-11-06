package com.nuvio.tv.ui.screens.home

/**
 * Backdrop hand-off from a clicked card to the Detail screen, which shows it for the whole visit.
 * Click-time writers name the title ([updateForTitle]) where the route checks it
 * ([consumeForTitle]); the Home hero shows a debounced item, so its art is never the hand-off.
 */
object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop URL that was actually displayed — survives navigation for seamless back transitions. */
    @Volatile
    var lastDisplayedUrl: String? = null

    /** The (id, type) whose art [currentHeroBackdropUrl] is; null for an untitled [update]. */
    @Volatile
    private var selectedTitle: Pair<String, String>? = null

    fun update(url: String?) {
        selectedTitle = null
        currentHeroBackdropUrl = url
        if (!url.isNullOrBlank()) {
            lastDisplayedUrl = url
        }
    }

    fun updateForTitle(id: String, type: String, url: String?) {
        update(url)
        selectedTitle = id to type
    }

    /** The hand-off URL only when [updateForTitle] wrote it for this title; always clears. */
    fun consumeForTitle(id: String, type: String): String? {
        val matches = selectedTitle == (id to type)
        val url = consumeAndClear()
        return url.takeIf { matches }
    }

    fun consumeAndClear(): String? {
        val url = currentHeroBackdropUrl
        currentHeroBackdropUrl = null
        selectedTitle = null
        return url
    }
}
