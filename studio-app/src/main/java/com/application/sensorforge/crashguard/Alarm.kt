package com.application.sensorforge.crashguard

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Local attention alarm for a possible impact: blinking flashlight (torch only, no camera capture,
 * no CAMERA permission), high-pitch two-tone siren on the alarm stream, repeating vibration.
 * Purely on-device; it contacts no one.
 */
class Alarm(private val context: Context, private val scope: CoroutineScope) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val torchId: String? = runCatching {
        cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var blink: Job? = null
    private var track: AudioTrack? = null

    val active get() = blink != null
    val hasTorch get() = torchId != null

    fun start() {
        if (active) return
        Log.i(TAG, "alarm on (torch=${torchId != null})")
        blink = scope.launch {
            var on = false
            while (isActive) {
                on = !on
                setTorch(on)
                delay(250)
            }
        }
        startSound()
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        }
    }

    fun stop() {
        if (!active) return
        Log.i(TAG, "alarm off")
        blink?.cancel(); blink = null
        setTorch(false)
        // Torch changes are applied asynchronously; repeat "off" so a late "on" cannot leave it lit.
        scope.launch { delay(300); if (!active) setTorch(false) }
        track?.let { runCatching { it.stop() }; it.release() }; track = null
        runCatching { vibrator?.cancel() }
    }

    /** High-pitch two-tone siren (2.8 kHz / 3.5 kHz, 0.25 s each), synthesized and looped on the alarm stream. */
    private fun startSound() {
        val pcm = siren()
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    play()
                }
        }.onFailure { Log.w(TAG, "siren failed: $it") }.getOrNull()
        track = t
        Log.i(TAG, "siren ${if (t != null) "playing" else "unavailable"} (%d / %d Hz)".format(LOW_HZ, HIGH_HZ))
    }

    private fun siren(): ShortArray {
        val half = SAMPLE_RATE / 4 // 0.25 s per tone
        val fade = SAMPLE_RATE / 200 // 5 ms ramps avoid clicks at tone changes
        return ShortArray(half * 2) { i ->
            val hz = if (i < half) LOW_HZ else HIGH_HZ
            val j = i % half
            val env = minOf(1.0, j / fade.toDouble(), (half - j) / fade.toDouble())
            (sin(2 * PI * hz * i / SAMPLE_RATE) * env * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        runCatching { cameras.setTorchMode(id, on) }
    }

    private companion object {
        const val TAG = "SF.CG"
        const val SAMPLE_RATE = 48_000
        const val LOW_HZ = 2_800
        const val HIGH_HZ = 3_500
    }
}
