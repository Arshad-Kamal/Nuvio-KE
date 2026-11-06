package com.nuvio.tv.domain.model

enum class FolderViewMode {
    TABBED_GRID,
    ROWS,
    FOLLOW_LAYOUT;

    /**
     * The mode a folder shows in (Spec 28 Q1: two modes). Stored ROWS and FOLLOW_LAYOUT both
     * show as Rows (Home's rows); the stored value is kept for sync and import.
     */
    val displayMode: FolderViewMode
        get() = if (this == TABBED_GRID) TABBED_GRID else ROWS

    companion object {
        fun fromString(value: String?): FolderViewMode = when (value?.lowercase()) {
            "rows" -> ROWS
            "follow_layout" -> FOLLOW_LAYOUT
            else -> TABBED_GRID
        }
    }
}
