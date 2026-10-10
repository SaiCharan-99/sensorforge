package com.application.sensorforge.sdk.scene

import com.application.sensorforge.sdk.perception.BBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {
    private val rules = SceneRules("box", inside = listOf("charger"), outside = listOf("cable"))
    private fun o(l: String, b: BBox) = SceneObject(l, b, 0.9, SceneObject.Source.HEAD)
    private val box = o("box", BBox(0.2f, 0.2f, 0.8f, 0.8f))

    @Test fun passWhenInsideAndOutsideAsExpected() {
        val v = SceneJudge.judge(listOf(box, o("charger", BBox(0.3f, 0.3f, 0.5f, 0.5f)), o("cable", BBox(0.85f, 0.1f, 0.98f, 0.3f))), rules)
        assertEquals(Verdict.PASS, v.verdict)
    }

    @Test fun failWhenItemIsOutsideButShouldBeInside() {
        val v = SceneJudge.judge(listOf(box, o("charger", BBox(0.85f, 0.3f, 0.97f, 0.5f)), o("cable", BBox(0.85f, 0.1f, 0.98f, 0.3f))), rules)
        assertEquals(Verdict.FAIL, v.verdict)
        assertTrue(v.reasons.first().contains("charger"))
    }

    @Test fun failWhenForbiddenItemIsInside() {
        val v = SceneJudge.judge(listOf(box, o("charger", BBox(0.3f, 0.3f, 0.5f, 0.5f)), o("cable", BBox(0.4f, 0.5f, 0.6f, 0.7f))), rules)
        assertEquals(Verdict.FAIL, v.verdict)
    }

    @Test fun missingItemOrContainerIsUnverifiedNeverPass() {
        assertEquals(Verdict.UNVERIFIED, SceneJudge.judge(listOf(box, o("cable", BBox(0.85f, 0.1f, 0.98f, 0.3f))), rules).verdict)
        assertEquals(Verdict.UNVERIFIED, SceneJudge.judge(listOf(o("charger", BBox(0.3f, 0.3f, 0.5f, 0.5f))), rules).verdict)
    }

    @Test fun halfOverlapIsInsideAndJustUnderIsOutside() {
        val b = BBox(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(0.5, SceneJudge.overlap(BBox(0.7f, 0.3f, 0.9f, 0.5f), b), 1e-4)
        assertTrue(SceneJudge.overlap(BBox(0.75f, 0.3f, 0.95f, 0.5f), b) < 0.5)
    }

    @Test fun smootherNeedsConsecutiveAgreement() {
        val s = SceneSmoother(3)
        assertEquals(Verdict.UNVERIFIED, s.add(Verdict.PASS))
        assertEquals(Verdict.UNVERIFIED, s.add(Verdict.PASS))
        assertEquals(Verdict.PASS, s.add(Verdict.PASS))
        assertEquals(Verdict.UNVERIFIED, s.add(Verdict.FAIL))
    }

    @Test fun llmReplyParsing() {
        val p = SceneNarrator.parse("VERDICT: fail\nREASON: The charger is outside the box.")
        assertNotNull(p); assertEquals(Verdict.FAIL, p!!.verdict); assertTrue(p.reason.contains("charger"))
        assertNull(SceneNarrator.parse("I think it looks fine"))
    }

    @Test fun promptStatesRulesAndOverlap() {
        val t = SceneNarrator.prompt(listOf(box, o("charger", BBox(0.3f, 0.3f, 0.5f, 0.5f))), rules)
        assertTrue(t.contains("100% of it lies inside")); assertTrue(t.contains("'cable' was NOT detected"))
    }
}
