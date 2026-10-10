package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.contracts.AfterParam
import com.application.sensorforge.sdk.contracts.Before
import com.application.sensorforge.sdk.contracts.EntityRef
import com.application.sensorforge.sdk.contracts.ForbiddenClass
import com.application.sensorforge.sdk.contracts.MaxInstances
import com.application.sensorforge.sdk.contracts.ObservabilityWindow
import com.application.sensorforge.sdk.contracts.Primitive
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.SpecExpander
import com.application.sensorforge.sdk.contracts.StepDef
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Track
import com.application.sensorforge.sdk.engine.ReasonCode
import com.application.sensorforge.sdk.engine.Verdict

/**
 * Scenario catalogue shared by the example Plan and by SIMULATION capture. A scenario is a
 * way a real run can go; [expected] is what a correct verifier must say (tech spec §25).
 */
enum class ScenarioKind { VALID_A, VALID_B, MISSING, ORDER_SWAP, COVERED, EARLY_START, IMPOSTOR, DUPLICATE, CODE_MISMATCH, NEVER_SHOWN }

data class Scenario(
    val kind: ScenarioKind,
    /** Step the scenario is about (missing / covered / swapped second step). */
    val stepId: String? = null,
    val otherStepId: String? = null,
    val expected: Verdict,
    val expectedReason: ReasonCode?,
) {
    val id: String get() = listOfNotNull(kind.name.lowercase(), stepId, otherStepId).joinToString("_")
}

/** A synthetic run: timeline + the host parameter values it was made for. */
data class SimulatedRun(val timeline: Timeline, val params: Map<String, List<String>>)

/**
 * Spec-driven synthetic timelines (all labelled SIMULATION by callers, rule 2). Used by the
 * Plan, by the Forge "Simulate capture" button and by the Stage B tests. Deterministic.
 */
object Simulator {
    const val START_UTC_MS = 1_760_000_000_000L
    private const val STEP_GAP = 2.0
    private const val SAMPLE_ITEMS = "A,B,C"

    /** Params for a spec: sample `list<code>` items, a start time before every AFTER_PARAM step. */
    fun sampleParams(spec: ProcedureSpec, lateStart: Boolean = false): Map<String, List<String>> =
        spec.parameters.mapValues { (_, d) ->
            when (d.type) {
                "time" -> listOf((START_UTC_MS + if (lateStart) 3_600_000L else 500L).toString())
                else -> SAMPLE_ITEMS.split(',')
            }
        }

    /** Spec with `for_each` expanded for the sample items (the form the engine runs). */
    fun expanded(spec: ProcedureSpec): ProcedureSpec = SpecExpander.expand(spec, sampleParams(spec).filterValues { true })

    /** Scenarios that apply to an expanded spec, with their expected outcome. */
    fun scenarios(spec: ProcedureSpec): List<Scenario> {
        val s = expanded(spec)
        val required = s.steps.filter { it.required }
        val out = mutableListOf(Scenario(ScenarioKind.VALID_A, expected = Verdict.PASS, expectedReason = null))
        val anyOrder = s.constraints.filterIsInstance<com.application.sensorforge.sdk.contracts.AnyOrder>().firstOrNull()
        if (anyOrder != null && anyOrder.steps.size >= 2) out += Scenario(ScenarioKind.VALID_B, expected = Verdict.PASS, expectedReason = null)
        required.forEach { out += Scenario(ScenarioKind.MISSING, it.id, expected = Verdict.FAIL, expectedReason = ReasonCode.REQUIRED_ABSENT) }
        s.constraints.filterIsInstance<Before>().forEach { out += Scenario(ScenarioKind.ORDER_SWAP, it.a, it.b, Verdict.FAIL, ReasonCode.ORDER_VIOLATED) }
        required.forEach { out += Scenario(ScenarioKind.COVERED, it.id, expected = Verdict.UNVERIFIED, expectedReason = ReasonCode.REQUIRED_NOT_OBSERVABLE) }
        s.constraints.filterIsInstance<AfterParam>().forEach { out += Scenario(ScenarioKind.EARLY_START, it.step, expected = Verdict.FAIL, expectedReason = ReasonCode.ORDER_VIOLATED) }
        s.prohibited.filterIsInstance<ForbiddenClass>().forEach { out += Scenario(ScenarioKind.IMPOSTOR, it.cls, expected = Verdict.FAIL, expectedReason = ReasonCode.PROHIBITED_OBSERVED) }
        s.prohibited.filterIsInstance<MaxInstances>().forEach { out += Scenario(ScenarioKind.DUPLICATE, it.entity, expected = Verdict.FAIL, expectedReason = ReasonCode.PROHIBITED_OBSERVED) }
        s.steps.filter { it.primitive == "code_read" }.forEach { out += Scenario(ScenarioKind.CODE_MISMATCH, it.id, expected = Verdict.FAIL, expectedReason = ReasonCode.CODE_MISMATCH) }
        out += Scenario(ScenarioKind.NEVER_SHOWN, expected = Verdict.UNVERIFIED, expectedReason = ReasonCode.REQUIRED_NOT_OBSERVABLE)
        // CODE_MISMATCH only meaningful when the entity expects a code.
        return out.filter { sc ->
            sc.kind != ScenarioKind.CODE_MISMATCH ||
                s.entities.firstOrNull { e -> e.id == s.steps.first { it.id == sc.stepId }.entity }?.expectedCode != null
        }
    }

