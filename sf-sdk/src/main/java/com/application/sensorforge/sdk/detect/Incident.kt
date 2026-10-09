package com.application.sensorforge.sdk.detect

import com.application.sensorforge.sdk.contracts.DetectSpec
import com.application.sensorforge.sdk.train.Head
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.sqrt

/** Tech spec §19. */
enum class IncidentState {
    DISARMED, ARMED, CANDIDATE, SUSPECTED, COUNTDOWN, CANCELLED, CONFIRMED, TIMEOUT, EVIDENCE_SAVED, SOS_SIMULATED, RESOLVED
}

@Serializable
data class TraceEntry(val tNanos: Long, val state: IncidentState, val message: String)

/** The only transport outcome that exists. There is deliberately no SENT. */
enum class TransportResult { SIMULATED }

interface IncidentTransport { fun send(evidence: IncidentEvidence): TransportResult }

/** Tech spec §19: never sends anything; returns SIMULATED. */
object MockIncidentTransport : IncidentTransport {
    override fun send(evidence: IncidentEvidence) = TransportResult.SIMULATED
}

@Serializable
data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Float, val ageMs: Long)

@Serializable
data class IncidentEvidence(
    val incidentId: String,
    val simulation: Boolean,
    val peakNanos: Long,
    val peakG: Double,
    val features: List<Float>,
    val decidedBy: String,                 // "head imu_risk v3 <sha>" or "rule-based triage (no head)"
    val impactProb: Double?,
    val classProbs: Map<String, Double>,
    val postPeakStdG: Double,
    val location: LocationFix?,
    val locationStatus: String,            // OK | LOCATION_NOT_AVAILABLE
    val imuSamples: Int,
    val imuSha256: String,
    val trace: List<TraceEntry>,
    val receiptSha256: String = "",
)

/**
 * CrashGuard detect engine + incident lifecycle (tech spec §17, §19). Pure and deterministic:
 * time comes only from sample timestamps and [tick]. Live and SIMULATION samples share this path;
 * [simulation] marks whatever the current incident was triggered by.
 *
 * Unknown by default: if the decision window is not observable, no incident is raised and the
 * trace says NOT_OBSERVABLE.
 */
