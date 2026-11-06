package com.nuvio.tv.domain.model

/** Every theme, White first. Supporter gating is gone (Spec 31 S6): all themes unlock locally. */
val selectableAppThemes: List<AppTheme> =
    listOf(AppTheme.WHITE) + AppTheme.entries.filterNot { it == AppTheme.WHITE }

/** No stored (or an unknown stored) theme reads White. */
fun resolveAppTheme(selectedTheme: AppTheme?): AppTheme = selectedTheme ?: AppTheme.WHITE
