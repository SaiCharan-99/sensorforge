package com.application.sensorforge.sdk.detect

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-window IMU features (tech spec §18). Pure and deterministic.
 * Returns null when a required channel is missing or too sparse in the window:
 * unknown by default (rule 5), the caller reports NOT_OBSERVABLE.
 */
object WindowFeatures {
    const val G = 9.80665
    private const val RAD_TO_DEG = 57.29577951308232

    /** Gap after the |a| peak before stillness is measured, so the impact tail is excluded. */
    const val SETTLE_NANOS = 250_000_000L

    val NAMES: List<String> = buildList {
        for (s in listOf("acc_g", "gyro_dps")) addAll(listOf("max", "mean", "std", "min", "range").map { "${s}_$it" })
        for (s in listOf("acc_jerk", "gyro_jerk")) addAll(listOf("absmax", "absmean", "std").map { "${s}_$it" })
        addAll(listOf("frac_acc_gt2g", "frac_acc_gt3g", "frac_gyro_gt150", "frac_gyro_gt300"))
        addAll(listOf("orient_delta_deg", "orient_max_deg"))
        addAll(listOf("post_peak_acc_std", "post_peak_acc_dev_1g"))
        addAll(listOf("zcr_acc_mag", "zcr_acc_x", "zcr_acc_y", "zcr_acc_z", "zcr_gyro_mag"))
        addAll(listOf("acc_x_std", "acc_y_std", "acc_z_std", "gyro_x_std", "gyro_y_std", "gyro_z_std"))
        add("peak_pos")
    }
    val DIM = NAMES.size

    /** Minimum samples per channel for a window to be observable (half the nominal rate). */
    fun minSamples(lengthS: Double, hz: Int) = (lengthS * hz / 2).toInt().coerceAtLeast(2)

    /**
     * Features for the window [endNanos - lengthS, endNanos]. Post-peak stillness uses up to
     * [stillnessS] starting [SETTLE_NANOS] after the |a| peak, clipped to the window.
     */
    fun compute(
        samples: List<ImuSample>, endNanos: Long, lengthS: Double, hz: Int, stillnessS: Double = 2.0,
    ): FloatArray? {
        val start = endNanos - (lengthS * 1e9).toLong()
        val inWin = samples.filter { it.tNanos in start..endNanos }
            .sortedWith(compareBy({ it.tNanos }, { it.channel }))
        val acc = inWin.filter { it.channel == ImuChannel.ACCEL }
        val gyr = inWin.filter { it.channel == ImuChannel.GYRO }
        val rot = inWin.filter { it.channel == ImuChannel.ROTATION }
        val need = minSamples(lengthS, hz)
        if (acc.size < need || gyr.size < need || rot.size < 2) return null

        val aMag = DoubleArray(acc.size) { mag(acc[it]) / G }
        val gMag = DoubleArray(gyr.size) { mag(gyr[it]) * RAD_TO_DEG }
        val out = ArrayList<Double>(DIM)

        for (m in listOf(aMag, gMag)) {
            val mx = m.max(); val mn = m.min()
            out += listOf(mx, m.average(), std(m), mn, mx - mn)
        }
        for ((m, ts) in listOf(aMag to acc, gMag to gyr)) {
            val j = DoubleArray(m.size - 1) { i ->
                val dt = (ts[i + 1].tNanos - ts[i].tNanos) / 1e9
                if (dt > 0) (m[i + 1] - m[i]) / dt else 0.0
            }
            out += listOf(j.maxOf { abs(it) }, j.map { abs(it) }.average(), std(j))
        }
        out += listOf(frac(aMag, 2.0), frac(aMag, 3.0), frac(gMag, 150.0), frac(gMag, 300.0))

        val q0 = rot.first()
        out += listOf(angleDeg(q0, rot.last()), rot.maxOf { angleDeg(q0, it) })

        val peak = aMag.indices.maxBy { aMag[it] }
        val tPeak = acc[peak].tNanos
        val stillStart = tPeak + SETTLE_NANOS
        val stillEnd = stillStart + (stillnessS * 1e9).toLong()
        val post = acc.indices.filter { acc[it].tNanos >= stillStart && acc[it].tNanos <= stillEnd }
            .map { aMag[it] }.toDoubleArray()
        out += if (post.size >= 2) listOf(std(post), post.map { abs(it - 1.0) }.average()) else listOf(0.0, 0.0)

        out += listOf(
            zcr(aMag), zcr(axis(acc) { it.x }), zcr(axis(acc) { it.y }), zcr(axis(acc) { it.z }), zcr(gMag),
        )
        for (s in listOf(acc, gyr)) out += listOf(std(axis(s) { it.x }), std(axis(s) { it.y }), std(axis(s) { it.z }))
        out += (tPeak - start).toDouble() / (endNanos - start)

        check(out.size == DIM)
        return FloatArray(DIM) { out[it].toFloat() }
    }

    /** Window end times stepping back from the newest sample by [hopS], oldest first. */
    fun windowEnds(samples: List<ImuSample>, lengthS: Double, hopS: Double): List<Long> {
        if (samples.isEmpty()) return emptyList()
        val first = samples.minOf { it.tNanos }
        val last = samples.maxOf { it.tNanos }
        val len = (lengthS * 1e9).toLong()
        val hop = (hopS * 1e9).toLong()
        return generateSequence(last) { it - hop }.takeWhile { it - len >= first }.toList().reversed()
    }

    private fun axis(s: List<ImuSample>, f: (ImuSample) -> Float) = DoubleArray(s.size) { f(s[it]).toDouble() }

    private fun mag(s: ImuSample) = sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())

    private fun std(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    private fun frac(v: DoubleArray, thr: Double) = v.count { it > thr }.toDouble() / v.size

    private fun zcr(v: DoubleArray): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        var n = 0
        for (i in 1 until v.size) if ((v[i - 1] - m) * (v[i] - m) < 0) n++
        return n.toDouble() / (v.size - 1)
    }

    /** Rotation angle between two unit quaternions, in degrees. */
    private fun angleDeg(a: ImuSample, b: ImuSample): Double {
        val dot = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).toDouble()
        return 2 * acos(min(1.0, max(-1.0, dot))) * RAD_TO_DEG
    }
}
