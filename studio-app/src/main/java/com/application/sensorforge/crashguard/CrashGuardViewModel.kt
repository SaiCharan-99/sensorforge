package com.application.sensorforge.crashguard

import android.app.Application
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.detect.ImuChannel
import com.application.sensorforge.sdk.detect.ImuRingBuffer
import com.application.sensorforge.sdk.detect.ImuSample
import com.application.sensorforge.sdk.detect.IncidentController
import com.application.sensorforge.sdk.detect.IncidentEvidence
import com.application.sensorforge.sdk.detect.IncidentState
import com.application.sensorforge.sdk.detect.SensorObservation
import com.application.sensorforge.sdk.detect.TraceEntry
import com.application.sensorforge.sdk.detect.WindowFeatures
import com.application.sensorforge.sdk.sensors.AndroidImuSource
import com.application.sensorforge.sdk.sensors.ReplaySource
import com.application.sensorforge.sdk.train.TrainingError
import com.application.sensorforge.sdk.train.TrainingReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

data class Recorder(val label: String, val remainingS: Int)

data class ModelUi(
    val counts: List<ClassCount> = emptyList(),
    val recorder: Recorder? = null,
    val heldOutNext: Boolean = false,
    val training: Boolean = false,
    val error: String? = null,
    val versions: List<Int> = emptyList(),
    val active: Int? = null,
    val latest: TrainingReport? = null,
)

data class LiveUi(
    val accelHz: Double = 0.0, val gyroHz: Double = 0.0, val rotHz: Double = 0.0,
    val missing: List<ImuChannel> = emptyList(),
    val accelG: List<Float> = emptyList(), val gyroDps: List<Float> = emptyList(),
    val replaying: String? = null,
)

data class IncidentUi(
    val state: IncidentState = IncidentState.DISARMED,
    val simulation: Boolean = false,
    val countdownLeftS: Int = 0,
    val trace: List<TraceEntry> = emptyList(),
    val evidence: IncidentEvidence? = null,
    val evidenceFile: String? = null,
)

class CrashGuardViewModel(app: Application) : AndroidViewModel(app) {
    val spec = Contracts.parseDetect(app.assets.open("specs/crashguard.detect.json").bufferedReader().readText())
        .also { Validator.requireValid(it) }
    private val buffer = ImuRingBuffer(spec.evidence.imuRingBufferS.toDouble())
    private val imu = AndroidImuSource(app, spec.window.imuHz)
    val model = ImuModel(app, spec)
    private val controller = IncidentController(spec, null)
    private val log = IncidentLog(File(app.filesDir, "crashguard/incidents"))
    private val vibrator = app.getSystemService(Vibrator::class.java)

    private val _model = MutableStateFlow(ModelUi())
    val modelUi: StateFlow<ModelUi> = _model.asStateFlow()
    private val _live = MutableStateFlow(LiveUi())
    val live: StateFlow<LiveUi> = _live.asStateFlow()
    private val _incident = MutableStateFlow(IncidentUi())
    val incident: StateFlow<IncidentUi> = _incident.asStateFlow()

    private var visible = false
    private var source: SensorObservation? = null
    private var ticker: Job? = null
    private var lastBuzzS = -1

    init {
        refresh()
        viewModelScope.launch { while (isActive) { incidentTick(); delay(200) } }
    }

    private fun sink(sim: Boolean): (ImuSample) -> Unit = { s -> buffer.add(s); controller.onSample(s, sim) }

    private fun startSource(src: SensorObservation) {
        source?.stop()
        buffer.clear()
        controller.resetStream(src.isSimulation)
        source = src
        src.start(sink(src.isSimulation))
    }

    /** Foreground only (onResume). Motion sensors only, never camera or mic. */
    fun onVisible() {
        visible = true
        startSource(imu)
        ticker = viewModelScope.launch {
            while (isActive) {
                val snap = buffer.snapshot()
                val tail = snap.maxOfOrNull { it.tNanos }?.let { end -> snap.filter { it.tNanos >= end - 5_000_000_000L } }.orEmpty()
                _live.update {
                    it.copy(
                        accelHz = buffer.rateHz(ImuChannel.ACCEL), gyroHz = buffer.rateHz(ImuChannel.GYRO),
                        rotHz = buffer.rateHz(ImuChannel.ROTATION), missing = imu.missingChannels,
                        accelG = tail.filter { s -> s.channel == ImuChannel.ACCEL }.map { s -> (mag(s) / WindowFeatures.G).toFloat() },
                        gyroDps = tail.filter { s -> s.channel == ImuChannel.GYRO }.map { s -> (mag(s) * 57.2958).toFloat() },
                    )
                }
                delay(200)
            }
        }
    }

