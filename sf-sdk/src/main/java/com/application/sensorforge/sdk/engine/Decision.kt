package com.application.sensorforge.sdk.engine

import kotlinx.serialization.Serializable

@Serializable
enum class Verdict { PASS, FAIL, UNVERIFIED }

@Serializable
enum class StepStatus { OBSERVED, ABSENT, NOT_OBSERVABLE }

/** Declaration order is the reason sort order (tech spec §15). */
@Serializable
enum class ReasonCode(val failing: Boolean) {
    REQUIRED_ABSENT(true),
    REQUIRED_NOT_OBSERVABLE(false),
    CODE_MISMATCH(true),
    ORDER_VIOLATED(true),
    IDENTITY_MISMATCH(true),
    IDENTITY_UNPROVEN(false),
    PROHIBITED_OBSERVED(true),
    PROHIBITED_UNDETERMINED(false),
    UNEXPLAINED_CHANGE(false),
    PERCEPTION_STACK_MISMATCH(false),
}

@Serializable
data class Reason(val code: ReasonCode, val ref: String, val message: String)

@Serializable
data class StepResult(
    val id: String,
    val status: StepStatus,
    val required: Boolean,
    /** Time of the chosen evidence (OBSERVED only). */
    val t: Double? = null,
    val primitiveId: String? = null,
    val evidenceFrames: List<Long> = emptyList(),
    /** Observable coverage of the step's target over its eligible interval (no-candidate case). */
    val coverage: Double? = null,
)

@Serializable
data class Decision(
    val verdict: Verdict,
    val reasons: List<Reason>,
    val steps: List<StepResult>,
)
