package com.application.sensorforge.sdk.forge

/** A value the use case needs before capture or at run time (ui-design S02b "Config"). */
data class ConfigField(
    val key: String,
    val label: String,
    val kind: Kind,
    val default: String = "",
    val hint: String = "",
) {
    enum class Kind { TIME, ITEMS, TEXT, NUMBER }
}

/** Static description of an app the guided flow can set up (all of them share the same flow). */
data class UseCaseDef(
    val id: String,
    val title: String,
    val mode: String,
    /** Asset path of the shipped spec (relative to assets/ or fixtures/). */
    val specAsset: String,
    val blurb: String,
    val exampleSentence: String,
    val sensors: List<String>,
    val heads: List<HeadPlan>,
    /** Class the tagged-prop pipeline reads when capturing crops for a head (headId → tag class). */
    val cropTagClass: Map<String, String> = emptyMap(),
    val config: List<ConfigField> = emptyList(),
    /** Perception profile written to the manifest; derived from the spec when null. */
    val profile: List<String>? = null,
    /** Models (catalog ids) the runtime uses for this app; they become models.lock. */
    val models: List<String>,
)

object UseCases {
    val PACKCHECK = UseCaseDef(
        id = "packcheck", title = "PackCheck", mode = "verify", specAsset = "specs/packcheck.spec.json",
        blurb = "Verify a kit packing: items out of the box, adapter label read, look-alikes rejected.",
        exampleSentence = "Open the box. Remove the charger and the adapter from the box. Read the adapter label AD-42. At most one adapter. Never show impostor cable.",
        sensors = listOf("camera", "clock"),
        heads = listOf(
            HeadPlan(
                "identity_packcheck", "H1", listOf("charger", "impostor_cable"), CaptureKind.CROP,
                mapOf(
                    "charger" to "Hold the genuine charger (with its tag) in view for 4 s; vary angle and distance.",
                    "impostor_cable" to "Hold the look-alike cable wearing the same tag in view for 4 s.",
                ),
                kindOf = { if (it == "charger") PlanKind.GOOD else PlanKind.BAD },
            ),
        ),
        cropTagClass = mapOf("identity_packcheck" to "charger"),
        config = listOf(ConfigField("items", "Items list (host app)", ConfigField.Kind.ITEMS, "A,B,C", "Optional for PackCheck.")),
        models = listOf("m1_tags", "c1_zones_qr", "c1_quality", "c2_zxing", "c3_tracker", "m2_yuv_hist"),
    )

    val SEALSAFE = UseCaseDef(
        id = "sealsafe", title = "SealSafe", mode = "verify", specAsset = "specs/sealsafe.spec.json",
        blurb = "Verify an exam envelope: seal shown intact, opened only after the start time, papers removed.",
        exampleSentence = "Show the sealed envelope. Open it only after start. Remove the papers from the envelope.",
        sensors = listOf("camera", "clock"),
        heads = listOf(
            HeadPlan(
                "state_sealsafe", "H2", listOf("intact", "broken"), CaptureKind.CROP,
                mapOf(
                    "intact" to "Show the intact seal (with its tag) for 4 s; vary angle and lighting.",
                    "broken" to "Show the broken/opened seal (with its tag) for 4 s.",
                ),
                kindOf = { if (it == "intact") PlanKind.GOOD else PlanKind.BAD },
            ),
        ),
        cropTagClass = mapOf("state_sealsafe" to "seal"),
        config = listOf(ConfigField("start_time", "Exam start time", ConfigField.Kind.TIME, "", "UTC time the envelope may be opened from.")),
        models = listOf("m1_tags", "c1_quality", "c2_zxing", "c3_tracker", "m2_yuv_hist"),
    )

    val CRASHGUARD = UseCaseDef(
        id = "crashguard", title = "CrashGuard", mode = "detect", specAsset = "specs/crashguard.detect.json",
        blurb = "Possible-impact triage from motion sensors only, with a cancellable countdown and a simulated SOS.",
        exampleSentence = "",
        sensors = listOf("accelerometer", "gyroscope", "rotation_vector", "location", "clock"),
        heads = listOf(
            HeadPlan(
                "imu_risk", "H3", listOf("normal", "shake", "drop", "impact_like"), CaptureKind.IMU,
                mapOf(
                    "normal" to "Walk, hold or carry the phone normally for 20 s (record several windows).",
                    "shake" to "Shake the phone by hand and knock it on a table; do not drop it.",
                    "drop" to "Drop the phone flat onto a soft surface (bed). Record each drop.",
                    "impact_like" to "Comes from labelled replay fixtures (SIMULATION), never faked as live.",
                ),
                replayOnly = setOf("impact_like"),
                kindOf = { if (it == "impact_like") PlanKind.GOOD else PlanKind.BAD },
            ),
        ),
        models = emptyList(),
    )

    val ALL = listOf(PACKCHECK, CRASHGUARD, SEALSAFE)
    fun byId(id: String) = ALL.first { it.id == id }
}
