package com.videowall.sync

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Maintains a clock offset relative to the Master.
 * All timestamps used for rendering / audio decisions should go through [nowNs].
 */
class TimeSyncManager {

    private val offsetNs = AtomicLong(0L)
    private val lastRttNs = AtomicLong(0L)

    /** Current synchronized time in nanoseconds (monotonic domain aligned to Master). */
    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos() + offsetNs.get()

    fun offsetNs(): Long = offsetNs.get()

    fun lastRttNs(): Long = lastRttNs.get()

    /**
     * Called when a time beacon arrives from Master.
     * @param masterMonoNs  Master's SystemClock.elapsedRealtimeNanos() at send
     * @param receiveLocalNs Local elapsedRealtimeNanos when packet was received
     * @param sendLocalNs   Local elapsedRealtimeNanos when the request (if any) was sent
     *
     * Simple one-way or request-reply offset estimation.
     * For request-reply: RTT = receive - send; offset ≈ master - local - RTT/2
     */
    fun onMasterTime(
        masterMonoNs: Long,
        receiveLocalNs: Long,
        sendLocalNs: Long? = null
    ) {
        if (sendLocalNs != null) {
            val rtt = receiveLocalNs - sendLocalNs
            lastRttNs.set(rtt)
            val estimatedMasterAtReceive = masterMonoNs + rtt / 2
            val newOffset = estimatedMasterAtReceive - receiveLocalNs
            // mild smoothing
            val prev = offsetNs.get()
            offsetNs.set((prev * 3 + newOffset) / 4)
        } else {
            // one-way: assume negligible one-way delay on LAN
            val newOffset = masterMonoNs - receiveLocalNs
            val prev = offsetNs.get()
            offsetNs.set((prev * 7 + newOffset) / 8)
        }
    }
}
