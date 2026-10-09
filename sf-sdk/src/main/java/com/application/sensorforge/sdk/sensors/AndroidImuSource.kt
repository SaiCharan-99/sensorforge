package com.application.sensorforge.sdk.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.application.sensorforge.sdk.detect.ImuChannel
import com.application.sensorforge.sdk.detect.ImuSample
import com.application.sensorforge.sdk.detect.SensorObservation

/**
 * Live accelerometer + gyroscope + rotation vector via SensorManager (tech spec §17).
 * Motion sensors only; never touches camera or microphone. Call only from a visible
 * foreground session.
 */
class AndroidImuSource(context: Context, private val hz: Int = 100) : SensorObservation {
    override val isSimulation = false
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var listener: SensorEventListener? = null

    /** Channels whose hardware sensor is missing on this device. */
    val missingChannels: List<ImuChannel>
        get() = CHANNELS.filter { (_, type) -> sm.getDefaultSensor(type) == null }.map { it.first }

    override fun start(sink: (ImuSample) -> Unit) {
        stop()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ch = CHANNELS.firstOrNull { it.second == e.sensor.type }?.first ?: return
                val v = e.values
                sink(
                    if (ch == ImuChannel.ROTATION) {
                        val q = FloatArray(4).also { SensorManager.getQuaternionFromVector(it, v) }
                        ImuSample(e.timestamp, ch, q[1], q[2], q[3], q[0])
                    } else ImuSample(e.timestamp, ch, v[0], v[1], v[2])
                )
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        val periodUs = 1_000_000 / hz
        CHANNELS.forEach { (_, type) -> sm.getDefaultSensor(type)?.let { sm.registerListener(l, it, periodUs) } }
        listener = l
    }

    override fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
    }

    private companion object {
        val CHANNELS = listOf(
            ImuChannel.ACCEL to Sensor.TYPE_ACCELEROMETER,
            ImuChannel.GYRO to Sensor.TYPE_GYROSCOPE,
            ImuChannel.ROTATION to Sensor.TYPE_ROTATION_VECTOR,
        )
    }
}
