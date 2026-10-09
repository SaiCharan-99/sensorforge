package com.application.sensorforge.crashguard

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Local attention alarm for a possible impact: blinking flashlight (torch only, no camera capture,
 * no CAMERA permission), looping alarm tone on the alarm stream, repeating vibration.
 * Purely on-device; it contacts no one.
 */
class Alarm(private val context: Context, private val scope: CoroutineScope) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val torchId: String? = runCatching {
        cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var blink: Job? = null
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null

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
        ringtone?.stop(); ringtone = null
        tone?.release(); tone = null
        runCatching { vibrator?.cancel() }
    }

    private fun startSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val r = uri?.let { runCatching { RingtoneManager.getRingtone(context, it) }.getOrNull() }
        if (r != null) {
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
        } else {
            // No alarm sound configured: fall back to a generated tone on the alarm stream.
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
                ?.also { it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK) }
        }
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        runCatching { cameras.setTorchMode(id, on) }
    }

    private companion object { const val TAG = "SF.CG" }
}
