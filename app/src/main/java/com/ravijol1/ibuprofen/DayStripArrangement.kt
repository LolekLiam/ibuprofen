package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Justify short day lists; preserve a small gap when the list needs scrolling. */
internal object DayStripArrangement : Arrangement.Horizontal {
    override val spacing = 4.dp

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        val arrangement = if (sizes.sum() + spacing.roundToPx() * (sizes.size - 1).coerceAtLeast(0) <= totalSize)
            Arrangement.SpaceBetween else Arrangement.spacedBy(spacing)
        with(arrangement) { arrange(totalSize, sizes, layoutDirection, outPositions) }
    }
}
