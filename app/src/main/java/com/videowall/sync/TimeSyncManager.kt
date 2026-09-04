package com.videowall.sync

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Maintains a clock offset relative to the Master.
 * All timestamps used for rendering decisions go through [nowNs].
 *
 * Protocol:
 *  Client sends TIME_REQ at local T0
 *  Master replies TIME with masterMonoNs
 *  Client receives at local T1
 *  offset ≈ masterMonoNs + RTT/2 - T1
 */
class TimeSyncManager {

    private val offsetNs = AtomicLong(0L)
    private val lastRttNs = AtomicLong(0L)
    private val sampleCount = AtomicLong(0L)

    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos() + offsetNs.get()

    fun offsetNs(): Long = offsetNs.get()

    fun lastRttNs(): Long = lastRttNs.get()

    fun onMasterTime(
        masterMonoNs: Long,
        receiveLocalNs: Long,
        sendLocalNs: Long? = null
    ) {
        if (sendLocalNs != null && sendLocalNs > 0) {
            val rtt = (receiveLocalNs - sendLocalNs).coerceAtLeast(0)
            lastRttNs.set(rtt)
            val estimatedMasterAtReceive = masterMonoNs + rtt / 2
            val newOffset = estimatedMasterAtReceive - receiveLocalNs
            smoothOffset(newOffset)
        } else {
            val newOffset = masterMonoNs - receiveLocalNs
            smoothOffset(newOffset)
        }
    }

    private fun smoothOffset(newOffset: Long) {
        val n = sampleCount.incrementAndGet()
        val prev = offsetNs.get()
        if (n <= 2L || prev == 0L) {
            offsetNs.set(newOffset)
        } else {
            offsetNs.set((prev * 7 + newOffset) / 8)
        }
    }
}
