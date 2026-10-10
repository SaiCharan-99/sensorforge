package com.application.sensorforge.sdk.handover

import com.application.sensorforge.sdk.engine.ReasonCode
import com.application.sensorforge.sdk.engine.StepStatus
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.engine.VerifyEngine
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-over scenarios on SIMULATION tracks (synthetic item positions and hands, not real people). */
class HandoverProofTest {
    private val fps = 10.0
    private val cfg = HandoverConfig(items = listOf("A", "B"), slipCode = "SLIP-7", minSteps = 5)

    private fun box(x: Float) = BBox(x - 0.04f, 0.46f, x + 0.04f, 0.54f)
    private fun hand(x: Float) = HandObs(listOf(Pt(x, 0.5f), Pt(x + 0.01f, 0.51f)))

    /** One item's motion: (time, x, held). Interpolated at [fps]; null x = out of view. */
    private class Path(val pts: List<Triple<Double, Float?, Boolean>>)

    private fun run(
        paths: Map<String, Path>, end: Double, slip: Pair<Double, String>? = null,
        steps: Int? = 6, loc: Float? = 8f, analyzer: HandoverAnalyzer = HandoverAnalyzer(cfg),
    ): HandoverAnalyzer {
        var t = 0.0
        while (t <= end + 1e-9) {
            val items = mutableListOf<ItemObs>(); val hands = mutableListOf<HandObs>()
            for ((code, p) in paths) {
                val seg = p.pts.lastOrNull { it.first <= t } ?: continue
                val x = seg.second ?: continue
                items += ItemObs(code, box(x))
                if (seg.third) hands += hand(x)
            }
            analyzer.add(HandoverFrame(t, true, items, hands, if (slip != null && t >= slip.first) listOf(slip.second) else emptyList()))
            if (steps != null) analyzer.onSteps(t, if (t > 2.0) steps else 0)
            if (loc != null && t >= 1.0) analyzer.onLocation(t, loc)
            t += 1 / fps
        }
        if (steps == null) analyzer.onSteps(end, 0, available = false)
        return analyzer
    }

    private fun decide(a: HandoverAnalyzer) = VerifyEngine.evaluate(HandoverSpecs.of(cfg), a.timeline("sim", startUtcMs = 0L), HandoverSpecs.STACK)

    /** Item starts at the partner side (0.2), is carried across while held, and put down on the customer side (0.8). */
    private fun handed(t0: Double) = Path(listOf(
        Triple(0.0, 0.2f, false), Triple(t0, 0.2f, true), Triple(t0 + 0.5, 0.5f, true), Triple(t0 + 1.0, 0.8f, true), Triple(t0 + 1.5, 0.8f, false),
    ))
    private val stays = Path(listOf(Triple(0.0, 0.2f, false)))

    @Test fun validHandoverPasses() {
        val a = run(mapOf("A" to handed(1.0), "B" to handed(3.0)), 8.0, slip = 6.0 to "SLIP-7")
        val d = decide(a)
        assertEquals(d.reasons.toString(), Verdict.PASS, d.verdict)
        assertEquals(2, a.handedCount)
        assertTrue(d.steps.filter { it.id.startsWith("handed_") }.all { it.status == StepStatus.OBSERVED })
    }

