package com.application.sensorforge.sdk.detect

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SYNTHETIC IMU recordings for normal / shake / drop (SIMULATION, rule 2). Used by the Forge
 * "Simulate capture" button and by Stage B so the H3 train → hash-change path can run before a
 * human records real motion. They are saved with a SIMULATION header and must never be
 * presented as LIVE or used for accuracy claims.
 */
object ImuSynth {
    private const val HZ = 100
    private const val G = 9.80665f

    fun seconds(label: String) = when (label) { "normal" -> 15.0; "shake" -> 8.0; else -> 8.0 }

    fun generate(label: String, seed: Long, seconds: Double = seconds(label)): List<ImuSample> {
        val rng = Random(seed * 7919 + label.hashCode())
        val n = (seconds * HZ).toInt()
        val out = ArrayList<ImuSample>(n * 3)
        val dropAt = 2.0 + rng.nextDouble() * 1.5
        val freq = 3.0 + rng.nextDouble() * 3.0
        val amp = 14f + rng.nextFloat() * 18f
        var angle = 0.0
        val axis = floatArrayOf(rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f).let { a ->
            val m = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-3f); floatArrayOf(a[0] / m, a[1] / m, a[2] / m)
        }
        for (i in 0 until n) {
            val t = i / HZ.toDouble()
            val tn = (t * 1e9).toLong()
            var ax = 0f; var ay = 0f; var az = G
            var gx = 0f; var gy = 0f; var gz = 0f
            when (label) {
                "normal" -> {
                    // Holding / carrying: gentle sway, footstep bumps well below the shake range.
                    val step = (0.6f * sin(2 * PI * 1.8 * t)).toFloat()
                    ax = 0.5f * sin(2 * PI * 0.4 * t).toFloat() + 0.2f * step
                    ay = 0.4f * cos(2 * PI * 0.3 * t).toFloat()
                    az = G + 0.9f * step
                    gx = 0.15f * sin(2 * PI * 0.5 * t).toFloat(); gy = 0.1f * cos(2 * PI * 0.7 * t).toFloat()
                    angle += 0.004
                }
                "shake" -> {
                    // Hand shake: sustained oscillation, big but without a free-fall/impact signature.
                    val burst = if (t in 1.0..(seconds - 1.0)) 1f else 0f
                    ax = burst * amp * sin(2 * PI * freq * t).toFloat()
                    ay = burst * amp * 0.5f * cos(2 * PI * freq * t).toFloat()
                    az = G + burst * amp * 0.4f * sin(2 * PI * freq * 1.3 * t).toFloat()
                    gx = burst * 6f * sin(2 * PI * freq * t).toFloat(); gy = burst * 4f * cos(2 * PI * freq * t).toFloat()
                    angle += 0.02 * burst
                }
                else -> { // drop
                    val dt = t - dropAt
                    when {
                        dt < 0 -> { az = G + 0.1f * sin(2 * PI * 0.5 * t).toFloat() }
                        dt < 0.35 -> { ax = 0.3f; ay = 0.3f; az = 0.6f; gx = 4f; gy = 3f; angle += 0.08 }     // free fall + tumble
                        else -> {
                            val s = dt - 0.35
                            val spike = (10f + rng.nextFloat() * 8f) * G / 9.8f
                            val k = exp(-s * 18).toFloat()
                            az = G + spike * G * 0.1f * k * cos(2 * PI * 12 * s).toFloat() + spike * k * 5f
                            ax = spike * 0.4f * k * sin(2 * PI * 9 * s).toFloat()
                            gx = 20f * k; gy = 12f * k
                            angle += 0.02 * k
                        }
                    }
                }
            }
            val noise = if (label == "normal") 0.02f else 0.05f
            out += ImuSample(tn, ImuChannel.ACCEL, ax + rng.nextGaussian().toFloat() * noise, ay + rng.nextGaussian().toFloat() * noise, az + rng.nextGaussian().toFloat() * noise)
            out += ImuSample(tn, ImuChannel.GYRO, gx + rng.nextGaussian().toFloat() * 0.01f, gy, gz)
            val h = (angle / 2); val sh = sin(h).toFloat()
            out += ImuSample(tn, ImuChannel.ROTATION, axis[0] * sh, axis[1] * sh, axis[2] * sh, cos(h).toFloat())
        }
        return out
    }
}
