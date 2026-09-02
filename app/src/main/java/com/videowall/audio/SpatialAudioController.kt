package com.videowall.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

/**
 * Applies constant-power stereo panning based on the device's horizontal
 * position in the video wall grid.  Can also bias toward a dynamic "action X"
 * received from the Master over the data channel.
 *
 * For simplicity this class demonstrates the panning math; in a full WebRTC
 * pipeline the same gains are applied either via a custom AudioTrack sink
 * or by post-processing the remote audio track samples.
 */
class SpatialAudioController {

    @Volatile
    var basePan: Float = 0f   // -1 (left) … +1 (right)

    @Volatile
    var actionBias: Float = 0f // additional bias from Master

    private val running = AtomicBoolean(false)

    /** Final pan after combining base grid position + live action. */
    fun effectivePan(): Float {
        val p = (basePan + actionBias * 0.35f).coerceIn(-1f, 1f)
        return p
    }

    /** Constant-power pan gains. */
    fun gains(): Pair<Float, Float> {
        val p = effectivePan()
        // map [-1,1] → [0, π/2]
        val angle = (p + 1f) * (Math.PI.toFloat() / 4f)
        val left = cos(angle)
        val right = sin(angle)
        return left to right
    }

    fun setGridPan(pan: Float) {
        basePan = pan.coerceIn(-1f, 1f)
        Log.d(TAG, "Grid pan set to $basePan")
    }

    fun setActionX(x: Float) {
        // x is expected in [0,1] full-frame coordinate
        actionBias = ((x * 2f) - 1f).coerceIn(-1f, 1f)
    }

    companion object {
        private const val TAG = "SpatialAudio"
    }
}
