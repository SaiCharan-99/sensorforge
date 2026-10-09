package com.application.sensorforge.sdk.perception

import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.StepDef
import com.application.sensorforge.sdk.engine.Decision
import com.application.sensorforge.sdk.engine.StepStatus

data class CoachingPrompt(val stepId: String, val target: String, val message: String)

/**
 * Live coaching (tech spec §23): when the active required step's target has not been observable
 * for longer than `coaching_timeout_s`, prompt the user. Never touches verdict logic.
 * The active step is the first required step, in spec order, that is not yet OBSERVED.
 */
class Coach(private val spec: ProcedureSpec, private val repeatS: Double = 8.0) {
    private var lastPrompt: CoachingPrompt? = null
    private var lastAt = Double.NEGATIVE_INFINITY

    fun activeStep(decision: Decision): StepDef? {
        val status = decision.steps.associate { it.id to it.status }
        return spec.steps.firstOrNull { it.required && status[it.id] != StepStatus.OBSERVED }
    }

    fun check(decision: Decision, nowS: Double, lastObservableAt: (String) -> Double?): CoachingPrompt? {
        val step = activeStep(decision) ?: return null
        val seen = lastObservableAt(step.observeTarget) ?: 0.0
        if (nowS - seen <= spec.policy.coachingTimeoutS) return null
        val prompt = CoachingPrompt(step.id, step.observeTarget, message(step))
        if (prompt == lastPrompt && nowS - lastAt < repeatS) return null
        lastPrompt = prompt; lastAt = nowS
        return prompt
    }

    private fun message(step: StepDef): String {
        val target = step.observeTarget
        val what = target.removePrefix("zone:").replace('_', ' ')
        val cls = spec.entities.firstOrNull { it.id == target }?.cls?.replace('_', ' ') ?: what
        return when {
            step.primitive == "code_read" -> "Show the $cls label to the camera."
            target.startsWith("zone:") -> "Show the $what to the camera, nothing covering it."
            else -> "Show the $cls to the camera."
        }
    }
}
