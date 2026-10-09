package com.application.sensorforge.sdk.contracts

/** Closed vocabularies. Adding a primitive needs a schema version bump (tech spec §10). */
object Vocabulary {
    const val SCHEMA_VERSION = "3.0"

    val VERIFY_PRIMITIVES = setOf(
        "appears", "disappears", "track_break",
        "enters_zone", "leaves_zone", "region_empty",
        "contained_in", "exits_container",
        "held_by_hand", "released", "transferred",
        "state_is", "state_change",
        "code_read",
        "joint_angle_in_range", "pose_held", "rep_completed",
        "steps_walked", "at_location",
        "camera_unstable",
        "identity_unresolved", "unexplained_change",
    )
    val DETECT_PRIMITIVES = setOf("impact_candidate", "stillness", "camera_unstable")
    val ALL_PRIMITIVES = VERIFY_PRIMITIVES + DETECT_PRIMITIVES

    val PERCEPTION_PROFILES = setOf(
        "objects", "zones", "codes", "state", "identity", "body", "hands", "sensors", "quality",
    )

    val SENSORS = setOf(
        "camera", "accelerometer", "gyroscope", "rotation_vector", "step_counter", "location", "clock",
    )

    /** Detect mode is motion-only: camera and mic stay off (tech spec §17, §20). */
    val DETECT_FORBIDDEN_SENSORS = setOf("camera", "microphone")

    val IDENTITY_POLICIES = setOf("track_continuity", "code_required")
    val MODES = setOf("verify", "detect")

    /** `list<code>`: host-supplied items (for_each); `time`: UTC epoch ms (AFTER_PARAM). */
    val PARAMETER_TYPES = setOf("list<code>", "time")
}
