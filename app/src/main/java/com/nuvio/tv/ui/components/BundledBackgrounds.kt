package com.nuvio.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R

/**
 * A bundled profile background (Spec 44 D3): a light-painted ribbon rendered by
 * `docs/design/generators/spec44/ribbons2.py` (own work, no licence), shipped as a 1920x1080 WebP.
 * [slug] (the generator's composition_palette) is stored in profile look entries: never rename or
 * reuse one. [displayName] is the palette, as the approved mockup labels the tiles.
 */
@Immutable
data class BundledBackground(
    val slug: String,
    val displayName: String,
    @param:DrawableRes val drawable: Int
)

/** The D3 round-3 set, in picker order (sandbox u44 v3 stage 6). */
object BundledBackgrounds {
    val all: List<BundledBackground> = listOf(
        BundledBackground("arch_ember", "Ember", R.drawable.background_arch_ember),
        BundledBackground("loop_magenta", "Magenta", R.drawable.background_loop_magenta),
        BundledBackground("sweep_teal", "Teal", R.drawable.background_sweep_teal),
        BundledBackground("halo_gold", "Gold", R.drawable.background_halo_gold),
        BundledBackground("wave_cobalt", "Cobalt", R.drawable.background_wave_cobalt),
        BundledBackground("spiral_violet", "Violet", R.drawable.background_spiral_violet),
        BundledBackground("cascade_emerald", "Emerald", R.drawable.background_cascade_emerald),
        BundledBackground("infinity_ruby", "Ruby", R.drawable.background_infinity_ruby)
    )

    val bySlug: Map<String, BundledBackground> = all.associateBy { it.slug }
}
