package com.application.sensorforge.sdk.pose

/** MediaPipe Pose landmark indices used here (33-point model). */
object Lm {
    const val L_SHOULDER = 11; const val R_SHOULDER = 12
    const val L_HIP = 23; const val R_HIP = 24
    const val L_KNEE = 25; const val R_KNEE = 26
    const val L_ANKLE = 27; const val R_ANKLE = 28
    const val COUNT = 33
}

/** One landmark, upright normalised image coordinates; [vis] = visibility/presence 0..1. */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f, val vis: Float = 1f)

/** Pose landmarks at time [t] seconds; null = no person found. */
data class PoseFrame(val t: Double, val lm: List<Landmark>?)

enum class Side { LEFT, RIGHT }

enum class Exercise { KNEE_EXTENSION, TREE_POSE }

/** A joint angle (at [b], between a-b-c) that must lie in [min, max] degrees. */
data class JointCriterion(val name: String, val a: Int, val b: Int, val c: Int, val min: Double, val max: Double)

/**
 * Therapist configuration (tech spec Part F): ranges, hold and tempo are config, not model output.
 * Knee extension: the knee goes from flexed (<= [restBelowDeg]) into [angleMin, angleMax], is held
 * >= [holdS], and the whole rep takes between [tempoMinS] and [tempoMaxS].
 * Tree pose: every criterion in range at once for >= [holdS]; [side] is the standing leg.
 */
data class RepConfig(
    val exercise: Exercise = Exercise.KNEE_EXTENSION,
    val side: Side = Side.RIGHT,
    val reps: Int = 3,
    val angleMin: Double = 160.0,
    val angleMax: Double = 185.0,
    val holdS: Double = 1.0,
    val tempoMinS: Double = 3.0,
    val tempoMaxS: Double = 10.0,
    val restBelowDeg: Double = 120.0,
    val minVisibility: Float = 0.5f,
) {
    init {
        require(reps in 1..50) { "reps must be 1..50" }
        require(angleMin < angleMax) { "angle range is empty" }
        require(holdS >= 0.0) { "hold must be >= 0" }
        require(tempoMinS <= tempoMaxS) { "tempo window is empty" }
    }

    /** Criteria for the configured exercise and side. */
    fun criteria(): List<JointCriterion> = when (exercise) {
        Exercise.KNEE_EXTENSION -> {
            val r = side == Side.RIGHT
            listOf(JointCriterion("knee_extension", if (r) Lm.R_HIP else Lm.L_HIP, if (r) Lm.R_KNEE else Lm.L_KNEE, if (r) Lm.R_ANKLE else Lm.L_ANKLE, angleMin, angleMax))
        }
        Exercise.TREE_POSE -> {
            val stand = side == Side.RIGHT
            listOf(
                JointCriterion("standing_knee_straight", if (stand) Lm.R_HIP else Lm.L_HIP, if (stand) Lm.R_KNEE else Lm.L_KNEE, if (stand) Lm.R_ANKLE else Lm.L_ANKLE, angleMin, angleMax),
                JointCriterion("lifted_knee_bent", if (stand) Lm.L_HIP else Lm.R_HIP, if (stand) Lm.L_KNEE else Lm.R_KNEE, if (stand) Lm.L_ANKLE else Lm.R_ANKLE, 20.0, 100.0),
            )
        }
    }

    companion object {
        fun kneeExtension(side: Side = Side.RIGHT, reps: Int = 3) = RepConfig(Exercise.KNEE_EXTENSION, side, reps)
        fun treePose(standing: Side = Side.RIGHT, holdS: Double = 5.0) =
            RepConfig(Exercise.TREE_POSE, standing, reps = 1, angleMin = 165.0, angleMax = 185.0, holdS = holdS, tempoMinS = 0.0, tempoMaxS = 600.0)
    }
}
