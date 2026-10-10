package com.application.sensorforge.sdk.handover

import com.application.sensorforge.sdk.contracts.EntityDef
import com.application.sensorforge.sdk.contracts.ParameterDef
import com.application.sensorforge.sdk.contracts.PolicyDef
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.SpecExpander
import com.application.sensorforge.sdk.contracts.StepDef
import com.application.sensorforge.sdk.contracts.Validator

/**
 * HandoverProof spec (tech spec Part F): `for_each items: transferred(partner_side -> customer_side)`,
 * slip `code_read`, `steps_walked >= n`, `at_location` recorded (never required, so never sole evidence).
 * The per-item step carries the item code in `state` and observes `zone:item_<code>` (the item's own
 * visibility window), so one item leaving the frame cannot excuse another.
 */
object HandoverSpecs {
    const val STACK = "handover-hands-mediapipe+tags/v1"

    /** The un-expanded template (with `for_each`). */
    fun template(cfg: HandoverConfig): ProcedureSpec {
        val steps = buildList {
            add(StepDef("handed_{item}", "transferred", entity = "item", zone = "customer_side", state = "{item}", observeTarget = "zone:item_{item}", minConfidence = 0.6, forEach = "items"))
            add(StepDef("slip_read", "code_read", entity = "slip", observeTarget = "slip", minConfidence = 0.8))
            if (cfg.minSteps > 0) add(StepDef("steps_walked", "steps_walked", entity = "walker", observeTarget = "walker", minConfidence = 0.5))
            add(StepDef("location_recorded", "at_location", entity = "walker", observeTarget = "walker", required = false, minConfidence = 0.0))
        }
        return ProcedureSpec(
            schemaVersion = "3.0", procedureId = "handoverproof", version = 1,
            parameters = mapOf("items" to ParameterDef("list<code>", "host")),
            entities = listOf(
                EntityDef("item", "item", "track_continuity"),
                EntityDef("slip", "slip", "code_required", expectedCode = cfg.slipCode),
                EntityDef("walker", "person", "track_continuity"),
            ),
            steps = steps,
            policy = PolicyDef(minObservableCoverage = 0.9, unexplainedChangeBlocksPass = false, coachingTimeoutS = 5.0),
        ).also { Validator.requireValid(it) }
    }

    /** The expanded, validated spec the engine evaluates. */
    fun of(cfg: HandoverConfig): ProcedureSpec =
        SpecExpander.expand(template(cfg), mapOf("items" to cfg.items)).also { Validator.requireValid(it) }
}
