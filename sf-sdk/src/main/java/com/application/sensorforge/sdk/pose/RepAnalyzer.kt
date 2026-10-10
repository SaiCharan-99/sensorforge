package com.application.sensorforge.sdk.pose

import com.application.sensorforge.sdk.contracts.EntityRef
import com.application.sensorforge.sdk.contracts.ObservabilityWindow
import com.application.sensorforge.sdk.contracts.Primitive
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Track
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/** Interior angle at [b] in degrees; x is scaled by [aspect] (width / height) so angles are true. */
fun jointAngle(a: Landmark, b: Landmark, c: Landmark, aspect: Float = 1f): Double {
    val v1x = (a.x - b.x) * aspect; val v1y = a.y - b.y
    val v2x = (c.x - b.x) * aspect; val v2y = c.y - b.y
    val n = hypot(v1x, v1y) * hypot(v2x, v2y)
    if (n < 1e-9) return Double.NaN
    return Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / n).toDouble().coerceIn(-1.0, 1.0)))
}

/** Outcome of one attempt. [problems] name the exact criterion that was missed. */
data class RepAttempt(val tStart: Double, val tEnd: Double, val peakDeg: Double, val holdS: Double, val valid: Boolean, val problems: List<String>)

/** What S14 shows right now. */
data class RepLive(
    val angleDeg: Double?,
    val phase: String,
    val repsDone: Int,
    val holdProgressS: Double,
    val feedback: String?,
    val keypointsOk: Boolean,
)

/**
 * Pure rep/hold state machine (no Android). Feed frames in time order; read [timeline] at any time.
 * Criteria are checked exactly as configured. Anything unclear (keypoints lost) is never a pass:
 * the attempt is discarded and the observability window ends.
 */
class RepAnalyzer(private val cfg: RepConfig, private val aspect: Float = 9f / 16f, private val smoothing: Double = 0.5) {
    val attempts = mutableListOf<RepAttempt>()
    private val primitives = mutableListOf<Primitive>()
    private val windows = mutableListOf<DoubleArray>()
    private var windowOpen = false
    private var pid = 0
    private var lastT = 0.0
    private val smoothed = HashMap<Int, Double>()

    // knee extension attempt
    private var attemptStart: Double? = null
    private var peak = 0.0
    private var inRangeSince: Double? = null
    private var bestHold = 0.0

    // tree pose hold
    private var holdSince: Double? = null
    private var rangeEmitted = false
    private var held = false

    var live = RepLive(null, "waiting", 0, 0.0, "Stand where the camera sees your whole body.", false)
        private set

    val repsDone get() = attempts.count { it.valid }

    fun add(f: PoseFrame): RepLive {
        lastT = max(lastT, f.t)
        val crit = cfg.criteria()
        val lm = f.lm
        val ok = lm != null && lm.size >= Lm.COUNT && crit.all { c -> listOf(c.a, c.b, c.c).all { lm[it].vis >= cfg.minVisibility } }
        if (!ok || lm == null) {
            windowOpen = false
            attemptStart?.let { s ->
                attempts += RepAttempt(s, f.t, peak, bestHold, false, listOf("keypoints lost during the repetition"))
                resetAttempt()
            }
            if (holdSince != null && !held) attempts += RepAttempt(holdSince!!, f.t, 0.0, f.t - holdSince!!, false, listOf("keypoints lost during the hold"))
            holdSince = null; rangeEmitted = false; smoothed.clear()
            live = RepLive(null, "keypoints lost", repsDone, 0.0, if (f.lm == null) "No person found: step back so your whole body is visible." else "Keypoints not clear: improve the light and keep the joint in view.", false)
            return live
        }
        if (windowOpen) windows.last()[1] = f.t else { windows += doubleArrayOf(f.t, f.t); windowOpen = true }

        val angles = crit.map { c ->
            val raw = jointAngle(lm[c.a], lm[c.b], lm[c.c], aspect)
            val prev = smoothed[c.b]
            val s = if (prev == null || raw.isNaN()) raw else prev + smoothing * (raw - prev)
            smoothed[c.b] = s
            c to s
        }
        if (angles.any { it.second.isNaN() }) {
            live = RepLive(null, "keypoints lost", repsDone, 0.0, "Keypoints are degenerate: the joint is not visible.", false)
            return live
        }
        val conf = crit.flatMap { listOf(lm[it.a].vis, lm[it.b].vis, lm[it.c].vis) }.average().coerceIn(0.0, 1.0)
        live = when (cfg.exercise) {
            Exercise.KNEE_EXTENSION -> stepKnee(f.t, angles.first().second, conf)
            Exercise.TREE_POSE -> stepHold(f.t, angles, conf)
        }
        return live
    }

