package com.videowall.render

import android.util.Log
import com.videowall.model.CropRect
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Crops each WebRTC frame to the assigned grid tile before rendering.
 * Crop can be updated live when Master drag-drops the client onto a new tile.
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
        if (!enabled || crop.width <= 0f || crop.height <= 0f) {
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

        val cropX = (crop.x * width).toInt().coerceIn(0, width - 1)
        val cropY = (crop.y * height).toInt().coerceIn(0, height - 1)
        val cropW = (crop.width * width).toInt().coerceAtLeast(1).coerceAtMost(width - cropX)
        val cropH = (crop.height * height).toInt().coerceAtLeast(1).coerceAtMost(height - cropY)

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
