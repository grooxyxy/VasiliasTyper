package com.vasiliastyper.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Shared stripe helper so tall comic pages keep their bottom-most content.
 *
 * The last stripe is always aligned to the bottom edge, even when a fixed
 * step would otherwise skip it.
 */
object StripeTiling {
    fun startPositions(
        totalHeight: Int,
        stripeHeight: Int,
        overlap: Int,
        minStep: Int = 256
    ): List<Int> {
        if (totalHeight <= 0) return emptyList()
        val safeStripeHeight = stripeHeight.coerceAtLeast(1)
        val step = (safeStripeHeight - overlap).coerceAtLeast(minStep)
        val starts = mutableListOf<Int>()
        var top = 0
        while (top < totalHeight) {
            starts.add(top)
            if (top >= totalHeight - safeStripeHeight) break
            top += step
        }
        val lastTop = max(0, totalHeight - safeStripeHeight)
        starts.add(lastTop)
        return starts.distinct().sorted()
    }

    fun ranges(
        totalHeight: Int,
        stripeHeight: Int,
        overlap: Int,
        minStep: Int = 256
    ): List<IntRange> {
        val starts = startPositions(totalHeight, stripeHeight, overlap, minStep)
        if (starts.isEmpty()) return emptyList()
        return starts.map { top ->
            val bottom = min(totalHeight, top + stripeHeight)
            top until bottom
        }
    }
}
