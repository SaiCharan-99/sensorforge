package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.contracts.AnyOrder
import com.application.sensorforge.sdk.contracts.Before
import com.application.sensorforge.sdk.contracts.ConstraintDef
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.SpecExpander
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.engine.Decision
import com.application.sensorforge.sdk.engine.StepStatus
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.engine.VerifyEngine
import kotlinx.serialization.Serializable

/** A labelled recorded (or simulated) run. */
data class VerifyExample(
    val id: String,
    val planItemId: String?,
    val label: ExampleLabel,
    val split: Split,
    val session: String,
    val simulation: Boolean,
    val timeline: Timeline,
    val params: Map<String, List<String>>,
    val missingSteps: List<String> = emptyList(),
)

@Serializable
data class ScoreRow(
    val exampleId: String,
    val label: ExampleLabel,
    val actual: Verdict,
    val expected: Verdict,
    val reasons: List<String>,
    val split: Split,
    val simulation: Boolean,
)

@Serializable
data class SplitScore(
    val n: Int,
    val falseAccepts: Int,
    val falseRejects: Int,
    val wrongKind: Int,
    val correct: Int,
)

@Serializable
data class Score(val rows: List<ScoreRow>, val dev: SplitScore, val heldOut: SplitScore) {
    /** False accepts dominate (tech spec §16.3): a pack with any is not shippable. */
    val shippable get() = dev.falseAccepts == 0 && heldOut.falseAccepts == 0 && dev.n + heldOut.n > 0
}

object Scoring {
    fun evaluate(spec: ProcedureSpec, ex: VerifyExample, perceptionStack: String? = null): Decision =
        VerifyEngine.evaluate(SpecExpander.expand(spec, ex.params), ex.timeline, perceptionStack, ex.params)

    fun score(spec: ProcedureSpec, examples: List<VerifyExample>): Score {
        val rows = examples.map { ex ->
            val d = evaluate(spec, ex)
            ScoreRow(ex.id, ex.label, d.verdict, ex.label.expected, d.reasons.map { "${it.code}:${it.ref}" }, ex.split, ex.simulation)
        }
        fun of(s: Split) = rows.filter { it.split == s }.let { r ->
            SplitScore(
                r.size,
                falseAccepts = r.count { it.label != ExampleLabel.VALID && it.actual == Verdict.PASS },
                falseRejects = r.count { it.label == ExampleLabel.VALID && it.actual != Verdict.PASS },
                wrongKind = r.count { (it.label == ExampleLabel.INVALID && it.actual == Verdict.UNVERIFIED) || (it.label == ExampleLabel.INSUFFICIENT && it.actual == Verdict.FAIL) },
                correct = r.count { it.actual == it.expected },
            )
        }
        return Score(rows, of(Split.DEV), of(Split.HELD_OUT))
    }
}

// ---- Order / requirement induction (tech spec §16.3) ----

enum class InductionStatus { PROPOSED, ANY_ORDER, NEEDS_DEVELOPER }

data class ConstraintProposal(val constraint: ConstraintDef?, val a: String, val b: String, val status: InductionStatus, val support: Int, val note: String)

data class RequirementProposal(val stepId: String, val required: Boolean, val observedIn: Int, val of: Int, val needsConfirmation: Boolean)

object Induction {
    const val MIN_SUPPORT = 5

    /** Observed time of each step in each VALID dev example. */
    private fun valid(spec: ProcedureSpec, examples: List<VerifyExample>): List<Map<String, Double>> =
        examples.filter { it.label == ExampleLabel.VALID && it.split == Split.DEV }.map { ex ->
            Scoring.evaluate(spec, ex).steps.filter { it.status == StepStatus.OBSERVED && it.t != null }.associate { it.id to it.t!! }
        }

    /**
     * BEFORE only if consistently observed in ≥ [MIN_SUPPORT] dev examples; both orders seen →
     * ANY_ORDER; otherwise ask the developer (never silently invent an order).
     */
    fun order(spec: ProcedureSpec, examples: List<VerifyExample>): List<ConstraintProposal> {
        val runs = valid(spec, examples)
        val ids = Scoring.let { SpecExpander.expand(spec, Simulator.sampleParams(spec)).steps.map { it.id } }
        val existing = spec.constraints.flatMap { c -> when (c) { is Before -> listOf(setOf(c.a, c.b)); is AnyOrder -> c.steps.flatMap { x -> c.steps.map { y -> setOf(x, y) } }; else -> emptyList() } }.toSet()
        val out = mutableListOf<ConstraintProposal>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]; val b = ids[j]
            if (setOf(a, b) in existing) continue
            val both = runs.filter { a in it && b in it }
            if (both.isEmpty()) continue
            val aFirst = both.count { it.getValue(a) < it.getValue(b) }
            val bFirst = both.size - aFirst
            out += when {
                aFirst > 0 && bFirst > 0 && both.size >= MIN_SUPPORT ->
                    ConstraintProposal(AnyOrder(listOf(a, b), "induced"), a, b, InductionStatus.ANY_ORDER, both.size, "Both orders seen in $aFirst/$bFirst valid examples.")
                aFirst == both.size && both.size >= MIN_SUPPORT ->
                    ConstraintProposal(Before(a, b, "induced"), a, b, InductionStatus.PROPOSED, both.size, "'$a' before '$b' in all ${both.size} valid examples.")
                bFirst == both.size && both.size >= MIN_SUPPORT ->
                    ConstraintProposal(Before(b, a, "induced"), b, a, InductionStatus.PROPOSED, both.size, "'$b' before '$a' in all ${both.size} valid examples.")
                else -> ConstraintProposal(null, a, b, InductionStatus.NEEDS_DEVELOPER, both.size, "Only ${both.size} valid examples show both; need $MIN_SUPPORT. Ask the developer.")
            }
        }
        return out
    }

    /** Observed in all valid examples → required; otherwise optional (developer confirms). */
    fun requirements(spec: ProcedureSpec, examples: List<VerifyExample>): List<RequirementProposal> {
        val runs = valid(spec, examples)
        if (runs.isEmpty()) return emptyList()
        return SpecExpander.expand(spec, Simulator.sampleParams(spec)).steps.map { s ->
            val n = runs.count { s.id in it }
            RequirementProposal(s.id, n == runs.size, n, runs.size, needsConfirmation = n != runs.size || s.required != (n == runs.size))
        }
    }
}

