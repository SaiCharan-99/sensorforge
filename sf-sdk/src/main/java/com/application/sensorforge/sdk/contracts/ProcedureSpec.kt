package com.application.sensorforge.sdk.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Verify-mode procedure spec (tech spec §6). Pure Kotlin, no Android dependency. */
@Serializable
data class ProcedureSpec(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("procedure_id") val procedureId: String,
    val version: Int,
    val mode: String = "verify",
    val parameters: Map<String, ParameterDef> = emptyMap(),
    val entities: List<EntityDef> = emptyList(),
    val steps: List<StepDef>,
    val constraints: List<ConstraintDef> = emptyList(),
    val prohibited: List<ProhibitedDef> = emptyList(),
    val policy: PolicyDef,
)

@Serializable
data class ParameterDef(
    val type: String,
    val source: String,
    val optional: Boolean = false,
)

@Serializable
data class EntityDef(
    val id: String,
    val cls: String,
    @SerialName("identity_policy") val identityPolicy: String,
    val head: String? = null,
    @SerialName("expected_code") val expectedCode: String? = null,
)

@Serializable
data class StepDef(
    val id: String,
    val primitive: String,
    /** Entity id (not class); the engine resolves it to the entity's cls. */
    val entity: String? = null,
    /** Container entity id. */
    val container: String? = null,
    val zone: String? = null,
    val state: String? = null,
    /** Entity id or `zone:<name>`. */
    @SerialName("observe_target") val observeTarget: String,
    val required: Boolean = true,
    @SerialName("min_confidence") val minConfidence: Double = 0.75,
    @SerialName("for_each") val forEach: String? = null,
)

@Serializable
sealed interface ConstraintDef {
    val source: String?
}

@Serializable
@SerialName("BEFORE")
data class Before(val a: String, val b: String, override val source: String? = null) : ConstraintDef

@Serializable
@SerialName("ANY_ORDER")
data class AnyOrder(val steps: List<String>, override val source: String? = null) : ConstraintDef

@Serializable
@SerialName("SAME_IDENTITY")
data class SameIdentity(val a: String, val b: String, override val source: String? = null) : ConstraintDef

@Serializable
sealed interface ProhibitedDef

@Serializable
@SerialName("MAX_INSTANCES")
data class MaxInstances(val entity: String, val max: Int) : ProhibitedDef

@Serializable
@SerialName("FORBIDDEN_CLASS")
data class ForbiddenClass(
    val cls: String,
    @SerialName("min_confidence") val minConfidence: Double,
) : ProhibitedDef

@Serializable
data class PolicyDef(
    @SerialName("min_observable_coverage") val minObservableCoverage: Double,
    @SerialName("unexplained_change_blocks_pass") val unexplainedChangeBlocksPass: Boolean,
    @SerialName("coaching_timeout_s") val coachingTimeoutS: Double,
)
