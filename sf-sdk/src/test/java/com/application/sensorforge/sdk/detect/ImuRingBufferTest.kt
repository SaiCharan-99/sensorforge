package com.application.sensorforge.sdk.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class ImuRingBufferTest {
    private fun s(tMs: Long, ch: ImuChannel = ImuChannel.ACCEL) = ImuSample(tMs * 1_000_000, ch, 0f, 0f, 9.81f)

    @Test fun evictsSamplesOlderThanWindow() {
        val b = ImuRingBuffer(20.0)
        for (t in 0L..30_000L step 10) b.add(s(t))
        val snap = b.snapshot()
        assertEquals(10_000L * 1_000_000, snap.first().tNanos)
        assertEquals(30_000L * 1_000_000, snap.last().tNanos)
        assertEquals(20.0, b.spanSeconds(), 1e-9)
    }

    @Test fun ratePerChannel() {
        val b = ImuRingBuffer(20.0)
        for (t in 0L..1_000L step 10) { b.add(s(t)); if (t % 20 == 0L) b.add(s(t, ImuChannel.GYRO)) }
        assertEquals(100.0, b.rateHz(ImuChannel.ACCEL), 1e-6)
        assertEquals(50.0, b.rateHz(ImuChannel.GYRO), 1e-6)
        assertEquals(0.0, b.rateHz(ImuChannel.ROTATION), 0.0)
    }

    @Test fun snapshotIsACopy() {
        val b = ImuRingBuffer(1.0)
        b.add(s(0))
        val snap = b.snapshot()
        b.add(s(5))
        assertEquals(1, snap.size)
    }
}
