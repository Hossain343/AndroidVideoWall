package com.videowall.render

/**
 * Computes normalized crop rectangles for a 1-based row-major grid index.
 *
 * Example 3x3:
 *  1 2 3
 *  4 5 6
 *  7 8 9
 */
data class CropRect(
    val x: Float,      // left  [0..1]
    val y: Float,      // top   [0..1]
    val width: Float,  // [0..1]
    val height: Float  // [0..1]
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

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

        val w = 1f / columns
        val h = 1f / rows
        return CropRect(
            x = col * w,
            y = row * h,
            width = w,
            height = h
        )
    }

    /** Horizontal position in [-1, +1] used for spatial audio pan. */
    fun horizontalPan(index: Int, columns: Int, rows: Int): Float {
        val crop = cropForIndex(index, columns, rows)
        val centerX = crop.x + crop.width / 2f
        // map [0,1] → [-1,+1]
        return (centerX * 2f) - 1f
    }
}