// ---- Threshold calibration (calibration, not training; rule 3) ----

enum class CalibrationStatus { OK, FRAGILE, INFEASIBLE }

data class StepCalibration(
    val stepId: String,
    val threshold: Double,
    val status: CalibrationStatus,
    val positives: Int,
    val negatives: Int,
    val message: String,
)

object Calibration {
    private const val DEFAULT = 0.75

    /**
     * Per step, the best evidence confidence in positive (valid) vs negative (the step is listed
     * in `missing_steps` of an invalid/insufficient example, or the plan says it is the subject)
     * dev examples. Picks the threshold between them with zero false accepts; held-out never used.
     */
    fun calibrate(spec: ProcedureSpec, examples: List<VerifyExample>): List<StepCalibration> {
        val dev = examples.filter { it.split == Split.DEV }
        val steps = SpecExpander.expand(spec, Simulator.sampleParams(spec)).steps
        return steps.map { s ->
            fun conf(ex: VerifyExample): Double? {
                val clsOf = spec.entities.associate { it.id to it.cls }
                return ex.timeline.primitives.filter {
                    it.type == s.primitive && (s.primitive == "region_empty" || it.entity?.cls == clsOf[s.entity]) &&
                        (s.state == null || it.state == s.state) && (s.zone == null || it.zone == s.zone)
                }.maxOfOrNull { it.confidence }
            }
            val pos = dev.filter { it.label == ExampleLabel.VALID }.mapNotNull(::conf)
            val neg = dev.filter { it.label != ExampleLabel.VALID && s.id in it.missingSteps }.mapNotNull(::conf)
            val pmin = pos.minOrNull()
            val nmax = neg.maxOrNull()
            when {
                pmin == null -> StepCalibration(s.id, s.minConfidence, CalibrationStatus.FRAGILE, 0, neg.size, "No valid examples show this step yet; keeping ${s.minConfidence}.")
                nmax == null -> StepCalibration(s.id, minOf(DEFAULT, (pmin - 0.02).coerceAtLeast(0.5)), CalibrationStatus.FRAGILE, pos.size, 0, "No negative example to calibrate against; kept near the default.")
                pmin > nmax -> {
                    val gap = pmin - nmax
                    StepCalibration(s.id, (pmin + nmax) / 2, if (gap >= 0.1) CalibrationStatus.OK else CalibrationStatus.FRAGILE, pos.size, neg.size, "Separation %.2f between negatives (max %.2f) and positives (min %.2f).".format(gap, nmax, pmin))
                }
                else -> StepCalibration(s.id, s.minConfidence, CalibrationStatus.INFEASIBLE, pos.size, neg.size, "Perception can't support this step: a negative scores %.2f, a positive only %.2f.".format(nmax, pmin))
            }
        }
    }

    /** Spec with calibrated thresholds applied (infeasible steps keep their value). */
    fun apply(spec: ProcedureSpec, cal: List<StepCalibration>): ProcedureSpec {
        val by = cal.filter { it.status != CalibrationStatus.INFEASIBLE }.associateBy { it.stepId }
        return spec.copy(steps = spec.steps.map { s -> by[s.id]?.let { s.copy(minConfidence = it.threshold.coerceIn(0.0, 1.0)) } ?: s })
    }
}

// ---- Regression (every approved delta re-runs it) ----

@Serializable
data class RegressionEntry(val exampleId: String, val expected: Verdict, val summary: String)

data class RegressionDelta(val exampleId: String, val expected: Verdict, val before: Verdict, val after: Verdict) {
    val broke get() = before == expected && after != expected
    val fixed get() = before != expected && after == expected
    /** "Consequence" text shown before approval. */
    val consequence get() = when {
        broke -> "Example $exampleId was correct ($before) and would become $after."
        fixed -> "Example $exampleId would be fixed ($before → $after)."
        else -> "Example $exampleId changes $before → $after (still wrong)."
    }
}

object Regression {
    fun snapshot(spec: ProcedureSpec, examples: List<VerifyExample>): List<RegressionEntry> =
        examples.map { ex -> RegressionEntry(ex.id, ex.label.expected, Scoring.evaluate(spec, ex).verdict.name) }

    /** Differences in verdict between the approved spec and a candidate on the same examples. */
    fun deltas(approved: ProcedureSpec, candidate: ProcedureSpec, examples: List<VerifyExample>): List<RegressionDelta> =
        examples.mapNotNull { ex ->
            val b = Scoring.evaluate(approved, ex).verdict
            val a = Scoring.evaluate(candidate, ex).verdict
            if (a != b) RegressionDelta(ex.id, ex.label.expected, b, a) else null
        }

    fun breaks(deltas: List<RegressionDelta>) = deltas.filter { it.broke }
}
