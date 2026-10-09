package com.application.sensorforge.sdk.detect

import com.application.sensorforge.sdk.train.Example
import kotlin.math.sqrt

/** A parsed IMU replay. [simulation] is true for every fixture file (header says SIMULATION). */
data class ImuReplay(val name: String, val simulation: Boolean, val samples: List<ImuSample>) {
    companion object {
        /** CSV: `t_ns,channel,x,y,z,w` with channel A/G/R; `#` lines are header comments. */
        fun parseCsv(name: String, text: String): ImuReplay {
            var sim = false
            val out = ArrayList<ImuSample>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                if (line.startsWith("#")) { if ("SIMULATION" in line) sim = true; continue }
                if (line.startsWith("t_ns")) continue
                val p = line.split(',')
                val ch = when (p[1]) {
                    "A" -> ImuChannel.ACCEL; "G" -> ImuChannel.GYRO; "R" -> ImuChannel.ROTATION
                    else -> error("$name: unknown channel ${p[1]}")
                }
                out += ImuSample(p[0].toLong(), ch, p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat())
            }
            return ImuReplay(name, sim, out)
        }

        fun toCsv(header: List<String>, samples: List<ImuSample>): String = buildString {
            header.forEach { append("# ").append(it).append('\n') }
            append("t_ns,channel,x,y,z,w\n")
            for (s in samples.sortedWith(compareBy({ it.tNanos }, { it.channel }))) {
                val c = when (s.channel) { ImuChannel.ACCEL -> "A"; ImuChannel.GYRO -> "G"; ImuChannel.ROTATION -> "R" }
                append("${s.tNanos},$c,${s.x},${s.y},${s.z},${s.w}\n")
            }
        }
    }
}

/**
 * Turns recordings into training examples (H3). Continuous classes (normal, shake) use every
 * window; event classes (drop, impact_like) use only windows anchored on the |a| peak the same
 * way the detector evaluates them: peak within the first (length − settle − stillness) of the
 * window, so post-peak stillness is fully observed.
 */
object ImuDataset {
    val CLASSES = listOf("normal", "shake", "drop", "impact_like")
    val EVENT_CLASSES = setOf("drop", "impact_like")

    fun examples(
        samples: List<ImuSample>, label: String, session: String, heldOut: Boolean,
        lengthS: Double, hopS: Double, hz: Int, stillnessS: Double,
    ): List<Example> {
        val ends = if (label in EVENT_CLASSES) {
            val peak = peakNanos(samples) ?: return emptyList()
            eventWindowEnds(peak, lengthS, hopS, stillnessS).filter { e ->
                val first = samples.minOf { it.tNanos }; val last = samples.maxOf { it.tNanos }
                e <= last && e - (lengthS * 1e9).toLong() >= first
            }
        } else WindowFeatures.windowEnds(samples, lengthS, hopS)
        return ends.mapNotNull { e ->
            WindowFeatures.compute(samples, e, lengthS, hz, stillnessS)?.let { Example(it, label, session, heldOut) }
        }
    }

    /** Window ends that put [peakNanos] in the anchored range; also the detector's evaluation points. */
    fun eventWindowEnds(peakNanos: Long, lengthS: Double, hopS: Double, stillnessS: Double): List<Long> {
        val minLag = WindowFeatures.SETTLE_NANOS + (stillnessS * 1e9).toLong()
        val maxLag = (lengthS * 1e9).toLong()
        val hop = (hopS * 1e9).toLong()
        return generateSequence(peakNanos + minLag) { it + hop }.takeWhile { it - peakNanos <= maxLag }.toList()
    }

    fun peakNanos(samples: List<ImuSample>): Long? =
        samples.filter { it.channel == ImuChannel.ACCEL }
            .maxWithOrNull(compareBy<ImuSample> { sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble()) }.thenBy { -it.tNanos })
            ?.tNanos
}
