package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.engine.Verdict
import kotlinx.serialization.Serializable

/** Example labels (tech spec §16.3). */
@Serializable
enum class ExampleLabel(val expected: Verdict) { VALID(Verdict.PASS), INVALID(Verdict.FAIL), INSUFFICIENT(Verdict.UNVERIFIED) }

@Serializable
enum class Split { DEV, HELD_OUT }

@Serializable
enum class PlanKind { GOOD, BAD, UNCLEAR }

/** What an item captures: a whole run (timeline), crops for a head class, or IMU windows. */
@Serializable
enum class CaptureKind { RUN, CROP, IMU }

/**
 * One line of the example plan (ui-design S02b). Capture is driven by these items.
 * [minCount] is what Train / Test need; [targetCount] is what the plan asks for.
 */
@Serializable
data class PlanItem(
    val id: String,
    val kind: PlanKind,
    val title: String,
    val instruction: String,
    val capture: CaptureKind,
    val label: ExampleLabel?,
    val expectedVerdict: Verdict?,
    val expectedReason: String? = null,
    /** Head class for CROP / IMU items. */
    val headClass: String? = null,
    val scenario: String? = null,
    val stepId: String? = null,
    val minCount: Int = 1,
    val targetCount: Int = 5,
    val needsHeldOut: Boolean = true,
    /** Developer-added or edited. */
    val custom: Boolean = false,
)

@Serializable
data class Plan(
    val items: List<PlanItem>,
    /** Configuration the use case needs before capture (start time, item list, angles...). */
    val config: List<String> = emptyList(),
    val accepted: Boolean = false,
)

/** Head a use case trains (H1/H2/H3). */
data class HeadPlan(
    val headId: String,
    val slot: String,
    val classes: List<String>,
    val capture: CaptureKind,
    val howTo: Map<String, String>,
    /** Classes whose data cannot be captured live (replay fixtures only), e.g. impact_like. */
    val replayOnly: Set<String> = emptySet(),
    val kindOf: (String) -> PlanKind = { PlanKind.GOOD },
)

object PlanBuilder {
    const val DEMO_MIN = 5

    /**
     * Plan from the spec's scenarios (valid variants, hard negatives, unclear) plus head classes.
     * Texts follow docs/data-requirements.md.
     */
    fun build(spec: ProcedureSpec?, heads: List<HeadPlan>, config: List<String> = emptyList()): Plan {
        val items = mutableListOf<PlanItem>()
        if (spec != null) {
            val seq = Simulator.order(Simulator.expanded(spec)).joinToString(", then ") { describe(it.id, spec) }
            for (sc in Simulator.scenarios(spec)) {
                val (kind, label) = when (sc.expected) {
                    Verdict.PASS -> PlanKind.GOOD to ExampleLabel.VALID
                    Verdict.FAIL -> PlanKind.BAD to ExampleLabel.INVALID
                    Verdict.UNVERIFIED -> PlanKind.UNCLEAR to ExampleLabel.INSUFFICIENT
                }
                items += PlanItem(
                    id = sc.id, kind = kind, title = title(sc), instruction = instruction(sc, seq),
                    capture = CaptureKind.RUN, label = label, expectedVerdict = sc.expected,
                    expectedReason = sc.expectedReason?.name, scenario = sc.id, stepId = sc.stepId,
                    minCount = 1, targetCount = if (kind == PlanKind.GOOD) DEMO_MIN else 3,
                    needsHeldOut = kind == PlanKind.GOOD,
                )
            }
        }
        for (h in heads) for (c in h.classes) {
            val replay = c in h.replayOnly
            items += PlanItem(
                id = "head_${h.headId}_$c", kind = h.kindOf(c),
                title = "${h.slot} class: $c" + if (replay) " (replay fixtures)" else "",
                instruction = h.howTo[c] ?: "Capture examples of '$c'.",
                capture = h.capture, label = null, expectedVerdict = null, headClass = c,
                minCount = if (replay) 0 else DEMO_MIN, targetCount = if (replay) 0 else DEMO_MIN + 3, needsHeldOut = !replay,
            )
        }
        return Plan(items, config)
    }

    private fun describe(stepId: String, spec: ProcedureSpec): String {
        val st = spec.steps.firstOrNull { it.id == stepId } ?: return stepId.replace('_', ' ')
        return stepId.replace('_', ' ') + (st.state?.let { " ($it)" } ?: "")
    }

    private fun title(sc: Scenario) = when (sc.kind) {
        ScenarioKind.VALID_A -> "Correct run"
        ScenarioKind.VALID_B -> "Correct run, other order"
        ScenarioKind.MISSING -> "Missing: ${sc.stepId}"
        ScenarioKind.ORDER_SWAP -> "Out of order: ${sc.stepId} after ${sc.otherStepId}"
        ScenarioKind.COVERED -> "Covered: ${sc.stepId} hidden from the camera"
        ScenarioKind.EARLY_START -> "Too early: ${sc.stepId} before the start time"
        ScenarioKind.IMPOSTOR -> "Forbidden item: ${sc.stepId}"
        ScenarioKind.DUPLICATE -> "Duplicate ${sc.stepId}"
        ScenarioKind.CODE_MISMATCH -> "Wrong code at ${sc.stepId}"
        ScenarioKind.NEVER_SHOWN -> "Nothing shown to the camera"
    }

    private fun instruction(sc: Scenario, seq: String) = when (sc.kind) {
        ScenarioKind.VALID_A -> "Do it correctly: $seq."
        ScenarioKind.VALID_B -> "Do it correctly but take the independent items out in the other order."
        ScenarioKind.MISSING -> "Do everything except '${sc.stepId}'. Keep the camera on the scene to the end."
        ScenarioKind.ORDER_SWAP -> "Do '${sc.otherStepId}' first, then '${sc.stepId}'."
        ScenarioKind.COVERED -> "Do the run but cover the lens or the contents with your hand during '${sc.stepId}'."
        ScenarioKind.EARLY_START -> "Do '${sc.stepId}' before the configured start time."
        ScenarioKind.IMPOSTOR -> "Show the look-alike '${sc.stepId}' as well as the real items."
        ScenarioKind.DUPLICATE -> "Bring in a second '${sc.stepId}'."
        ScenarioKind.CODE_MISMATCH -> "Show a label with the wrong code at '${sc.stepId}'."
        ScenarioKind.NEVER_SHOWN -> "Record with the scene out of view or the lens covered the whole time."
    }

    /** Counts by plan item id against minimums. */
    data class Progress(val item: PlanItem, val dev: Int, val heldOut: Int) {
        val met get() = dev >= item.minCount && (!item.needsHeldOut || item.targetCount == 0 || heldOut >= 1 || item.kind != PlanKind.GOOD && item.capture == CaptureKind.RUN)
        val devMet get() = dev >= item.minCount
    }

    /** Missing items for the gate message, e.g. "charger: 2/5". */
    fun missing(progress: List<Progress>): List<String> =
        progress.filter { !it.devMet }.map { "${it.item.title}: ${it.dev}/${it.item.minCount}" }

    /** Developer edits (add / replace / remove). Plan stays unaccepted after an edit. */
    fun withItem(plan: Plan, item: PlanItem) =
        plan.copy(items = plan.items.filter { it.id != item.id } + item.copy(custom = true), accepted = false)

    fun withoutItem(plan: Plan, id: String) = plan.copy(items = plan.items.filter { it.id != id }, accepted = false)
}
