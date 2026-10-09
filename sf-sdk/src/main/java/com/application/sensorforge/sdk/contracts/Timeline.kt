package com.application.sensorforge.sdk.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Observation timeline: the verifier's only input (tech spec §9). */
@Serializable
data class Timeline(
    @SerialName("clip_id") val clipId: String,
    @SerialName("perception_stack") val perceptionStack: String,
    @SerialName("duration_s") val durationS: Double,
    val tracks: List<Track> = emptyList(),
    val primitives: List<Primitive> = emptyList(),
    val observability: List<ObservabilityWindow> = emptyList(),
    /** Wall clock at t = 0 (UTC epoch ms). Needed only by AFTER_PARAM. */
    @SerialName("start_utc_ms") val startUtcMs: Long? = null,
)

@Serializable
data class Track(
    @SerialName("track_id") val trackId: String,
    val cls: String,
    @SerialName("identity_id") val identityId: String,
    @SerialName("t_start") val tStart: Double,
    @SerialName("t_end") val tEnd: Double,
    val code: String? = null,
)

@Serializable
data class EntityRef(
    @SerialName("track_id") val trackId: String,
    val cls: String,
)

@Serializable
data class Primitive(
    val id: String,
    val type: String,
    val t: Double,
    /** End of an interval primitive, e.g. `camera_unstable [t, t_end]`. */
    @SerialName("t_end") val tEnd: Double? = null,
    val entity: EntityRef? = null,
    /** Container class. */
    val container: String? = null,
    val zone: String? = null,
    val state: String? = null,
    /** Decoded value for `code_read`. */
    val code: String? = null,
    /** For `identity_unresolved`: the earlier track the new one may equal. */
    @SerialName("other_track_id") val otherTrackId: String? = null,
    val confidence: Double,
    @SerialName("evidence_frames") val evidenceFrames: List<Long> = emptyList(),
)

@Serializable
data class ObservabilityWindow(
    val target: String,
    val intervals: List<List<Double>>,
)
