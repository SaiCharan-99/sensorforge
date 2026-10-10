package com.application.sensorforge.sdk.camera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import com.application.sensorforge.sdk.perception.Detection
import com.application.sensorforge.sdk.perception.YoloDecode
import com.application.sensorforge.sdk.runtime.BackendLog
import com.application.sensorforge.sdk.runtime.LatencyMeter
import java.io.File
import java.nio.FloatBuffer

/** M1 primary: YOLOE-11S (fixed vocabulary, ONNX export) on ONNX Runtime. NNAPI first, then CPU; the one used is logged. */
class OrtYoloDetector(
    context: Context, model: File, private val classes: List<String> = CLASSES, private val scoreThreshold: Float = 0.25f,
    /** Class-agnostic NMS and up to [maxResults] boxes: used for the prompt-free "box everything" proposer. */
    private val agnostic: Boolean = false, private val maxResults: Int = 10,
) : Detector {
    private val modelName = model.name
    private val env = OrtEnvironment.getEnvironment()
    private lateinit var session: OrtSession
    override var backend: String = "none"; private set
    override var lastMs = 0.0; private set
    private var meter: LatencyMeter? = null
    private val size = 416
    private val letter = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val px = IntArray(size * size)
    private val buf = FloatArray(3 * size * size)
    /** Raw detections of the last run (any class, above the detector threshold) for the debug line. */
    var lastSeen: List<Detection> = emptyList(); private set

    private fun open(model: File, ep: String): OrtSession {
        val o = OrtSession.SessionOptions()
        if (ep == "NNAPI") o.addNnapi() else o.setIntraOpNumThreads(4)
        return env.createSession(model.absolutePath, o)
    }

    init {
        BackendLog.init(context)
        // Open every available execution provider, time it on a blank frame, keep the fastest (all numbers are logged).
        val blank = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val timed = mutableListOf<Triple<String, OrtSession, Double>>()
        session = run {
            for (ep in listOf("NNAPI", "CPU")) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                try {
                    val s = open(model, ep)
                    session = s; backend = ep
                    BackendLog.record(modelName, RUNTIME, "execution provider", ep, "letterbox ${size}x$size", "load", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                    detect(blank)
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    detect(blank); detect(blank)
                    val ms = (SystemClock.elapsedRealtimeNanos() - t1) / 2e6
                    BackendLog.record(modelName, RUNTIME, "execution provider", ep, "blank 640x480", "warm", ms)
                    timed += Triple(ep, s, ms)
                } catch (e: Throwable) { Log.w(TAG, "execution provider $ep unavailable: ${e.message}") }
            }
            val best = timed.minByOrNull { it.third } ?: error("ONNX Runtime could not load ${model.name}")
            timed.filter { it !== best }.forEach { it.second.close() }
            backend = best.first
            best.second
        }
        meter = LatencyMeter(modelName, RUNTIME, "execution provider", backend, "letterbox ${size}x$size")
    }

    override fun detect(upright: Bitmap): List<Detection> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val scale = minOf(size.toFloat() / upright.width, size.toFloat() / upright.height)
        val w = upright.width * scale; val h = upright.height * scale
        val padX = (size - w) / 2; val padY = (size - h) / 2
        Canvas(letter).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(Bitmap.createScaledBitmap(upright, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), true), padX, padY, null)
        }
        letter.getPixels(px, 0, size, 0, 0, size, size)
        val plane = size * size
        for (i in 0 until plane) {
            val p = px[i]
            buf[i] = ((p shr 16) and 255) / 255f; buf[plane + i] = ((p shr 8) and 255) / 255f; buf[2 * plane + i] = (p and 255) / 255f
        }
        val dets = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { res ->
                val t = res[0] as OnnxTensor
                val shape = t.info.shape
                val ch = shape[1].toInt(); val n = shape[2].toInt()
                val fb = t.floatBuffer
                YoloDecode.decode(YoloDecode.Src { fb.get(it) }, ch, n, classes, padX, padY, scale, upright.width, upright.height, scoreThreshold, 0.5f, maxResults, agnostic)
            }
        }
        lastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        meter?.add(lastMs)
        lastSeen = dets
        return dets
    }

    override fun close() { session.close() }

    companion object {
        const val TAG = "SF.M1"
        const val RUNTIME = "ONNX Runtime Android 1.22.0"
        /** Vocabulary fixed at export time (tools/yoloe/export.py: class id -> text prompt), same order as the model's class channels. */
        val CLASSES = listOf("adapter", "box", "cable", "charger", "envelope", "papers", "phone", "seal")
    }
}
