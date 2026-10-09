package com.application.sensorforge.crashguard

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.detect.ImuChannel
import com.application.sensorforge.sdk.detect.ImuRingBuffer
import com.application.sensorforge.sdk.sensors.AndroidImuSource
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

data class LiveUi(val accelHz: Double = 0.0, val gyroHz: Double = 0.0, val rotHz: Double = 0.0, val missing: List<ImuChannel> = emptyList())

class CrashGuardViewModel(app: Application) : AndroidViewModel(app) {
    val spec = Contracts.parseDetect(app.assets.open("specs/crashguard.detect.json").bufferedReader().readText())
        .also { Validator.requireValid(it) }
    private val buffer = ImuRingBuffer(spec.evidence.imuRingBufferS.toDouble())
    private val imu = AndroidImuSource(app, spec.window.imuHz)
    val model = ImuModel(app, spec)

    private val _model = MutableStateFlow(ModelUi())
    val modelUi: StateFlow<ModelUi> = _model.asStateFlow()
    private val _live = MutableStateFlow(LiveUi())
    val live: StateFlow<LiveUi> = _live.asStateFlow()
    private var ticker: Job? = null

    init { refresh() }

    /** Foreground only: called from onResume / onPause. Motion sensors only. */
    fun onVisible() {
        imu.start(buffer::add)
        ticker = viewModelScope.launch {
            while (isActive) {
                _live.value = LiveUi(
                    buffer.rateHz(ImuChannel.ACCEL), buffer.rateHz(ImuChannel.GYRO), buffer.rateHz(ImuChannel.ROTATION), imu.missingChannels,
                )
                delay(1000)
            }
        }
    }

    fun onHidden() {
        imu.stop(); ticker?.cancel(); buffer.clear()
        _model.update { it.copy(recorder = null) }
    }

    fun setHeldOut(v: Boolean) = _model.update { it.copy(heldOutNext = v) }

    fun record(label: String) {
        if (_model.value.recorder != null) return
        val seconds = RECORD_SECONDS.getValue(label)
        val heldOut = _model.value.heldOutNext
        viewModelScope.launch {
            val start = SystemClock.elapsedRealtimeNanos()
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
            _model.update { it.copy(counts = counts, versions = versions, active = active, latest = latest) }
        }
    }

    override fun onCleared() { imu.stop() }

    companion object {
        val RECORD_SECONDS = mapOf("normal" to 15, "shake" to 8, "drop" to 8)
    }
}
