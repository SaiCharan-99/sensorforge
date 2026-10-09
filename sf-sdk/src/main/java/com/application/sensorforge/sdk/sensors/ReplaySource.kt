package com.application.sensorforge.sdk.sensors

import android.os.SystemClock
import com.application.sensorforge.sdk.detect.ImuReplay
import com.application.sensorforge.sdk.detect.ImuSample
import com.application.sensorforge.sdk.detect.SensorObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays an IMU replay in real time through the same [SensorObservation] interface as live sensors
 * (tech spec §20). Timestamps are rebased onto elapsedRealtimeNanos. Always SIMULATION.
 */
class ReplaySource(
    private val replay: ImuReplay,
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit = {},
) : SensorObservation {
    override val isSimulation = true
    private var job: Job? = null

    override fun start(sink: (ImuSample) -> Unit) {
        stop()
        val samples = replay.samples.sortedWith(compareBy({ it.tNanos }, { it.channel }))
        if (samples.isEmpty()) { onFinished(); return }
        val t0 = samples.first().tNanos
        val base = SystemClock.elapsedRealtimeNanos()
        job = scope.launch {
            var i = 0
            while (isActive && i < samples.size) {
                val now = SystemClock.elapsedRealtimeNanos() - base
                while (i < samples.size && samples[i].tNanos - t0 <= now) {
                    val s = samples[i++]
                    sink(s.copy(tNanos = base + (s.tNanos - t0)))
                }
                delay(5)
            }
            if (isActive) onFinished()
        }
    }

    override fun stop() { job?.cancel(); job = null }
}
