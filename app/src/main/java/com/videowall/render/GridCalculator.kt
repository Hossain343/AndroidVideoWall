package com.videowall.render

import com.videowall.model.CropRect
import com.videowall.model.GridMath

/**
 * Thin adapter over [GridMath] for 1-based row-major grid indices.
 * Crop is always in Master source-canvas UV space.
 */
object GridCalculator {

    fun cropForIndex(
        index: Int,
        columns: Int,
        rows: Int
    ): CropRect {
        require(index >= 1) { "Index must be 1-based" }
        require(columns >= 1 && rows >= 1) { "Grid dimensions must be positive" }
        val max = columns * rows
        require(index <= max) { "Index $index exceeds grid size $max" }

        val zeroBased = index - 1
        val col = zeroBased % columns
        val row = zeroBased / columns
        return GridMath.cropForTile(col, row, columns, rows)
    }

    /** Horizontal position in [-1, +1] used for spatial audio pan. */
    fun horizontalPan(index: Int, columns: Int, rows: Int): Float {
        val zeroBased = index - 1
        val col = zeroBased % columns
        return GridMath.panForCol(col, columns)
    }
}
