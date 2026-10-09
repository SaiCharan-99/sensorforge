package com.application.sensorforge.sdk.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Detect-mode policy (tech spec §7, CrashGuard). */
@Serializable
data class DetectSpec(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("procedure_id") val procedureId: String,
    val version: Int,
    val mode: String,
    val window: WindowDef,
    val gate: GateDef,
    val head: DetectHeadDef,
    val corroboration: CorroborationDef,
    val incident: IncidentDef,
    val evidence: EvidenceDef,
)

@Serializable
data class WindowDef(
    @SerialName("length_s") val lengthS: Double,
    @SerialName("hop_s") val hopS: Double,
    @SerialName("imu_hz") val imuHz: Int,
)

@Serializable
data class GateDef(
    @SerialName("accel_peak_g") val accelPeakG: Double,
    @SerialName("gyro_peak_dps") val gyroPeakDps: Double,
)

@Serializable
data class DetectHeadDef(
    val id: String,
    @SerialName("positive_class") val positiveClass: String,
    @SerialName("min_prob") val minProb: Double,
)

@Serializable
data class CorroborationDef(
    @SerialName("post_event_stillness_s") val postEventStillnessS: Double,
    @SerialName("stillness_max_g_std") val stillnessMaxGStd: Double,
)

@Serializable
data class IncidentDef(
    @SerialName("countdown_s") val countdownS: Int,
    @SerialName("auto_escalate") val autoEscalate: Boolean,
    val transport: String,
)

@Serializable
data class EvidenceDef(
    @SerialName("imu_ring_buffer_s") val imuRingBufferS: Int,
    val location: String,
)
