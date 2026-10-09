package com.application.sensorforge.sdk.engine

import com.application.sensorforge.sdk.contracts.AnyOrder
import com.application.sensorforge.sdk.contracts.Before
import com.application.sensorforge.sdk.contracts.ForbiddenClass
import com.application.sensorforge.sdk.contracts.MaxInstances
import com.application.sensorforge.sdk.contracts.Primitive
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.SameIdentity
import com.application.sensorforge.sdk.contracts.StepDef
import com.application.sensorforge.sdk.contracts.Timeline

/**
 * Verify engine (tech spec §11–§15). Pure, deterministic: same spec + timeline → same Decision.
 * Expects an expanded (no `for_each`), validated spec and a validated timeline.
 */
object VerifyEngine {

    fun evaluate(spec: ProcedureSpec, timeline: Timeline, expectedPerceptionStack: String? = null): Decision {
        if (expectedPerceptionStack != null && timeline.perceptionStack != expectedPerceptionStack) {
            // Fail closed: perception we can't vouch for yields no evidence at all.
            val reason = Reason(
                ReasonCode.PERCEPTION_STACK_MISMATCH, timeline.perceptionStack,
                "Timeline came from perception stack '${timeline.perceptionStack}', pack expects '$expectedPerceptionStack'.",
            )
            val steps = spec.steps.map { StepResult(it.id, StepStatus.NOT_OBSERVABLE, it.required) }
            return Decision(Verdict.UNVERIFIED, listOf(reason), steps)
        }
        return Evaluation(spec, timeline).run()
    }

    private class Evaluation(val spec: ProcedureSpec, val timeline: Timeline) {
        val clsOfEntity = spec.entities.associate { it.id to it.cls }
        val entityById = spec.entities.associateBy { it.id }
        val identity = IdentityGraph(timeline)
        val trackById = timeline.tracks.associateBy { it.trackId }
        val primitives = timeline.primitives.sortedWith(compareBy({ it.t }, { it.id }))

        val unstable = Intervals.of(primitives.filter { it.type == "camera_unstable" }.map { it.t to (it.tEnd ?: it.t) })
        val observable: Map<String, Intervals> = timeline.observability
            .groupBy { it.target }
            .mapValues { (_, ws) -> Intervals.of(ws.flatMap { w -> w.intervals.map { it[0] to it[1] } }) }

        val befores = spec.constraints.filterIsInstance<Before>()
        val results = mutableMapOf<String, StepResult>()
        val chosen = mutableMapOf<String, Primitive>()
        val reasons = mutableListOf<Reason>()

        fun observableAt(target: String, t: Double) =
            (observable[target] ?: Intervals.EMPTY).contains(t) && !unstable.contains(t)

        fun matches(p: Primitive, s: StepDef): Boolean {
            if (p.type != s.primitive) return false
            if (s.primitive != "region_empty" && p.entity?.cls != clsOfEntity[s.entity]) return false
            if (s.container != null && p.container != clsOfEntity[s.container]) return false
            if (s.zone != null && p.zone != s.zone) return false
            if (s.state != null && p.state != s.state) return false
            return true
        }

        fun trustworthy(p: Primitive, s: StepDef) =
            observableAt(s.observeTarget, p.t) &&
                p.confidence >= s.minConfidence &&
                // A code_read without a decoded value is not evidence of anything.
                (s.primitive != "code_read" || codeOf(p) != null)

        fun codeOf(p: Primitive): String? = p.code ?: p.entity?.let { trackById[it.trackId]?.code }

        fun run(): Decision {
            topoOrder().forEach { evaluateStep(it) }
            checkConstraints()
            checkProhibited()
            checkUnexplainedChange()

            val sorted = reasons.distinct().sortedWith(compareBy({ it.code.ordinal }, { it.ref }))
            val verdict = when {
                sorted.any { it.code.failing } -> Verdict.FAIL
                sorted.isNotEmpty() -> Verdict.UNVERIFIED
                else -> Verdict.PASS
            }
            return Decision(verdict, sorted, spec.steps.map { results.getValue(it.id) })
        }

