package com.videowall.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin

/**
 * Applies constant-power stereo panning based on grid column.
 * Routes remote WebRTC audio to speaker and applies mute/unmute.
 */
class SpatialAudioController {

    @Volatile
    var basePan: Float = 0f

    @Volatile
    var actionBias: Float = 0f

    fun effectivePan(): Float {
        return (basePan + actionBias * 0.35f).coerceIn(-1f, 1f)
    }

    fun gains(): Pair<Float, Float> {
        val p = effectivePan()
        val angle = (p + 1f) * (Math.PI.toFloat() / 4f)
        return cos(angle) to sin(angle)
    }

    fun setGridPan(pan: Float) {
        basePan = pan.coerceIn(-1f, 1f)
        Log.d(TAG, "Grid pan set to $basePan")
    }

    fun setActionX(x: Float) {
        actionBias = ((x * 2f) - 1f).coerceIn(-1f, 1f)
    }

    fun applyToDevice(context: Context, audioEnabled: Boolean) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
            if (audioEnabled) {
                am.adjustStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.ADJUST_UNMUTE,
                    0
                )
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0
                )
            } else {
                am.adjustStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.ADJUST_MUTE,
                    0
                )
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    0
                )
            }
            val (left, right) = gains()
            try {
                @Suppress("DEPRECATION")
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (audioEnabled) {
                        (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) *
                            ((left + right) / 2f).coerceIn(0.1f, 1f)).toInt()
                    } else 0,
                    0
                )
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyToDevice", e)
        }
    }

    companion object {
        private const val TAG = "SpatialAudio"
    }
}
