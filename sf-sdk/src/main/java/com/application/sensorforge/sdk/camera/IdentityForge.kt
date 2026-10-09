package com.application.sensorforge.sdk.camera

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.CropEmbedding
import com.application.sensorforge.sdk.perception.Tag
import com.application.sensorforge.sdk.perception.Tags
import com.application.sensorforge.sdk.train.Example
import com.application.sensorforge.sdk.train.Head
import com.application.sensorforge.sdk.train.HeadStore
import com.application.sensorforge.sdk.train.HeadTrainer
import com.application.sensorforge.sdk.train.TrainConfig
import com.application.sensorforge.sdk.train.TrainingReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/** Crop region for identity embeddings: the tag box grown to cover the object around it. */
internal fun identityCrop(tagBox: BBox): BBox {
    val hw = tagBox.w * 1.8f; val hh = tagBox.h * 1.8f
    return BBox(tagBox.cx - hw, tagBox.cy - hh, tagBox.cx + hw, tagBox.cy + hh)
}

@Serializable
data class IdentityExample(val x: List<Float>, val label: String, val session: String, val heldOut: Boolean)

/**
 * Identity head (H1) authoring: capture crop embeddings of a tagged object under a label
 * (e.g. genuine `charger` vs look-alike `impostor_cable`, both wearing the `charger` tag),
 * then train a real head (weights change, hashed) with [HeadTrainer]. Embedding is
 * [CropEmbedding] (handcrafted; no M2 encoder yet).
 */
class IdentityForge(context: Context, procedureId: String, val tagClass: String, val classes: List<String>) {
    private val app = context.applicationContext
    val store = HeadStore(File(app.filesDir, "heads"), "identity_$procedureId")
    private val examplesFile = File(app.filesDir, "heads/identity_$procedureId/examples.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun examples(): List<IdentityExample> =
        if (examplesFile.exists()) json.decodeFromString(ListSerializer(IdentityExample.serializer()), examplesFile.readText()) else emptyList()

    fun counts(): Map<String, Pair<Int, Int>> = classes.associateWith { c ->
        val e = examples().filter { it.label == c }; e.count { !it.heldOut } to e.count { it.heldOut }
    }

    fun clearExamples() { examplesFile.delete() }

    /**
     * Captures for [seconds] from the back camera into [previewView]; every frame where the
     * `tagClass` tag is read adds one example. Calls [onDone] with the number captured.
     */
    fun capture(previewView: PreviewView, label: String, heldOut: Boolean, seconds: Double = 4.0, onDone: (Int) -> Unit) {
        require(label in classes)
        val owner = previewView.findViewTreeLifecycleOwner() ?: (previewView.context as LifecycleOwner)
        val session = "s${System.currentTimeMillis()}"
        val got = mutableListOf<IdentityExample>()
        val reader = TagFrameReader()
        val exec = Executors.newSingleThreadExecutor()
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val t0 = SystemClock.elapsedRealtime()
        var done = false
        val future = ProcessCameraProvider.getInstance(app)
        analysis.setAnalyzer(exec) { image ->
            try {
                if (done) return@setAnalyzer
                val f = reader.read(image)
                for (c in f.codes) {
                    val tag = Tags.parse(c.text) as? Tag.Obj ?: continue
                    if (tag.cls != tagClass) continue
                    f.embed(identityCrop(BBox.around(c.points, 1.4f)))?.let { got += IdentityExample(it.toList(), label, session, heldOut) }
                }
                if (SystemClock.elapsedRealtime() - t0 > seconds * 1000) {
                    done = true
                    ContextCompat.getMainExecutor(app).execute {
                        future.get().unbind(preview, analysis)
                        exec.shutdown()
                        if (got.isNotEmpty()) save(examples() + got)
                        Log.i(TAG, "captured ${got.size} '$label' examples (held-out=$heldOut)")
                        onDone(got.size)
                    }
                }
            } finally { image.close() }
        }
        future.addListener({
            val p = future.get(); p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(app))
    }

    /** Trains a new head version (not active until [approve]). */
    fun train(): Pair<Head, TrainingReport> {
        val data = examples().map { Example(it.x.toFloatArray(), it.label, it.session, it.heldOut) }
        val parent = store.active()?.let { store.load(it) }
        val (head, report) = HeadTrainer.train(
            headId = store.headId, classes = classes, data = data,
            config = TrainConfig(hidden = 8, epochs = 150), parent = parent, version = store.nextVersion(),
            datasetVersion = "${CropEmbedding.NAME}:${data.size}", trainedAt = Instant.now().toString(),
            deviceFingerprint = "${Build.MANUFACTURER} ${Build.MODEL} ${socModel()}",
            clockMillis = { SystemClock.elapsedRealtime() },
        )
        store.save(head, report)
        Log.i(TAG, "trained ${store.headId} v${head.version} in ${report.trainMillis} ms on ${report.trainingBackend}: " +
            "${report.parentSha256.take(8)} → ${report.trainedWeightsSha256.take(8)}, held-out ${report.heldOutMetrics}")
        return head to report
    }

    fun approve(version: Int) = store.promote(version)

    private fun save(all: List<IdentityExample>) {
        examplesFile.parentFile?.mkdirs()
        examplesFile.writeText(json.encodeToString(ListSerializer(IdentityExample.serializer()), all))
    }

    companion object { const val TAG = "SF.H1" }
}

private fun socModel(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"
