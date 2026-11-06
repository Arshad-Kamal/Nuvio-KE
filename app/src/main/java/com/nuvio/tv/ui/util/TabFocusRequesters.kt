package com.nuvio.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.nuvio.tv.TopBarTab

internal fun <T> syncRouteMap(
    existing: MutableMap<String, T>,
    routes: List<String>,
    create: () -> T
) {
    val validRoutes = routes.toSet()
    existing.keys.retainAll(validRoutes)
    routes.forEach { route ->
        existing.getOrPut(route, create)
    }
}

/** One requester per top-bar tab, identity kept per route as tabs come and go (Discover). */
@Composable
internal fun rememberTabFocusRequesters(
    tabs: List<TopBarTab>
): Map<String, FocusRequester> {
    val focusRequesters = remember { linkedMapOf<String, FocusRequester>() }
    syncRouteMap(
        existing = focusRequesters,
        routes = tabs.map(TopBarTab::route),
        create = ::FocusRequester
    )
    return focusRequesters
}
