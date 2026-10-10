package com.application.sensorforge.sdk.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.CropEmbedding
import com.application.sensorforge.sdk.runtime.BackendLog
import com.application.sensorforge.sdk.runtime.LatencyMeter
import com.application.sensorforge.sdk.runtime.ModelRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder

/** Crop encoder behind the registry (M2): a frozen base model; heads are trained on its vectors. */
interface CropEmbedder : AutoCloseable {
    /** Catalog id of the model, e.g. `m2_mobilenet_v3` (goes into models.lock). */
    val modelId: String

    /** Embedding name stored with examples and heads. */
    val name: String
    val dim: Int
    fun embed(frame: TagFrameReader.Frame, box: BBox): FloatArray?

    /** Same encoder on an upright bitmap (clip import); [box] is normalised. */
    fun embed(bitmap: Bitmap, box: BBox): FloatArray?
    override fun close() {}
}

/** Fallback M2: handcrafted YUV histogram (claim: "handcrafted crop embedding"). */
class HistEmbedder : CropEmbedder {
    override val modelId = "m2_yuv_hist"
    override val name = CropEmbedding.NAME
    override val dim = CropEmbedding.DIM
    override fun embed(frame: TagFrameReader.Frame, box: BBox) = frame.embed(box)

    override fun embed(bitmap: Bitmap, box: BBox): FloatArray? {
        val w = bitmap.width; val h = bitmap.height
        val x0 = (box.l * w).toInt().coerceIn(0, w); val y0 = (box.t * h).toInt().coerceIn(0, h)
        val x1 = (box.r * w).toInt().coerceIn(0, w); val y1 = (box.b * h).toInt().coerceIn(0, h)
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        return CropEmbedding.compute(x0, y0, x1, y1) { x, y ->
            val p = px[y * w + x]
            val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
            val yy = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            val u = (128 - 0.168736f * r - 0.331264f * g + 0.5f * b).toInt().coerceIn(0, 255)
            val v = (128 + 0.5f * r - 0.418688f * g - 0.081312f * b).toInt().coerceIn(0, 255)
            (yy shl 16) or (u shl 8) or v
        }
    }
}

/** M2: MediaPipe ImageEmbedder with MobileNet-V3-large (TFLite), L2-normalised, GPU then CPU. */
class MpImageEmbedder(context: Context, private val asset: String = "models/mobilenet_v3_large.tflite") : CropEmbedder {
    override val modelId = "m2_mobilenet_v3"
    override val name = "mobilenet_v3_large/l2"
    private val embedder: ImageEmbedder
    val backend: String
    override val dim: Int
    private val meter: LatencyMeter

    init {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val probe = BitmapImageBuilder(Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)).build()
        var made: Triple<ImageEmbedder, String, Int>? = null
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val o = ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(asset).setDelegate(d).build())
                    .setRunningMode(RunningMode.IMAGE).setL2Normalize(true).build()
                val e = ImageEmbedder.createFromOptions(context.applicationContext, o)
                // Probe: tells us the vector size and warms the delegate.
                val size = e.embed(probe).embeddingResult().embeddings()[0].floatEmbedding().size
                made = Triple(e, d.name, size)
                break
            } catch (e: Exception) {
                Log.w(TAG, "delegate $d unavailable: ${e.message}")
            }
        }
        val (e, be, size) = made ?: error("No MediaPipe delegate could load $asset")
        embedder = e; backend = be; dim = size
        val rt = "MediaPipe Tasks 0.10.21 (TFLite)"
        BackendLog.init(context)
        BackendLog.record("mobilenet_v3_large.tflite", rt, "delegate", backend, "224x224 crop", "load", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, "dim $dim")
        meter = LatencyMeter("mobilenet_v3_large.tflite", rt, "delegate", backend, "224x224 crop")
        Log.i(TAG, "loaded $asset dim $dim delegate $backend")
    }

    override fun embed(frame: TagFrameReader.Frame, box: BBox): FloatArray? {
        val bmp = frame.cropBitmap(box) ?: return null
        val t0 = SystemClock.elapsedRealtimeNanos()
        val v = embedder.embed(BitmapImageBuilder(bmp).build()).embeddingResult().embeddings()[0].floatEmbedding()
        meter.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        return v
    }

    override fun embed(bitmap: Bitmap, box: BBox): FloatArray? {
        val w = bitmap.width; val h = bitmap.height
        val x0 = (box.l * w).toInt().coerceIn(0, w - 1); val y0 = (box.t * h).toInt().coerceIn(0, h - 1)
        val x1 = (box.r * w).toInt().coerceIn(x0 + 1, w); val y1 = (box.b * h).toInt().coerceIn(y0 + 1, h)
        if (x1 - x0 < 8 || y1 - y0 < 8) return null
        val crop = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0), 224, 224, true)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val v = embedder.embed(BitmapImageBuilder(crop).build()).embeddingResult().embeddings()[0].floatEmbedding()
        meter.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        return v
    }

    override fun close() = embedder.close()

    companion object { const val TAG = "SF.M2" }
}

/** Chooses the M2 encoder: the installed MobileNet embedder, else the handcrafted histogram. */
object Embedders {
    private var cached: CropEmbedder? = null

    @Synchronized
    fun best(context: Context): CropEmbedder = cached ?: create(context, null).also { cached = it }

    /** Encoder a pack was frozen with (its `models.lock` names it); [best] when it names none. */
    @Synchronized
    fun forLock(context: Context, lockedIds: Collection<String>): CropEmbedder = when {
        "m2_mobilenet_v3" in lockedIds -> if (cached?.modelId == "m2_mobilenet_v3") cached!! else create(context, "m2_mobilenet_v3")
        "m2_yuv_hist" in lockedIds -> HistEmbedder()
        else -> best(context)
    }

    private fun create(context: Context, force: String?): CropEmbedder {
        val reg = ModelRegistry.get(context)
        val useMp = force == "m2_mobilenet_v3" || (force == null && reg.state("m2_mobilenet_v3").usable)
        if (useMp) try { return MpImageEmbedder(context) } catch (e: Exception) { Log.w("SF.M2", "M2 unavailable, using histogram: ${e.message}") }
        return HistEmbedder()
    }

    @Synchronized
    fun reset() { cached?.close(); cached = null }
}
