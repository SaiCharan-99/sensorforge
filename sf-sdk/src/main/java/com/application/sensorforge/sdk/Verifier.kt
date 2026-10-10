package com.application.sensorforge.sdk

import androidx.camera.view.PreviewView
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.engine.Decision
import com.application.sensorforge.sdk.engine.Reason
import com.application.sensorforge.sdk.engine.StepResult
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.perception.CoachingPrompt
import com.application.sensorforge.sdk.perception.Pt
import kotlinx.coroutines.flow.Flow

/** Public verifier API (tech spec §4). */
interface Verifier {
    val pack: PackInfo
    fun start(previewView: PreviewView?, onResult: (VerifierResult) -> Unit)
    fun events(): Flow<VerifierEvent>
    fun stop()
}

data class PackInfo(
    val id: String,
    val version: Int,
    val mode: String,
    /** SHA-256 of the pack (`.sfpkg`), or of the spec JSON until packs land. */
    val sha256: String,
    val perceptionStack: String,
    val stepIds: List<String>,
)

data class VerifierResult(
    val verdict: Verdict,
    val reasons: List<Reason>,
    val steps: List<StepResult>,
    val timeline: Timeline,
    /** Frame index → saved evidence JPEG (app-private). */
    val evidenceFiles: Map<Long, String>,
    /** Written in Phase 4 (receipts). */
    val receiptPath: String? = null,
)

/** Overlay geometry in upright, normalised image coordinates. */
data class OverlayBox(val trackId: String, val cls: String, val l: Float, val t: Float, val r: Float, val b: Float, val code: String?, val confidence: Double, val source: String = "tag")

sealed interface VerifierEvent {
    /** Per analysed frame. `aspect` = upright width / height of the analysis image. */
    data class Frame(
        val boxes: List<OverlayBox>,
        val zones: Map<String, List<Pt>>,
        val aspect: Float,
        val usable: Boolean,
        val fps: Double,
        val decodeMs: Double,
    ) : VerifierEvent

    /** Live re-evaluation (~1 Hz). Not a verdict: steps not yet seen read ABSENT/NOT_OBSERVABLE. */
    data class Live(val decision: Decision, val activeStepId: String?, val tS: Double) : VerifierEvent

    data class Coaching(val prompt: CoachingPrompt) : VerifierEvent

    /** Pose landmarks (upright normalised; null = no person) with per-point visibility and the rep/hold state (RepRight, S14). */
    data class Pose(val points: List<Pt>?, val visibility: List<Float>?, val live: com.application.sensorforge.sdk.pose.RepLive) : VerifierEvent

    /** Hand landmarks (one list per hand), the side split and the per-item state (HandoverProof, S15). */
    data class Handover(val hands: List<List<Pt>>, val splitX: Float, val partnerOnLeft: Boolean, val live: com.application.sensorforge.sdk.handover.HandoverLive) : VerifierEvent

    /** Backend log line (rule 4): runtime, provider, backend, latency. */
    data class Backend(val line: String) : VerifierEvent
}

class PermissionMissing(val permission: String) : Exception("Permission missing: $permission")
class CapabilityMissing(message: String) : Exception(message)