class IncidentController(
    private val spec: DetectSpec,
    private var head: Head?,
    private val transport: IncidentTransport = MockIncidentTransport,
    private val locate: () -> LocationFix? = { null },
) {
    private val buffer = ImuRingBuffer(spec.evidence.imuRingBufferS.toDouble())
    private val _state = MutableStateFlow(IncidentState.DISARMED)
    val state: StateFlow<IncidentState> = _state.asStateFlow()
    private val _trace = MutableStateFlow<List<TraceEntry>>(emptyList())
    val trace: StateFlow<List<TraceEntry>> = _trace.asStateFlow()
    private val _evidence = MutableStateFlow<IncidentEvidence?>(null)
    val evidence: StateFlow<IncidentEvidence?> = _evidence.asStateFlow()

    var simulation = false; private set
    private var lastT = Long.MIN_VALUE
    private var gateT = 0L
    private var peakT = 0L
    private var peakMag = 0.0
    private var countdownEnd = 0L
    private var pending: IncidentEvidence? = null
    private var counter = 0

    val countdownNanos get() = spec.incident.countdownS * 1_000_000_000L

    @Synchronized fun setHead(h: Head?) { head = h }

    @Synchronized fun arm(now: Long) {
        if (_state.value != IncidentState.DISARMED && _state.value != IncidentState.RESOLVED) return
        buffer.clear(); _trace.value = emptyList(); _evidence.value = null; pending = null; simulation = false
        go(now, IncidentState.ARMED, "armed: motion sensors only, camera and mic off")
    }

    @Synchronized fun disarm(now: Long) {
        if (_state.value in ACTIVE_INCIDENT) return // an open incident must be cancelled or resolved by the user
        buffer.clear()
        go(now, IncidentState.DISARMED, "disarmed")
    }

    /** Starts a new data stream (e.g. switching live → replay); clears the window buffer. */
    @Synchronized fun resetStream(isSimulation: Boolean) {
        buffer.clear()
        if (_state.value == IncidentState.CANDIDATE) _state.value = IncidentState.ARMED
        if (_state.value == IncidentState.ARMED) simulation = isSimulation
    }

    @Synchronized fun onSample(s: ImuSample, isSimulation: Boolean) {
        val st = _state.value
        if (st != IncidentState.ARMED && st != IncidentState.CANDIDATE) return
        buffer.add(s)
        lastT = maxOf(lastT, s.tNanos)
        when (st) {
            IncidentState.ARMED -> if (gate(s)) {
                simulation = isSimulation
                gateT = s.tNanos; peakT = s.tNanos; peakMag = accelG(s)
                go(s.tNanos, IncidentState.CANDIDATE, "gate crossed (%s)".format(describe(s)) + if (isSimulation) " [SIMULATION]" else "")
            }
            IncidentState.CANDIDATE -> {
                if (s.channel == ImuChannel.ACCEL && s.tNanos <= gateT + PEAK_SEARCH_NANOS && accelG(s) > peakMag) {
                    peakMag = accelG(s); peakT = s.tNanos
                }
                val evalAt = ImuDataset.eventWindowEnds(peakT, spec.window.lengthS, spec.window.hopS, spec.corroboration.postEventStillnessS).first()
                if (s.tNanos >= evalAt && s.tNanos > gateT + PEAK_SEARCH_NANOS) evaluate(evalAt)
            }
            else -> Unit
        }
    }

    /** Drives the countdown. */
    @Synchronized fun tick(now: Long) {
        if (_state.value == IncidentState.COUNTDOWN && now >= countdownEnd) {
            go(now, IncidentState.TIMEOUT, "countdown elapsed without response")
            saveEvidence(now)
            if (spec.incident.autoEscalate) sendSimulatedSos(now)
            else log(now, "auto_escalate=false: waiting for manual action")
        }
    }

    @Synchronized fun cancel(now: Long) {
        if (_state.value != IncidentState.COUNTDOWN) return
        go(now, IncidentState.CANCELLED, "cancelled by user; no alert")
        pending = null
    }

    @Synchronized fun needHelp(now: Long) {
        if (_state.value != IncidentState.COUNTDOWN) return
        go(now, IncidentState.CONFIRMED, "user pressed I need help")
        saveEvidence(now)
        sendSimulatedSos(now)
    }

    /** Manual action after TIMEOUT when auto_escalate=false. */
    @Synchronized fun sendSimulatedSos(now: Long) {
        if (_state.value != IncidentState.EVIDENCE_SAVED) return
        val ev = _evidence.value ?: return
        val r = transport.send(ev)
        go(now, IncidentState.SOS_SIMULATED, "transport=${spec.incident.transport} result=$r")
    }

    @Synchronized fun resolve(now: Long) {
        if (_state.value !in setOf(IncidentState.CANCELLED, IncidentState.EVIDENCE_SAVED, IncidentState.SOS_SIMULATED)) return
        go(now, IncidentState.RESOLVED, "resolved")
    }

    private fun evaluate(evalAt: Long) {
        val samples = buffer.snapshot()
        val w = spec.window
        val f = WindowFeatures.compute(samples, evalAt, w.lengthS, w.imuHz, spec.corroboration.postEventStillnessS)
        if (f == null) {
            back(evalAt, "NOT_OBSERVABLE: decision window incomplete (missing or dropped samples); no incident")
            return
        }
        val still = f[WindowFeatures.NAMES.indexOf("post_peak_acc_std")].toDouble()
        val stillOk = still <= spec.corroboration.stillnessMaxGStd
        val h = head
        val probs: Map<String, Double>
        val pImpact: Double?
        val decidedBy: String
        val headOk: Boolean
        if (h != null) {
            val p = h.predict(f)
            probs = h.classes.indices.associate { h.classes[it] to p[it].toDouble() }
            pImpact = probs[spec.head.positiveClass] ?: 0.0
            headOk = pImpact >= spec.head.minProb
            decidedBy = "head ${h.id} v${h.version} ${h.sha256().take(12)}"
        } else {
            probs = emptyMap(); pImpact = null; decidedBy = "rule-based triage (no head)"
            // Fallback (model stack §8): stricter gate, 1.5× the configured peak, plus stillness.
            headOk = peakMag >= spec.gate.accelPeakG * 1.5
        }
        val summary = "peak %.1f g, post-peak std %.3f g (max %.2f), %s%s".format(
            peakMag, still, spec.corroboration.stillnessMaxGStd, decidedBy,
            pImpact?.let { ", p(${spec.head.positiveClass})=%.2f (min %.2f)".format(it, spec.head.minProb) } ?: "",
        )
        if (!(headOk && stillOk)) {
            val cls = if (h != null) "head" else "rule"
            back(evalAt, "no incident: $summary" + when {
                !headOk && !stillOk -> " [$cls + stillness rejected]"
                !headOk -> " [$cls rejected]"
                else -> " [no stillness]"
            })
            return
        }
        go(evalAt, IncidentState.SUSPECTED, "possible impact: $summary")
        val csv = ImuReplay.toCsv(emptyList(), samples)
        pending = IncidentEvidence(
            incidentId = "inc-${++counter}-$peakT",
            simulation = simulation,
            peakNanos = peakT,
            peakG = peakMag,
            features = f.toList(),
            decidedBy = decidedBy,
            impactProb = pImpact,
            classProbs = probs,
            postPeakStdG = still,
            location = null,
            locationStatus = "",
            imuSamples = samples.size,
            imuSha256 = sha256(csv.toByteArray()),
            trace = emptyList(),
        )
        frozen = samples
        countdownEnd = evalAt + countdownNanos
        go(evalAt, IncidentState.COUNTDOWN, "countdown ${spec.incident.countdownS} s")
    }

    /** IMU buffer frozen at SUSPECTED (evidence). */
    var frozen: List<ImuSample> = emptyList(); private set

    private fun saveEvidence(now: Long) {
        val p = pending ?: return
        val loc = runCatching { locate() }.getOrNull()
        val ev = p.copy(location = loc, locationStatus = if (loc == null) "LOCATION_NOT_AVAILABLE" else "OK")
        go(now, IncidentState.EVIDENCE_SAVED, "evidence frozen: ${ev.imuSamples} IMU samples, location ${ev.locationStatus}")
        val withTrace = ev.copy(trace = _trace.value)
        _evidence.value = withTrace.copy(receiptSha256 = receipt(withTrace))
        pending = null
    }

    private fun back(t: Long, msg: String) {
        _state.value = IncidentState.ARMED
        log(t, msg)
        simulation = false
    }

    private fun go(t: Long, s: IncidentState, msg: String) { _state.value = s; log(t, msg) }

    private fun log(t: Long, msg: String) { _trace.value = _trace.value + TraceEntry(t, _state.value, msg) }

    private fun gate(s: ImuSample) = when (s.channel) {
        ImuChannel.ACCEL -> accelG(s) >= spec.gate.accelPeakG
        ImuChannel.GYRO -> mag(s) * RAD_TO_DEG >= spec.gate.gyroPeakDps
        else -> false
    }

    private fun describe(s: ImuSample) =
        if (s.channel == ImuChannel.ACCEL) "|a| %.1f g".format(accelG(s)) else "|ω| %.0f dps".format(mag(s) * RAD_TO_DEG)

    companion object {
        const val PEAK_SEARCH_NANOS = 500_000_000L
        private const val RAD_TO_DEG = 57.29577951308232
        val ACTIVE_INCIDENT = setOf(IncidentState.SUSPECTED, IncidentState.COUNTDOWN, IncidentState.CONFIRMED, IncidentState.TIMEOUT, IncidentState.EVIDENCE_SAVED, IncidentState.SOS_SIMULATED)

        private fun mag(s: ImuSample) = sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
        private fun accelG(s: ImuSample) = mag(s) / WindowFeatures.G

        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** Receipt hash over the evidence with an empty receipt field (canonical JSON). */
        fun receipt(e: IncidentEvidence): String =
            sha256(kotlinx.serialization.json.Json.encodeToString(IncidentEvidence.serializer(), e.copy(receiptSha256 = "")).toByteArray())
    }
}
