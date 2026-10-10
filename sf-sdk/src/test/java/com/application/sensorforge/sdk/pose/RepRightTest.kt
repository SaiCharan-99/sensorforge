package com.application.sensorforge.sdk.pose

import com.application.sensorforge.sdk.engine.ReasonCode
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.engine.VerifyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** T-P01–03 logic on SIMULATION landmark sequences (synthetic stick-figure legs, not real people). */
class RepRightTest {
    private val fps = 30.0

    /** Right leg with the knee angle [deg]: hip above knee, ankle rotated so the interior angle is [deg]. */
    private fun frame(t: Double, deg: Double, vis: Float = 1f): PoseFrame {
        val lm = MutableList(Lm.COUNT) { Landmark(0.5f, 0.5f, 0f, vis) }
        val th = Math.toRadians(deg)
        lm[Lm.R_HIP] = Landmark(0.5f, 0.3f, 0f, vis)
        lm[Lm.R_KNEE] = Landmark(0.5f, 0.5f, 0f, vis)
        lm[Lm.R_ANKLE] = Landmark((0.5 + 0.2 * sin(th)).toFloat(), (0.5 - 0.2 * cos(th)).toFloat(), 0f, vis)
        return PoseFrame(t, lm)
    }

    /** Piecewise-linear knee angle track: (duration s, target deg) segments starting from [start]. */
    private fun track(start: Double, segs: List<Pair<Double, Double>>, lostFrom: Double? = null, lostTo: Double? = null): List<PoseFrame> {
        val out = mutableListOf<PoseFrame>()
        var t = 0.0; var a = start
        for ((d, target) in segs) {
            val n = (d * fps).toInt()
            for (i in 1..n) {
                val tt = t + i / fps
                val lost = lostFrom != null && tt >= lostFrom && tt < lostTo!!
                out += if (lost) PoseFrame(tt, null) else frame(tt, a + (target - a) * i / n)
            }
            t += d; a = target
        }
        return out
    }

    private fun rep(rise: Double, hold: Double, peak: Double = 175.0) = listOf(rise to peak, hold to peak, rise to 90.0, 0.5 to 90.0)

    private fun run(cfg: RepConfig, frames: List<PoseFrame>): Triple<RepAnalyzer, Verdict, List<String>> {
        val a = RepAnalyzer(cfg, aspect = 1f)
        frames.forEach { a.add(it) }
        val d = VerifyEngine.evaluate(RepSpecs.of(cfg), a.timeline("sim"))
        return Triple(a, d.verdict, d.reasons.map { "${it.code}:${it.ref}" })
    }

    private val cfg = RepConfig.kneeExtension(Side.RIGHT, reps = 3)

    @Test fun angleOfStraightAndRightLeg() {
        assertEquals(180.0, jointAngle(Landmark(0.5f, 0.3f), Landmark(0.5f, 0.5f), Landmark(0.5f, 0.7f)), 0.5)
        assertEquals(90.0, jointAngle(Landmark(0.5f, 0.3f), Landmark(0.5f, 0.5f), Landmark(0.7f, 0.5f)), 0.5)
    }

    @Test fun tP01ValidRepsCounted() {
        val frames = track(90.0, rep(2.0, 1.5) + rep(2.0, 1.5) + rep(2.0, 1.5))
        val (a, v, r) = run(cfg, frames)
        assertEquals(3, a.repsDone)
        assertEquals(r.toString(), Verdict.PASS, v)
        assertTrue(a.live.feedback!!.startsWith("Meets configured criteria"))
    }

    @Test fun tP02TooFastNeedsAdjustment() {
        val frames = track(90.0, rep(2.0, 1.5) + rep(0.5, 1.5) + rep(2.0, 1.5))
        val (a, v, r) = run(cfg, frames)
        assertEquals(2, a.repsDone)
        val bad = a.attempts.single { !it.valid }
        assertTrue(bad.problems.single(), bad.problems.single().startsWith("tempo: too fast"))
        assertEquals(Verdict.FAIL, v)
        assertTrue(r.toString(), r.contains("${ReasonCode.REQUIRED_ABSENT}:rep_3"))
    }

