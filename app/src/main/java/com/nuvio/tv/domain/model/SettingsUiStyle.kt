package com.nuvio.tv.domain.model

/**
 * Retired settings shell styles (Spec 31 Q1: one style). The stored `settings_ui_style` key in the
 * synced theme_settings blob stays readable through [com.nuvio.tv.data.local.ThemeDataStore] but is
 * never written.
 */
enum class SettingsUiStyle {
    CLASSIC,
    ZEN,
    HORIZON
}
