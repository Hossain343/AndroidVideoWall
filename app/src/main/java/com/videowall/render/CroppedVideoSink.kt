package com.videowall.render

import android.util.Log
import com.videowall.model.CropRect
import com.videowall.model.GridMath
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Crops each WebRTC frame to the assigned grid tile in Master source-canvas space.
 * Crop is orientation-aware: rotation is mapped back to buffer-native coordinates
 * so portrait/landscape client rotation never breaks grid alignment.
 */
class CroppedVideoSink(
    private val target: VideoSink,
    @Volatile private var crop: CropRect
) : VideoSink {

    @Volatile
    private var enabled = true

    fun updateCrop(newCrop: CropRect) {
        crop = newCrop
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    override fun onFrame(frame: VideoFrame) {
        if (!enabled || !crop.isValid()) {
            target.onFrame(frame)
            return
        }

        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) {
            target.onFrame(frame)
            return
        }

        val px = GridMath.pixelCrop(crop, width, height, frame.rotation)
        val cropX = px[0].coerceIn(0, width - 1)
        val cropY = px[1].coerceIn(0, height - 1)
        val cropW = px[2].coerceAtLeast(1).coerceAtMost(width - cropX)
        val cropH = px[3].coerceAtLeast(1).coerceAtMost(height - cropY)

        try {
            val cropped = buffer.cropAndScale(cropX, cropY, cropW, cropH, cropW, cropH)
            val croppedFrame = VideoFrame(cropped, frame.rotation, frame.timestampNs)
            try {
                target.onFrame(croppedFrame)
            } finally {
                croppedFrame.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crop failed, passing original", e)
            target.onFrame(frame)
        }
    }

    companion object {
        private const val TAG = "CroppedVideoSink"
    }
}
