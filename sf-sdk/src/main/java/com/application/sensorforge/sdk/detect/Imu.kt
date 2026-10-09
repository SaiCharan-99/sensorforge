package com.application.sensorforge.sdk.detect

/** IMU channels captured for CrashGuard (tech spec §17). */
enum class ImuChannel { ACCEL, GYRO, ROTATION }

/**
 * One IMU reading. `tNanos` is monotonic (elapsedRealtimeNanos on device).
 * ACCEL in m/s², GYRO in rad/s, ROTATION is a unit quaternion (x, y, z, w).
 */
data class ImuSample(
    val tNanos: Long,
    val channel: ImuChannel,
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float = 0f,
)

/**
 * Source of IMU samples. Live sensors and SIMULATION replays implement the same
 * interface (tech spec §20), so downstream code cannot tell them apart except
 * through [isSimulation], which the UI must surface.
 */
interface SensorObservation {
    val isSimulation: Boolean
    fun start(sink: (ImuSample) -> Unit)
    fun stop()
}
