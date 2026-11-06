package com.nuvio.tv.core.assessment

/**
 * The key a first-launch setup is recorded against (Spec 33 Q4): MANUFACTURER|MODEL|DEVICE|RAM
 * tier label. TV / receiver facts (HDR types, passthrough set) are left out on purpose: they
 * change with TV and AVR power and input, so they would flap and re-show the welcome card.
 */
object DeviceFingerprint {
    fun of(manufacturer: String?, model: String?, device: String?, ramTierLabel: String): String =
        listOf(manufacturer, model, device, ramTierLabel).joinToString("|") { it.orEmpty().trim() }
}