    private fun stepKnee(t: Double, a: Double, conf: Double): RepLive {
        val inRange = a in cfg.angleMin..cfg.angleMax
        if (attemptStart == null) {
            if (a <= cfg.restBelowDeg) {
                val msg = if (repsDone >= cfg.reps) "Meets configured criteria: $repsDone reps." else live.feedback?.takeIf { it.startsWith("Needs adjustment") || it.startsWith("Rep ") }
                    ?: "Straighten your knee to ${cfg.angleMin.toInt()}-${cfg.angleMax.toInt()} degrees."
                return RepLive(a, "rest", repsDone, 0.0, msg, true)
            }
            attemptStart = t; peak = a; inRangeSince = null; bestHold = 0.0
        }
        val s = attemptStart!!
        peak = max(peak, a)
        if (inRange) { if (inRangeSince == null) inRangeSince = t; bestHold = max(bestHold, t - inRangeSince!!) } else inRangeSince = null
        if (a > cfg.restBelowDeg) {
            val holdNow = inRangeSince?.let { t - it } ?: 0.0
            return RepLive(a, if (inRange) "holding" else "moving", repsDone, holdNow, null, true)
        }
        // Back at rest: judge the attempt.
        val dur = t - s
        val problems = mutableListOf<String>()
        if (peak < cfg.angleMin) problems += "range: reached %.0f deg, needs at least %.0f deg".format(peak, cfg.angleMin)
        if (peak > cfg.angleMax) problems += "range: %.0f deg is past the maximum %.0f deg".format(peak, cfg.angleMax)
        if (bestHold < cfg.holdS) problems += "hold: %.1f s, needs at least %.1f s".format(bestHold, cfg.holdS)
        if (dur < cfg.tempoMinS) problems += "tempo: too fast, %.1f s, needs at least %.1f s".format(dur, cfg.tempoMinS)
        if (dur > cfg.tempoMaxS) problems += "tempo: too slow, %.1f s, needs at most %.1f s".format(dur, cfg.tempoMaxS)
        val deliberate = peak >= cfg.restBelowDeg + 15   // a small wobble is not an attempt
        resetAttempt()
        if (!deliberate) return RepLive(a, "rest", repsDone, 0.0, null, true)
        val valid = problems.isEmpty()
        attempts += RepAttempt(s, t, peak, bestHold, valid, problems)
        if (valid) emitRep(s, t, conf)
        val msg = when {
            !valid -> "Needs adjustment: ${problems.first()}"
            repsDone >= cfg.reps -> "Meets configured criteria: $repsDone reps."
            else -> "Rep $repsDone counted."
        }
        return RepLive(a, "rest", repsDone, 0.0, msg, true)
    }

    private fun emitRep(s: Double, t: Double, conf: Double) {
        val k = repsDone
        primitives += prim("joint_angle_in_range", "rep_$k", s, null, conf)
        primitives += prim("pose_held", "rep_$k", s, t, conf)
        primitives += prim("rep_completed", "rep_$k", t, null, conf)
    }

    private fun stepHold(t: Double, angles: List<Pair<JointCriterion, Double>>, conf: Double): RepLive {
        val miss = angles.firstOrNull { (c, a) -> a !in c.min..c.max }
        if (miss != null) {
            if (holdSince != null && !held) {
                attempts += RepAttempt(holdSince!!, t, angles.first().second, t - holdSince!!, false, listOf("${miss.first.name}: %.0f deg, needs %.0f-%.0f deg".format(miss.second, miss.first.min, miss.first.max)))
            }
            holdSince = null; rangeEmitted = false
            val m = "Needs adjustment: ${miss.first.name} is %.0f deg, needs %.0f-%.0f deg.".format(miss.second, miss.first.min, miss.first.max)
            return RepLive(angles.first().second, "adjusting", if (held) 1 else 0, 0.0, if (held) live.feedback else m, true)
        }
        if (holdSince == null) holdSince = t
        val d = t - holdSince!!
        if (!rangeEmitted) { primitives += prim("joint_angle_in_range", "tree", t, null, conf); rangeEmitted = true }
        if (!held && d >= cfg.holdS) {
            held = true
            primitives += prim("pose_held", "tree", t, null, conf)
            attempts += RepAttempt(holdSince!!, t, angles.first().second, d, true, emptyList())
        }
        return RepLive(angles.first().second, if (held) "held" else "holding", if (held) 1 else 0, d, if (held) "Meets configured criteria: held %.1f s.".format(d) else null, true)
    }

    private fun prim(type: String, state: String, t: Double, tEnd: Double?, conf: Double) =
        Primitive("p${pid++}", type, t, tEnd, entity = EntityRef("body", "person"), state = state, confidence = conf)

    private fun resetAttempt() { attemptStart = null; peak = 0.0; inRangeSince = null; bestHold = 0.0 }

    /** Closed timeline for the engine (tech spec §9). */
    fun timeline(clipId: String, durationS: Double = lastT, startUtcMs: Long? = null): Timeline {
        val end = max(durationS, lastT)
        return Timeline(
            clipId, STACK, end,
            tracks = listOf(Track("body", "person", "body", 0.0, end)),
            primitives = primitives.toList(),
            observability = listOf(ObservabilityWindow("body", windows.map { listOf(it[0], it[1]) })),
            startUtcMs = startUtcMs,
        )
    }

    companion object { const val STACK = "pose-mediapipe-heavy/v1" }
}
