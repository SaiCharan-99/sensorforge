package com.application.sensorforge.sdk.detect

/**
 * Time-bounded IMU buffer (tech spec §17: 20 s). Keeps samples whose timestamp is
 * within [windowSeconds] of the newest sample. Thread-safe; [snapshot] returns a copy
 * ordered by arrival (sensors deliver per-channel monotonic timestamps).
 */
class ImuRingBuffer(val windowSeconds: Double) {
    private val windowNanos = (windowSeconds * 1e9).toLong()
    private val samples = ArrayDeque<ImuSample>()
    private var newest = Long.MIN_VALUE

    @Synchronized fun add(s: ImuSample) {
        samples.addLast(s)
        if (s.tNanos > newest) newest = s.tNanos
        val cutoff = newest - windowNanos
        while (samples.isNotEmpty() && samples.first().tNanos < cutoff) samples.removeFirst()
    }

    @Synchronized fun snapshot(): List<ImuSample> = samples.toList()

    @Synchronized fun clear() { samples.clear(); newest = Long.MIN_VALUE }

    @Synchronized fun size(): Int = samples.size

    /** Span covered by buffered samples, in seconds. */
    @Synchronized fun spanSeconds(): Double =
        if (samples.isEmpty()) 0.0 else (newest - samples.minOf { it.tNanos }) / 1e9

    /** Mean rate (Hz) of one channel over the buffered span; 0 if fewer than 2 samples. */
    @Synchronized fun rateHz(channel: ImuChannel): Double {
        val ts = samples.filter { it.channel == channel }.map { it.tNanos }
        if (ts.size < 2) return 0.0
        return (ts.size - 1) / ((ts.max() - ts.min()) / 1e9)
    }
}
