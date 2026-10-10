package com.application.sensorforge.sdk.pose

import com.application.sensorforge.sdk.contracts.Before
import com.application.sensorforge.sdk.contracts.EntityDef
import com.application.sensorforge.sdk.contracts.PolicyDef
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.StepDef
import com.application.sensorforge.sdk.contracts.Validator

/**
 * RepRight spec for a therapist configuration. The angle, hold and tempo numbers are checked by
 * [RepAnalyzer] (config); the spec says which primitives must be observed, in order, while the
 * body is in view. Rep k is `rep_completed` with state `rep_k`.
 */
object RepSpecs {
    fun of(cfg: RepConfig): ProcedureSpec {
        val steps = when (cfg.exercise) {
            Exercise.KNEE_EXTENSION -> (1..cfg.reps).map { k ->
                StepDef("rep_$k", "rep_completed", entity = "body", state = "rep_$k", observeTarget = "body", minConfidence = 0.6)
            }
            Exercise.TREE_POSE -> listOf(
                StepDef("angle_in_range", "joint_angle_in_range", entity = "body", state = "tree", observeTarget = "body", minConfidence = 0.6),
                StepDef("pose_held", "pose_held", entity = "body", state = "tree", observeTarget = "body", minConfidence = 0.6),
            )
        }
        val constraints = steps.zipWithNext { a, b -> Before(a.id, b.id, "developer") }
        val id = if (cfg.exercise == Exercise.KNEE_EXTENSION) "repright_knee_extension" else "repright_tree_pose"
        return ProcedureSpec(
            schemaVersion = "3.0", procedureId = id, version = 1,
            entities = listOf(EntityDef("body", "person", "track_continuity")),
            steps = steps, constraints = constraints,
            policy = PolicyDef(minObservableCoverage = 0.6, unexplainedChangeBlocksPass = false, coachingTimeoutS = 5.0),
        ).also { Validator.requireValid(it) }
    }
}
