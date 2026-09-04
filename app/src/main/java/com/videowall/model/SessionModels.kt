package com.videowall.model

/**
 * Connected client as seen by the Master dashboard.
 * [phoneNumber] is a stable 1-based index shown on the client status card.
 * [gridCol]/[gridRow] are null until the Master places the device on the chessboard.
 */
data class ClientNode(
    val clientId: String,
    val phoneNumber: Int,
    val gridCol: Int? = null,
    val gridRow: Int? = null,
    val brightness: Float = 0.7f,
    val audioEnabled: Boolean = true
) {
    val isPlaced: Boolean get() = gridCol != null && gridRow != null
}

data class GridConfig(
    val columns: Int = 2,
    val rows: Int = 2
) {
    val tileCount: Int get() = columns * rows
}

/**
 * Normalized crop rectangle in source-canvas space [0..1].
 * Independent of client device orientation — always relative to Master source frame.
 */
data class CropRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    fun isValid(): Boolean = width > 0f && height > 0f
}

/**
 * Grid math that maps Master source canvas → per-tile crop boxes.
 * Crop is always in Master capture space. Client orientation must not change UV.
 */
object GridMath {

    fun cropForTile(col: Int, row: Int, columns: Int, rows: Int): CropRect {
        require(columns >= 1 && rows >= 1)
        val w = 1f / columns
        val h = 1f / rows
        return CropRect(
            x = (col * w).coerceIn(0f, 1f),
            y = (row * h).coerceIn(0f, 1f),
            width = w,
            height = h
        )
    }

    /**
     * Pixel-space crop against a concrete source buffer size.
     * Accounts for WebRTC frame rotation so crop is applied in buffer-native space
     * before the renderer applies the rotation matrix.
     */
    fun pixelCrop(
        crop: CropRect,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int
    ): IntArray {
        val rotated = rotationDegrees % 180 != 0
        val srcW = if (rotated) bufferHeight else bufferWidth
        val srcH = if (rotated) bufferWidth else bufferHeight

        val dx = (crop.x * srcW).toInt().coerceIn(0, srcW - 1)
        val dy = (crop.y * srcH).toInt().coerceIn(0, srcH - 1)
        val dw = (crop.width * srcW).toInt().coerceAtLeast(1).coerceAtMost(srcW - dx)
        val dh = (crop.height * srcH).toInt().coerceAtLeast(1).coerceAtMost(srcH - dy)

        return when (rotationDegrees % 360) {
            90 -> intArrayOf(dy, bufferHeight - dx - dw, dh, dw)
            180 -> intArrayOf(bufferWidth - dx - dw, bufferHeight - dy - dh, dw, dh)
            270 -> intArrayOf(bufferWidth - dy - dh, dx, dh, dw)
            else -> intArrayOf(dx, dy, dw, dh)
        }
    }

    fun panForCol(col: Int, columns: Int): Float {
        if (columns <= 1) return 0f
        val center = col + 0.5f
        return ((center / columns) * 2f) - 1f
    }
}

object JoinUri {
    private const val PREFIX = "vw://"

    fun encode(ip: String, port: Int, sessionId: String): String =
        "$PREFIX$ip:$port/$sessionId"

    fun decode(raw: String): Triple<String, Int, String>? {
        val s = raw.trim()
        if (!s.startsWith(PREFIX, ignoreCase = true)) return null
        val body = s.substring(PREFIX.length)
        val slash = body.indexOf('/')
        if (slash <= 0) return null
        val hostPort = body.substring(0, slash)
        val session = body.substring(slash + 1).ifBlank { return null }
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        val ip = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        return Triple(ip, port, session)
    }
}