    /** App not visible: stop all capture. An open incident keeps its countdown (timer only). */
    fun onHidden() {
        visible = false
        source?.stop(); source = null
        ticker?.cancel(); buffer.clear()
        controller.disarm(now())
        _model.update { it.copy(recorder = null) }
        _live.update { it.copy(replaying = null) }
    }

    fun arm() { controller.arm(now()); log("arm") }
    fun disarm() = controller.disarm(now())
    fun cancel() = controller.cancel(now())
    fun needHelp() = controller.needHelp(now())
    fun sendSimulatedSos() = controller.sendSimulatedSos(now())
    fun resolve() { controller.resolve(now()); controller.arm(now()) }

    /** S10 "Play impact replay": swaps the live source for a SIMULATION replay, then back to live. */
    fun playReplay(index: Int) {
        val r = model.replays.getOrNull(index) ?: return
        if (!visible || _live.value.replaying != null) return
        _live.update { it.copy(replaying = r.name) }
        startSource(ReplaySource(r, viewModelScope) {
            _live.update { it.copy(replaying = null) }
            if (visible) startSource(imu)
        })
    }

    private fun incidentTick() {
        val t = now()
        controller.tick(t)
        val st = controller.state.value
        val left = if (st == IncidentState.COUNTDOWN) {
            val start = controller.trace.value.last { it.state == IncidentState.COUNTDOWN }.tNanos
            ((start + controller.countdownNanos - t + 999_999_999) / 1_000_000_000).toInt().coerceAtLeast(0)
        } else 0
        if (st == IncidentState.COUNTDOWN && left != lastBuzzS) { lastBuzzS = left; buzz() }
        val ev = controller.evidence.value
        val prev = _incident.value
        var file = prev.evidenceFile
        if (ev != null && prev.evidence?.incidentId != ev.incidentId) {
            file = runCatching { log.save(ev, controller.frozen).name }.getOrElse { "save failed: $it" }
            Log.i(TAG, "evidence ${ev.incidentId} sim=${ev.simulation} receipt=${ev.receiptSha256.take(12)} -> $file")
        }
        if (ev == null) file = null
        val trace = controller.trace.value
        val fresh = if (trace.size >= prev.trace.size && trace.take(prev.trace.size) == prev.trace) trace.drop(prev.trace.size) else trace
        fresh.forEach { Log.i(TAG, "${it.state}: ${it.message}") }
        _incident.value = IncidentUi(st, controller.simulation, left, trace, ev, file)
    }

    private fun buzz() = runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)) }

    fun setHeldOut(v: Boolean) = _model.update { it.copy(heldOutNext = v) }

    fun record(label: String) {
        if (_model.value.recorder != null || _live.value.replaying != null) return
        val seconds = RECORD_SECONDS.getValue(label)
        val heldOut = _model.value.heldOutNext
        viewModelScope.launch {
            val start = now()
            for (s in seconds downTo 1) {
                _model.update { it.copy(recorder = Recorder(label, s), error = null) }
                delay(1000)
            }
            if (_model.value.recorder == null) return@launch // app hidden mid-recording: discard
            val samples = buffer.snapshot().filter { it.tNanos >= start }
            withContext(Dispatchers.IO) { model.record(label, heldOut, samples) }
            _model.update { it.copy(recorder = null) }
            refresh()
        }
    }

    fun train() {
        if (_model.value.training) return
        _model.update { it.copy(training = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { model.train() } }
            result.onFailure { e ->
                _model.update { it.copy(error = if (e is TrainingError) e.message else "Training failed: $e") }
            }
            _model.update { it.copy(training = false) }
            refresh()
        }
    }

    fun promote(version: Int) {
        model.heads.promote(version)
        refresh()
    }

    fun deleteAllRecordings() {
        model.recordings.deleteAll()
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val (counts, versions, active) = withContext(Dispatchers.IO) {
                Triple(model.counts(model.examples()), model.heads.versions(), model.heads.active())
            }
            val latest = versions.maxOrNull()?.let { withContext(Dispatchers.IO) { model.heads.report(it) } }
            controller.setHead(active?.let { withContext(Dispatchers.IO) { model.heads.load(it) } })
            _model.update { it.copy(counts = counts, versions = versions, active = active, latest = latest) }
        }
    }

    private fun log(what: String) = Log.i(TAG, "$what (head=${_model.value.active?.let { "v$it" } ?: "none"})")

    override fun onCleared() { source?.stop() }

    companion object {
        const val TAG = "SF.CG"
        val RECORD_SECONDS = mapOf("normal" to 15, "shake" to 8, "drop" to 8)
        private fun now() = SystemClock.elapsedRealtimeNanos()
        private fun mag(s: ImuSample) = sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
    }
}