        fun evaluateStep(s: StepDef) {
            val candidates = primitives.filter { matches(it, s) }
            val evidence = candidates.firstOrNull { trustworthy(it, s) }
            val result = when {
                evidence != null -> {
                    chosen[s.id] = evidence
                    StepResult(s.id, StepStatus.OBSERVED, s.required, evidence.t, evidence.id, evidence.evidenceFrames)
                }
                candidates.isNotEmpty() -> StepResult(s.id, StepStatus.NOT_OBSERVABLE, s.required)
                else -> {
                    val coverage = coverage(s)
                    val status = if (coverage >= spec.policy.minObservableCoverage) StepStatus.ABSENT else StepStatus.NOT_OBSERVABLE
                    StepResult(s.id, status, s.required, coverage = coverage)
                }
            }
            results[s.id] = result

            if (s.required) when (result.status) {
                StepStatus.ABSENT -> reasons += Reason(ReasonCode.REQUIRED_ABSENT, s.id, "Required step '${s.id}' did not happen while it was in view.")
                StepStatus.NOT_OBSERVABLE -> reasons += Reason(ReasonCode.REQUIRED_NOT_OBSERVABLE, s.id, "Required step '${s.id}' could not be seen clearly enough to verify.")
                StepStatus.OBSERVED -> Unit
            }

            val expected = entityById[s.entity]?.expectedCode
            if (s.primitive == "code_read" && evidence != null && expected != null && codeOf(evidence) != expected) {
                reasons += Reason(ReasonCode.CODE_MISMATCH, s.id, "Read code '${codeOf(evidence)}', expected '$expected'.")
            }
        }

        /** Coverage of the step's target from the latest observed BEFORE-predecessor (else 0) to clip end. */
        fun coverage(s: StepDef): Double {
            val start = befores.filter { it.b == s.id }
                .mapNotNull { results[it.a]?.takeIf { r -> r.status == StepStatus.OBSERVED }?.t }
                .maxOrNull() ?: 0.0
            val end = timeline.durationS
            if (end <= start) return 0.0
            val seen = (observable[s.observeTarget] ?: Intervals.EMPTY).minus(unstable).clip(start, end)
            return seen.length() / (end - start)
        }

        fun checkConstraints() {
            for (c in spec.constraints) when (c) {
                is Before -> {
                    val ta = results[c.a]?.t
                    val tb = results[c.b]?.t
                    if (ta != null && tb != null && ta > tb) {
                        reasons += Reason(ReasonCode.ORDER_VIOLATED, "${c.a}->${c.b}", "'${c.a}' must happen before '${c.b}'.")
                    }
                }
                is SameIdentity -> {
                    val ia = chosen[c.a]?.entity?.let { identity.identityOf(it.trackId) }
                    val ib = chosen[c.b]?.entity?.let { identity.identityOf(it.trackId) }
                    if (ia != null && ib != null && ia != ib) {
                        if (identity.sameComponent(ia, ib)) {
                            reasons += Reason(ReasonCode.IDENTITY_UNPROVEN, "${c.a}=${c.b}", "Identity trail broken: can't prove '${c.a}' and '${c.b}' are the same object.")
                        } else {
                            reasons += Reason(ReasonCode.IDENTITY_MISMATCH, "${c.a}=${c.b}", "'${c.a}' and '${c.b}' involve different objects.")
                        }
                    }
                }
                is AnyOrder -> Unit
            }
        }

        fun checkProhibited() {
            for (p in spec.prohibited) when (p) {
                is MaxInstances -> {
                    val cls = clsOfEntity.getValue(p.entity)
                    val (minPossible, maxPossible) = identity.instanceBounds(cls)
                    if (minPossible > p.max) {
                        reasons += Reason(ReasonCode.PROHIBITED_OBSERVED, p.entity, "More than ${p.max} '${p.entity}' seen.")
                    } else if (p.max < maxPossible) {
                        reasons += Reason(ReasonCode.PROHIBITED_UNDETERMINED, p.entity, "Can't rule out more than ${p.max} '${p.entity}'.")
                    }
                }
                is ForbiddenClass -> {
                    val seen = primitives.any { it.entity?.cls == p.cls && it.confidence >= p.minConfidence && !unstable.contains(it.t) }
                    if (seen) reasons += Reason(ReasonCode.PROHIBITED_OBSERVED, p.cls, "Forbidden '${p.cls}' seen.")
                }
            }
        }

        fun checkUnexplainedChange() {
            if (!spec.policy.unexplainedChangeBlocksPass) return
            primitives.filter { it.type == "unexplained_change" && !unstable.contains(it.t) }.forEach {
                reasons += Reason(ReasonCode.UNEXPLAINED_CHANGE, it.id, "Unexplained change in the scene at ${it.t}s.")
            }
        }

        /** Steps ordered so BEFORE-predecessors are evaluated first; ties keep spec order. */
        fun topoOrder(): List<StepDef> {
            val remaining = spec.steps.toMutableList()
            val done = mutableSetOf<String>()
            val out = mutableListOf<StepDef>()
            while (remaining.isNotEmpty()) {
                val next = remaining.first { s -> befores.none { it.b == s.id && it.a !in done && remaining.any { r -> r.id == it.a } } }
                remaining.remove(next)
                done += next.id
                out += next
            }
            return out
        }
    }
}
