package com.videowall.render

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.videowall.sync.TimeSyncManager
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client-side jitter buffer.
 *
 * Frames are held until synchronized master time reaches
 * frameTimestamp + targetDelayMs, then released to [target].
 * Late frames are dropped to keep all wall tiles in lock-step.
 */
class JitterBufferSink(
    private val target: VideoSink,
    private val timeSync: TimeSyncManager,
    private val targetDelayMs: Long = 80L
) : VideoSink {

    private data class Held(
        val frame: VideoFrame,
        val masterTsNs: Long
    )

    private val queue = ConcurrentLinkedQueue<Held>()
    private val running = AtomicBoolean(true)
    private val handler = Handler(Looper.getMainLooper())
    private val drainRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            drain()
            handler.postDelayed(this, 8L)
        }
    }

    init {
        handler.post(drainRunnable)
    }

    override fun onFrame(frame: VideoFrame) {
        if (!running.get()) {
            frame.release()
            return
        }
        val masterTs = frame.timestampNs + timeSync.offsetNs()
        frame.retain()
        queue.offer(Held(frame, masterTs))
        while (queue.size > MAX_QUEUE) {
            queue.poll()?.frame?.release()
        }
    }

    private fun drain() {
        val nowMaster = timeSync.nowNs()
        val targetDelayNs = targetDelayMs * 1_000_000L
        while (true) {
            val head = queue.peek() ?: break
            val presentAt = head.masterTsNs + targetDelayNs
            if (presentAt > nowMaster) break

            queue.poll()
            val lateByMs = (nowMaster - presentAt) / 1_000_000L
            if (lateByMs > MAX_LATE_MS) {
                head.frame.release()
                continue
            }
            try {
                target.onFrame(head.frame)
            } catch (e: Exception) {
                Log.w(TAG, "render failed", e)
            } finally {
                head.frame.release()
            }
        }
    }

    fun release() {
        running.set(false)
        handler.removeCallbacks(drainRunnable)
        while (true) {
            val h = queue.poll() ?: break
            h.frame.release()
        }
    }

    companion object {
        private const val TAG = "JitterBuffer"
        private const val MAX_QUEUE = 6
        private const val MAX_LATE_MS = 40L
    }
}
