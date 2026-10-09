package com.application.sensorforge.sdk.engine

/** Sorted, non-overlapping closed intervals on the clip time axis. */
internal class Intervals private constructor(val spans: List<Pair<Double, Double>>) {

    fun contains(t: Double) = spans.any { (a, b) -> t in a..b }

    fun length() = spans.sumOf { (a, b) -> b - a }

    fun clip(start: Double, end: Double) =
        Intervals(spans.mapNotNull { (a, b) -> maxOf(a, start).let { s -> minOf(b, end).let { e -> if (e > s) s to e else null } } })

    fun minus(other: Intervals): Intervals {
        var cur = spans
        for ((ca, cb) in other.spans) {
            cur = cur.flatMap { (a, b) ->
                if (cb <= a || ca >= b) listOf(a to b)
                else listOfNotNull(if (ca > a) a to ca else null, if (cb < b) cb to b else null)
            }
        }
        return Intervals(cur)
    }

    companion object {
        val EMPTY = Intervals(emptyList())

        fun of(raw: List<Pair<Double, Double>>): Intervals {
            val merged = mutableListOf<Pair<Double, Double>>()
            for ((a, b) in raw.filter { it.second >= it.first }.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && a <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, b)
                else merged.add(a to b)
            }
            return Intervals(merged)
        }
    }
}