    fun run(spec: ProcedureSpec, sc: Scenario, variant: Int = 0): SimulatedRun {
        val s = expanded(spec)
        val params = sampleParams(spec, lateStart = sc.kind == ScenarioKind.EARLY_START)
        val order = order(s, reverseAny = sc.kind == ScenarioKind.VALID_B)
        val times = order.mapIndexed { i, st -> st.id to 2.0 + STEP_GAP * i }.toMap().toMutableMap()
        if (sc.kind == ScenarioKind.ORDER_SWAP) {
            val a = times.getValue(sc.stepId!!); val b = times.getValue(sc.otherStepId!!)
            times[sc.stepId] = b; times[sc.otherStepId] = a
        }
        val duration = 2.0 + STEP_GAP * (order.size + 1)
        val classes = (s.entities.map { it.cls } + s.steps.mapNotNull { it.container }.mapNotNull { c -> s.entities.firstOrNull { it.id == c }?.cls }).distinct()
        val tracks = classes.map { Track("t_$it", it, "i_$it", 0.0, duration, s.entities.firstOrNull { e -> e.cls == it }?.expectedCode) }.toMutableList()
        val clsOf = s.entities.associate { it.id to it.cls }

        val prims = mutableListOf<Primitive>()
        var n = 0
        val conf = 0.88 + 0.02 * (variant % 4)
        for (st in order) {
            if (sc.kind == ScenarioKind.MISSING && st.id == sc.stepId) continue
            if (sc.kind == ScenarioKind.COVERED && st.id == sc.stepId) continue
            if (sc.kind == ScenarioKind.NEVER_SHOWN) continue
            prims += primitiveFor(st, "p${n++}", times.getValue(st.id), conf, clsOf, s, wrongCode = sc.kind == ScenarioKind.CODE_MISMATCH && st.id == sc.stepId)
        }
        if (sc.kind == ScenarioKind.IMPOSTOR) {
            tracks += Track("t_${sc.stepId}", sc.stepId!!, "i_${sc.stepId}", 1.0, duration)
            prims += Primitive("p${n++}", "appears", 4.0, entity = EntityRef("t_${sc.stepId}", sc.stepId), confidence = 0.95)
        }
        if (sc.kind == ScenarioKind.DUPLICATE) {
            val cls = clsOf.getValue(sc.stepId!!)
            tracks += Track("t_${cls}_dup", cls, "i_${cls}_dup", 1.0, duration, tracks.first { it.cls == cls }.code)
        }

        val targets = s.steps.map { it.observeTarget }.distinct()
        val hidden = if (sc.kind == ScenarioKind.COVERED) s.steps.first { it.id == sc.stepId }.observeTarget else null
        val obs = targets.map { tg ->
            val windows = if (tg == hidden || sc.kind == ScenarioKind.NEVER_SHOWN) emptyList() else listOf(listOf(0.0, duration))
            ObservabilityWindow(tg, windows)
        }
        val tl = Timeline("sim-${sc.id}-$variant", "simulation", duration, tracks, prims, obs, START_UTC_MS)
        return SimulatedRun(tl, params)
    }

    private fun primitiveFor(st: StepDef, id: String, t: Double, conf: Double, clsOf: Map<String, String>, spec: ProcedureSpec, wrongCode: Boolean): Primitive {
        val cls = st.entity?.let { clsOf[it] }
        val ref = cls?.let { EntityRef("t_$it", it) }
        val container = st.container?.let { clsOf[it] }
        return when (st.primitive) {
            "code_read" -> Primitive(id, st.primitive, t, entity = ref, code = if (wrongCode) "WRONG-0" else spec.entities.first { it.id == st.entity }.expectedCode ?: "SIM-1", confidence = conf)
            else -> Primitive(id, st.primitive, t, entity = ref, container = container, zone = st.zone, state = st.state, confidence = conf)
        }
    }

    /** Steps in an order that satisfies every BEFORE; ANY_ORDER siblings are reversed for variant B. */
    fun order(spec: ProcedureSpec, reverseAny: Boolean = false): List<StepDef> {
        val befores = spec.constraints.filterIsInstance<Before>()
        val remaining = spec.steps.toMutableList()
        val done = mutableSetOf<String>()
        val out = mutableListOf<StepDef>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { s -> befores.none { it.b == s.id && it.a !in done && remaining.any { r -> r.id == it.a } } }
            val pick = if (reverseAny) ready.lastOrNull() else ready.first()
            val next = pick ?: remaining.first()
            remaining.remove(next); done += next.id; out += next
        }
        return out
    }
}