    @Test fun partialRangeNamesTheCriterion() {
        val frames = track(90.0, rep(2.0, 1.5) + rep(2.0, 1.5, peak = 140.0) + rep(2.0, 1.5))
        val (a, v, _) = run(cfg, frames)
        val bad = a.attempts.first { !it.valid }
        assertTrue(bad.problems.joinToString(), bad.problems.first().startsWith("range: reached 14"))
        assertEquals(Verdict.FAIL, v)
    }

    @Test fun tooShortHoldNamesTheCriterion() {
        val frames = track(90.0, rep(2.0, 0.2))
        val (a, _, _) = run(cfg.copy(reps = 1), frames)
        assertTrue(a.attempts.single().problems.any { it.startsWith("hold:") })
    }

    @Test fun tP03KeypointsLostIsUnverifiedNeverPass() {
        val frames = track(90.0, rep(2.0, 1.5) + rep(2.0, 1.5) + rep(2.0, 1.5), lostFrom = 6.0, lostTo = 20.0)
        val (a, v, r) = run(cfg, frames)
        assertTrue(a.repsDone < 3)
        assertEquals(r.toString(), Verdict.UNVERIFIED, v)
    }

    @Test fun lowVisibilityIsNotObservable() {
        val a = RepAnalyzer(cfg, aspect = 1f)
        val live = a.add(frame(0.0, 175.0, vis = 0.2f))
        assertEquals(false, live.keypointsOk)
        assertEquals("keypoints lost", live.phase)
    }

    @Test fun treePoseHeldPasses() {
        val tc = RepConfig.treePose(Side.RIGHT, holdS = 2.0)
        val frames = mutableListOf<PoseFrame>()
        for (i in 1..(4 * fps).toInt()) {
            val t = i / fps
            val lm = frame(t, 178.0).lm!!.toMutableList()
            // Left (lifted) leg: knee bent to 60 degrees.
            val th = Math.toRadians(60.0)
            lm[Lm.L_HIP] = Landmark(0.45f, 0.3f); lm[Lm.L_KNEE] = Landmark(0.45f, 0.5f)
            lm[Lm.L_ANKLE] = Landmark((0.45 + 0.2 * sin(th)).toFloat(), (0.5 - 0.2 * cos(th)).toFloat())
            frames += PoseFrame(t, lm)
        }
        val a = RepAnalyzer(tc, aspect = 1f)
        frames.forEach { a.add(it) }
        assertEquals(Verdict.PASS, VerifyEngine.evaluate(RepSpecs.of(tc), a.timeline("tree")).verdict)
    }

    @Test fun treePoseNeverHeldFails() {
        val tc = RepConfig.treePose(Side.RIGHT, holdS = 2.0)
        val a = RepAnalyzer(tc, aspect = 1f)
        for (i in 1..(4 * fps).toInt()) {
            val lm = frame(i / fps, 178.0).lm!!.toMutableList()
            lm[Lm.L_HIP] = Landmark(0.45f, 0.3f); lm[Lm.L_KNEE] = Landmark(0.45f, 0.5f); lm[Lm.L_ANKLE] = Landmark(0.45f, 0.7f)  // lifted knee never bent
            a.add(PoseFrame(i / fps, lm))
        }
        val d = VerifyEngine.evaluate(RepSpecs.of(tc), a.timeline("tree"))
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(a.live.feedback!!.startsWith("Needs adjustment: lifted_knee_bent"))
    }

    @Test fun deterministic() {
        val frames = track(90.0, rep(2.0, 1.5) + rep(2.0, 1.5))
        assertEquals(run(cfg, frames).third, run(cfg, frames).third)
        assertEquals(run(cfg, frames).first.timeline("x"), run(cfg, frames).first.timeline("x"))
    }
}
