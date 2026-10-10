package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.Fixtures
import com.application.sensorforge.sdk.contracts.AfterParam
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.engine.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeCoreTest {
    private fun spec(name: String) = Contracts.parseProcedure(Fixtures.text("specs/$name.spec.json"))
    private val names = listOf("packcheck", "sealsafe", "unboxing")

    private fun verdictOf(sp: ProcedureSpec, run: SimulatedRun) =
        Scoring.evaluate(sp, VerifyExample("e", null, ExampleLabel.VALID, Split.DEV, "s", true, run.timeline, run.params))

    /** Every scenario of every spec must give exactly the expected verdict and reason (Stage B logic). */
    @Test fun simulatorMatchesExpectedForEverySpec() {
        for (n in names) {
            val sp = spec(n)
            for (sc in Simulator.scenarios(sp)) {
                val d = verdictOf(sp, Simulator.run(sp, sc))
                assertEquals("$n/${sc.id}: ${d.reasons}", sc.expected, d.verdict)
                sc.expectedReason?.let { r -> assertTrue("$n/${sc.id} wants $r, got ${d.reasons.map { it.code }}", d.reasons.any { it.code == r }) }
            }
        }
    }

    @Test fun planHasGoodBadUnclear() {
        val plan = PlanBuilder.build(spec("packcheck"), emptyList())
        assertTrue(plan.items.any { it.kind == PlanKind.GOOD } && plan.items.any { it.kind == PlanKind.BAD } && plan.items.any { it.kind == PlanKind.UNCLEAR })
        assertTrue(plan.items.any { it.id.startsWith("missing_adapter_out") })
    }

    private fun labelOf(v: Verdict) = when (v) { Verdict.PASS -> ExampleLabel.VALID; Verdict.FAIL -> ExampleLabel.INVALID; Verdict.UNVERIFIED -> ExampleLabel.INSUFFICIENT }

    private fun examples(sp: ProcedureSpec, n: Int = 6) = Simulator.scenarios(sp).flatMap { sc ->
        (0 until if (sc.kind == ScenarioKind.VALID_A) n else 1).map { i ->
            val r = Simulator.run(sp, sc, i)
            VerifyExample("${sc.id}-$i", sc.id, labelOf(sc.expected), Split.DEV, "s$i", true, r.timeline, r.params, listOfNotNull(sc.stepId.takeIf { sc.kind == ScenarioKind.MISSING }))
        }
    }

    @Test fun scoringIsPerfectOnSimulatedExamples() {
        val sp = spec("packcheck")
        val sc = Scoring.score(sp, examples(sp))
        assertEquals(0, sc.dev.falseAccepts); assertEquals(0, sc.dev.falseRejects); assertEquals(0, sc.dev.wrongKind)
        assertTrue(sc.shippable)
    }

    @Test fun falseAcceptIsCounted() {
        val sp = spec("packcheck")
        val valid = examples(sp).first { it.label == ExampleLabel.VALID }.copy(label = ExampleLabel.INVALID)
        val s = Scoring.score(sp, listOf(valid))
        assertEquals(1, s.dev.falseAccepts); assertTrue(!s.shippable)
    }

    @Test fun orderInductionNeedsFiveAndFindsAnyOrder() {
        val sp = spec("unboxing").copy(constraints = emptyList())
        assertTrue(Induction.order(sp, examples(sp, 3)).all { it.status == InductionStatus.NEEDS_DEVELOPER })
        assertTrue(Induction.order(sp, examples(sp, 6)).any { it.status == InductionStatus.PROPOSED })
    }

    @Test fun requirementInduction() {
        val sp = spec("sealsafe")
        assertTrue(Induction.requirements(sp, examples(sp)).all { it.required })
    }

    @Test fun calibrationSeparatesAndFlagsInfeasible() {
        val sp = spec("packcheck")
        val ex = examples(sp)
        assertEquals(CalibrationStatus.FRAGILE, Calibration.calibrate(sp, ex).first { it.stepId == "adapter_out" }.status)
        val neg = ex.first { it.label == ExampleLabel.VALID }
        fun withConf(e: VerifyExample, id: String, c: Double, label: ExampleLabel) =
            e.copy(id = id, label = label, missingSteps = listOf("adapter_out"), timeline = e.timeline.copy(primitives = e.timeline.primitives.map { it.copy(confidence = c) }))
        val low = ex.filter { it.label == ExampleLabel.VALID }.map { withConf(it, it.id, 0.8, ExampleLabel.VALID).copy(missingSteps = emptyList()) }
        assertEquals(CalibrationStatus.INFEASIBLE, Calibration.calibrate(sp, low + withConf(neg, "neg", 0.99, ExampleLabel.INVALID)).first { it.stepId == "adapter_out" }.status)
        val clear = Calibration.calibrate(sp, ex + withConf(neg, "neg", 0.4, ExampleLabel.INVALID))
        assertEquals(CalibrationStatus.OK, clear.first { it.stepId == "adapter_out" }.status)
        assertTrue(Calibration.apply(sp, clear).steps.first { it.id == "adapter_out" }.minConfidence in 0.4..0.9)
    }

    @Test fun regressionFlagsBrokenExamples() {
        val sp = spec("packcheck")
        val ex = examples(sp)
        val strict = sp.copy(steps = sp.steps.map { it.copy(minConfidence = 0.99) })
        val d = Regression.deltas(sp, strict, ex)
        assertTrue(Regression.breaks(d).isNotEmpty())
        assertTrue(Regression.breaks(d).first().consequence.contains("would become"))
        assertEquals(ex.size, Regression.snapshot(sp, ex).size)
    }

    @Test fun templateCompilerDraftsSealSafe() {
        val d = TemplateCompiler.compile("sealsafe", "Show the sealed envelope, open it only after start, remove the papers from the envelope.")
        Validator.requireValid(d.spec)
        assertEquals(listOf("envelope_intact", "envelope_opened", "papers_out"), d.spec.steps.map { it.id })
        assertTrue(d.spec.constraints.any { it is AfterParam && it.step == "envelope_opened" && it.param == "start_time" })
        assertEquals("time", d.spec.parameters.getValue("start_time").type)
        for (sc in Simulator.scenarios(d.spec)) assertEquals(sc.id, sc.expected, verdictOf(d.spec, Simulator.run(d.spec, sc)).verdict)
    }

    @Test fun templateReportsUnreadClauses() {
        val d = TemplateCompiler.compile("x", "Show the box. Dance wildly.")
        assertEquals(1, d.spec.steps.size)
        assertTrue(d.warnings.any { it.contains("Dance wildly") })
    }

    @Test fun templateRemoveListIsAnyOrderAndProhibitions() {
        val d = TemplateCompiler.compile("kit", "Open the box. Remove the charger and the cable from the box. Never show impostor cable. At most one adapter.")
        Validator.requireValid(d.spec)
        assertEquals(setOf("box_opened", "charger_out", "cable_out"), d.spec.steps.map { it.id }.toSet())
        assertEquals(2, d.spec.prohibited.size)
    }

    @Test fun profileDerivation() {
        assertTrue("zones" in TemplateCompiler.profileOf(spec("packcheck")) && "codes" in TemplateCompiler.profileOf(spec("packcheck")))
        assertTrue("state" in TemplateCompiler.profileOf(spec("sealsafe")))
    }
}
