package com.application.sensorforge.sdk.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.Detection
import com.application.sensorforge.sdk.runtime.BackendLog
import com.application.sensorforge.sdk.runtime.LatencyMeter
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * M1 learned detector: MediaPipe Tasks ObjectDetector (EfficientDet-Lite, TFLite).
 * Tries the GPU delegate, falls back to CPU; the backend actually used is logged (rule 4).
 * Input is an upright bitmap; boxes come back upright and normalised.
 */
class MpObjectDetector(
    context: Context,
    val modelAsset: String = "models/efficientdet_lite0.tflite",
    private val scoreThreshold: Float = 0.35f,
    private val maxResults: Int = 10,
) : AutoCloseable {
    val backend: String
    private val detector: ObjectDetector
    var lastMs = 0.0; private set

    private val meter: LatencyMeter

    init {
        val t0 = SystemClock.elapsedRealtimeNanos()
        var made: Pair<ObjectDetector, String>? = null
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val opts = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).setDelegate(d).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(scoreThreshold)
                    .setMaxResults(maxResults)
                    .build()
                made = ObjectDetector.createFromOptions(context.applicationContext, opts) to d.name
                break
            } catch (e: Exception) {
                Log.w(TAG, "delegate $d unavailable: ${e.message}")
            }
        }
        val (det, be) = made ?: error("No MediaPipe delegate could load $modelAsset")
        detector = det; backend = be
        Log.i(TAG, "loaded $modelAsset runtime MediaPipe Tasks 0.10.21 (TFLite) delegate $backend")
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        BackendLog.init(context)
        BackendLog.record(modelAsset.substringAfterLast('/'), "MediaPipe Tasks 0.10.21 (TFLite)", "delegate", backend, "upright bitmap", "load", ms)
        meter = LatencyMeter(modelAsset.substringAfterLast('/'), "MediaPipe Tasks 0.10.21 (TFLite)", "delegate", backend, "upright bitmap")
    }

    fun detect(upright: Bitmap): List<Detection> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val r = detector.detect(BitmapImageBuilder(upright).build())
        lastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        meter.add(lastMs)
        val w = upright.width.toFloat(); val h = upright.height.toFloat()
        return r.detections().mapNotNull { d ->
            val c = d.categories().firstOrNull() ?: return@mapNotNull null
            val b = d.boundingBox()
            Detection(c.categoryName().replace(' ', '_'), BBox(b.left / w, b.top / h, b.right / w, b.bottom / h), c.score().toDouble())
        }
    }

    override fun close() = detector.close()

    companion object { const val TAG = "SF.M1" }
}
