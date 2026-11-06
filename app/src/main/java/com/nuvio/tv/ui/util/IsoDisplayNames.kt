package com.nuvio.tv.ui.util

import java.util.Locale

private val ISO_CODE = Regex("[A-Za-z]{2,3}")

/**
 * "US, GB" -> "United States, United Kingdom" in [locale]; ISO language codes likewise when
 * [isCountry] is false. Parts that are not a 2-3 letter code, or that the platform cannot name,
 * stay as given. Shared by Detail's About sheet and the studio / network page (Spec 29g).
 */
internal fun isoDisplayNames(raw: String, isCountry: Boolean, locale: Locale = Locale.getDefault()): String =
    raw.split(",").joinToString(", ") { part ->
        val code = part.trim()
        if (!code.matches(ISO_CODE)) return@joinToString code
        val name = if (isCountry) {
            if (code.length == 2) Locale.forLanguageTag("und-$code").getDisplayCountry(locale) else ""
        } else {
            Locale.forLanguageTag(code).getDisplayLanguage(locale)
        }
        name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code
    }
