package com.videowall.model

/**
 * Connected client as seen by the Master dashboard.
 * [phoneNumber] is a stable 1-based index shown on the client status card.
 * [gridCol]/[gridRow] are null until the Master drops the device on the chessboard.
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

data class CropRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

object GridMath {
    fun cropForTile(col: Int, row: Int, columns: Int, rows: Int): CropRect {
        require(columns >= 1 && rows >= 1)
        val w = 1f / columns
        val h = 1f / rows
        return CropRect(col * w, row * h, w, h)
    }

    /** Stereo pan -1..+1 from column position. */
    fun panForCol(col: Int, columns: Int): Float {
        if (columns <= 1) return 0f
        val center = col + 0.5f
        return ((center / columns) * 2f) - 1f
    }
}

/** QR / join payload: vw://ip:port/sessionId */
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
