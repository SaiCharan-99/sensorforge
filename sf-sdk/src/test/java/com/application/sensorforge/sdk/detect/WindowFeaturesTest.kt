package com.application.sensorforge.sdk.detect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class WindowFeaturesTest {
    private fun f(name: String, v: FloatArray) = v[WindowFeatures.NAMES.indexOf(name)].toDouble()

    /** Synthetic 100 Hz stream: phone at rest, optional 5 g spike at [spikeAtS], optional yaw rotation. */
    private fun stream(durS: Double, spikeAtS: Double? = null, yawDegPerS: Double = 0.0): List<ImuSample> {
        val out = ArrayList<ImuSample>()
        for (i in 0..(durS * 100).toInt()) {
            val t = i * 10_000_000L
            val ts = i / 100.0
            val spike = spikeAtS != null && abs(ts - spikeAtS) < 0.03
            val az = if (spike) 5 * WindowFeatures.G else WindowFeatures.G
            out += ImuSample(t, ImuChannel.ACCEL, 0f, 0f, az.toFloat())
            out += ImuSample(t, ImuChannel.GYRO, 0f, 0f, Math.toRadians(yawDegPerS).toFloat())
            val half = Math.toRadians(yawDegPerS * ts) / 2
            out += ImuSample(t, ImuChannel.ROTATION, 0f, 0f, sin(half).toFloat(), cos(half).toFloat())
        }
        return out
    }

    private fun last(s: List<ImuSample>) = s.maxOf { it.tNanos }

    @Test fun dimensionInSpecRange() = assertTrue(WindowFeatures.DIM in 32..64)

    @Test fun restingPhoneIsStill() {
        val s = stream(3.0)
        val v = WindowFeatures.compute(s, last(s), 3.0, 100)!!
        assertEquals(1.0, f("acc_g_max", v), 1e-4)
        assertEquals(0.0, f("acc_g_std", v), 1e-6)
        assertEquals(0.0, f("frac_acc_gt3g", v), 0.0)
        assertEquals(0.0, f("orient_delta_deg", v), 1e-3)
    }

    @Test fun spikeThenStillness() {
        val s = stream(3.0, spikeAtS = 0.5)
        val v = WindowFeatures.compute(s, last(s), 3.0, 100)!!
        assertEquals(5.0, f("acc_g_max", v), 1e-3)
        assertTrue(f("frac_acc_gt3g", v) > 0)
        assertEquals(0.0, f("post_peak_acc_std", v), 1e-6)
        assertEquals(0.5 / 3.0, f("peak_pos", v), 0.02)
    }

    @Test fun orientationDelta() {
        val s = stream(3.0, yawDegPerS = 30.0)
        val v = WindowFeatures.compute(s, last(s), 3.0, 100)!!
        assertEquals(90.0, f("orient_delta_deg", v), 0.5)
        assertEquals(30.0, f("gyro_dps_mean", v), 1e-3)
    }

    @Test fun missingChannelIsUnobservable() {
        val s = stream(3.0).filter { it.channel != ImuChannel.GYRO }
        assertNull(WindowFeatures.compute(s, last(s), 3.0, 100))
    }

    @Test fun droppedSamplesAreUnobservable() {
        // keep only every 3rd accel sample (~33 Hz < half of 100 Hz)
        val s = stream(3.0).filter { it.channel != ImuChannel.ACCEL || it.tNanos % 30_000_000L == 0L }
        assertNull(WindowFeatures.compute(s, last(s), 3.0, 100))
    }

    @Test fun deterministicUnderShuffle() {
        val s = stream(3.0, spikeAtS = 1.2, yawDegPerS = 10.0)
        val a = WindowFeatures.compute(s, last(s), 3.0, 100)!!
        val b = WindowFeatures.compute(s.shuffled(java.util.Random(7)), last(s), 3.0, 100)!!
        assertArrayEquals(a, b, 0f)
    }

    @Test fun windowEndsHop() {
        val ends = WindowFeatures.windowEnds(stream(5.0), 3.0, 0.5)
        assertEquals(5, ends.size) // 3.0, 3.5, 4.0, 4.5, 5.0
        assertEquals(5_000_000_000L, ends.last())
    }
}
