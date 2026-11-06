package com.nuvio.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.nuvio.tv.ui.theme.NuvioFocus
import com.nuvio.tv.ui.theme.NuvioMotion
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
object NuvioScrollDefaults {
    val smoothScrollSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = 0.95f,
            stiffness = NuvioMotion.tokens.durations.fast.toFloat()
        )

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            if (containerSize <= 0f || size <= 0f) return 0f
            val itemCenter = offset + size / 2f
            val viewportTarget = containerSize * NuvioFocus.tokens.scrollViewportTarget
            return itemCenter - viewportTarget
        }
    }

    /**
     * Scrolls only as far as needed to show the item plus [marginPx] on each side (less when the
     * viewport has no room for it); zero while it already shows. For a scroller nested in a
     * same-axis scroller: Compose hands the part of a bring-into-view scroll the list cannot
     * consume to its nested-scroll parent, and the inherited specs (smoothScrollSpec, the TV
     * pivot) target a fixed viewport fraction, so they ask past the list's ends. With [marginPx]
     * no larger than the list's content padding every request fits the list's scroll range.
     */
    fun minimalScrollSpec(marginPx: Int): BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            // Clamped so item + margins fit: a margin that cannot fit would ping-pong.
            val margin = marginPx.toFloat().coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
            val leading = offset - margin
            val trailing = offset + size + margin
            return when {
                leading >= 0f && trailing <= containerSize -> 0f
                // Only an item larger than the viewport (margin 0) already covering it.
                leading < 0f && trailing > containerSize -> 0f
                leading < 0f -> leading
                else -> trailing - containerSize
            }
        }
    }

    /**
     * RTL-aware horizontal [BringIntoViewSpec] for poster rows: keeps a
     * start-side offset (the row's start padding) visible at both ends of a
     * focused scroll, in LTR and RTL alike.
     *
     * G6 consolidation home (2026-08-30): previously four inline copies of
     * this exact algorithm — ContinueWatchingSection, CatalogRowSection and
     * CollectionRowSection (both since deleted) hardcoded spacing.xxxl; ModernHomeRows passed its
     * rowStartPadding through. Formatting had drifted; the bodies had not.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    fun rtlAwareRowBringIntoViewSpec(
        defaultSpec: BringIntoViewSpec,
        isRtl: Boolean,
        startOffsetPx: Int
    ): BringIntoViewSpec = object : BringIntoViewSpec {
        override val scrollAnimationSpec: AnimationSpec<Float> =
            defaultSpec.scrollAnimationSpec

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            val childSize = abs(size)
            if (isRtl) {
                val childSmallerThanParent = childSize <= containerSize
                val initialTarget = containerSize - startOffsetPx.toFloat()
                val targetForTrailingEdge =
                    if (childSmallerThanParent && initialTarget < childSize) {
                        childSize
                    } else {
                        initialTarget
                    }
                return (offset + size) - targetForTrailingEdge
            } else {
                val childSmallerThanParent = childSize <= containerSize
                val initialTarget = startOffsetPx.toFloat()
                val spaceAvailable = containerSize - initialTarget
                val targetForLeadingEdge =
                    if (childSmallerThanParent && spaceAvailable < childSize) {
                        containerSize - childSize
                    } else {
                        initialTarget
                    }
                return offset - targetForLeadingEdge
            }
        }
    }
}