    @Test fun itemNotHandedFails() {
        val d = decide(run(mapOf("A" to handed(1.0), "B" to stays), 8.0, slip = 6.0 to "SLIP-7"))
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.REQUIRED_ABSENT && it.ref == "handed_B" })
    }

    @Test fun takenBackFails() {
        val back = Path(handed(3.0).pts + listOf(Triple(6.0, 0.8f, true), Triple(6.5, 0.5f, true), Triple(7.0, 0.2f, true), Triple(7.5, 0.2f, false)))
        val a = run(mapOf("A" to handed(1.0), "B" to back), 9.5, slip = 6.0 to "SLIP-7")
        val d = decide(a)
        assertEquals(ItemState.TAKEN_BACK, a.stateOf("B"))
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.REQUIRED_ABSENT && it.ref == "handed_B" })
    }

    @Test fun leftFrameMidTransferIsUnverified() {
        val gone = Path(listOf(Triple(0.0, 0.2f, false), Triple(3.0, 0.2f, true), Triple(3.5, 0.5f, true), Triple(4.0, null, false)))
        val d = decide(run(mapOf("A" to handed(1.0), "B" to gone), 8.0, slip = 6.0 to "SLIP-7"))
        assertEquals(Verdict.UNVERIFIED, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.REQUIRED_NOT_OBSERVABLE && it.ref == "handed_B" })
        assertTrue(d.reasons.none { it.code.failing })
    }

    @Test fun itemDroppedOnCustomerSideWithoutReleaseByHandStillNeedsSettle() {
        // Still held on the customer side at the end: not handed yet.
        val still = Path(listOf(Triple(0.0, 0.2f, false), Triple(1.0, 0.65f, true)))
        val a = run(mapOf("A" to still, "B" to handed(3.0)), 8.0, slip = 6.0 to "SLIP-7")
        assertEquals(1, a.handedCount)
        assertEquals(Verdict.FAIL, decide(a).verdict)
    }

    @Test fun wrongSlipFails() {
        val d = decide(run(mapOf("A" to handed(1.0), "B" to handed(3.0)), 8.0, slip = 6.0 to "SLIP-9"))
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.CODE_MISMATCH })
    }

    @Test fun slipNeverShownFails() {
        val d = decide(run(mapOf("A" to handed(1.0), "B" to handed(3.0)), 8.0))
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.REQUIRED_ABSENT && it.ref == "slip_read" })
    }

    @Test fun tooFewStepsFails() {
        val a = run(mapOf("A" to handed(1.0), "B" to handed(3.0)), 8.0, slip = 6.0 to "SLIP-7", steps = 3)
        val d = decide(a)
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.ref == "steps_walked" })
    }

    @Test fun noStepSensorIsUnverifiedNeverPass() {
        val d = decide(run(mapOf("A" to handed(1.0), "B" to handed(3.0)), 8.0, slip = 6.0 to "SLIP-7", steps = null))
        assertEquals(Verdict.UNVERIFIED, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.REQUIRED_NOT_OBSERVABLE && it.ref == "steps_walked" })
    }

    @Test fun locationIsRecordedButNeverRequired() {
        val withLoc = decide(run(mapOf("A" to handed(1.0), "B" to handed(3.0)), 8.0, slip = 6.0 to "SLIP-7", loc = 8f))
        val noLoc = decide(run(mapOf("A" to handed(1.0), "B" to handed(3.0)), 8.0, slip = 6.0 to "SLIP-7", loc = null))
        assertEquals(Verdict.PASS, withLoc.verdict)
        assertEquals(Verdict.PASS, noLoc.verdict)
        assertEquals(StepStatus.OBSERVED, withLoc.steps.first { it.id == "location_recorded" }.status)
        // Location alone never makes a pass: with nothing handed it still fails.
        assertEquals(Verdict.FAIL, decide(run(mapOf("A" to stays, "B" to stays), 8.0, slip = 6.0 to "SLIP-7", loc = 5f)).verdict)
    }

    @Test fun unusableFramesNeverPass() {
        val a = HandoverAnalyzer(cfg)
        var t = 0.0
        while (t < 6.0) { a.add(HandoverFrame(t, false)); t += 0.1 }
        assertEquals(Verdict.UNVERIFIED, decide(a).verdict)
    }

    @Test fun deterministic() {
        fun go() = decide(run(mapOf("A" to handed(1.0), "B" to stays), 8.0, slip = 6.0 to "SLIP-7"))
        assertEquals(go(), go())
    }

    @Test fun specUsesForEachAndExpands() {
        val tpl = HandoverSpecs.template(cfg)
        assertTrue(tpl.steps.any { it.forEach == "items" })
        assertEquals(listOf("handed_A", "handed_B"), HandoverSpecs.of(cfg).steps.map { it.id }.filter { it.startsWith("handed_") })
    }
}
