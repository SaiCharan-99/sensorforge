package com.application.sensorforge.sdk.scene

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.application.sensorforge.sdk.camera.CropEmbedder
import com.application.sensorforge.sdk.camera.Detector
import com.application.sensorforge.sdk.camera.DetectorChain
import com.application.sensorforge.sdk.camera.Embedders
import com.application.sensorforge.sdk.camera.OrtYoloDetector
import com.application.sensorforge.sdk.camera.TagFrameReader
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.Detection
import com.application.sensorforge.sdk.runtime.LlamaBackend
import com.application.sensorforge.sdk.runtime.ModelRegistry
import com.application.sensorforge.sdk.runtime.SessionLog
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One analysed frame: the picture, every proposed box with the detector's guess, and the boxes named by the trained head. */
class SceneFrame(val bitmap: Bitmap, val proposals: List<Detection>, val objects: List<SceneObject>, val ms: Double)

@Serializable
data class SceneExample(
    val id: String, val label: String, val session: String, val heldOut: Boolean, val embedder: String, val x: List<Float>,
    /** The tapped box this vector came from: one box makes several jittered variants, which count as one example. */
    val group: String = "",
) { val key get() = group.ifEmpty { id } }

/**
 * "Box everything, name it, train it, judge it" (SceneCheck).
 *  1. Proposals: the prompt-free YOLOE-11S (M1-pf) boxes every object it can see; without that file the
 *     fixed-vocabulary YOLOE / EfficientDet are used. The detector's own name is only a suggestion.
 *  2. Labels: the developer names boxes in captured frames; each box becomes an M2 embedding + a crop.
 *  3. Training: a small head on those embeddings is trained on this phone (real weights, hashed). The
 *     detector and encoder stay frozen; this is not detector fine-tuning.
 *  4. Inference: proposals are named by the head (below [MIN_PROB] they stay detector guesses), then
 *     [SceneJudge] decides inside/outside deterministically. The local LLM only gives a labelled opinion.
 */
class SceneLab(private val context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "scene").apply { mkdirs() }
    private val cropsDir = File(root, "crops").apply { mkdirs() }
    private val examplesFile = File(root, "examples.json")
    private val rulesFile = File(root, "rules.json")
    private val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }
    val store = HeadStore(File(app.filesDir, "heads"), HEAD_ID)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile private var running = false
    @Volatile private var activeHead: Head? = null
    /** True when the last training reserved held-out sessions automatically (none were marked by hand). */
    @Volatile var autoHeldOut = false; private set

    val embedder: CropEmbedder get() = Embedders.best(app)

    // ---- proposals ----
    private val proposerLock = Any()
    private var proposerCache: Pair<Detector, String>? = null

    /** Which proposer is in use, and why (shown in the UI). */
    fun proposerName(): String = openProposer()?.second ?: "none: no detector installed (tags only)"

    private fun openProposer(): Pair<Detector, String>? = synchronized(proposerLock) {
        proposerCache?.let { return it }
        val reg = ModelRegistry.get(app)
        for ((id, namesFile, what) in listOf(Triple("m1_yoloe_common", "yoloe-common.names", "everyday objects"), Triple("m1_yoloe_pf", "yoloe-11s-seg-pf.names", "prompt-free"))) {
            if (!reg.state(id).usable) continue
            val names = File(reg.modelsDir, namesFile)
            if (!names.exists()) continue
            try {
                val list = names.readLines().filter { it.isNotBlank() }
                val d = OrtYoloDetector(app, reg.deviceFile(id), list, 0.2f, agnostic = true, maxResults = 25)
                return (d to "YOLOE-11S $what (${list.size} names), ${d.backend}").also { proposerCache = it }
            } catch (e: Exception) { Log.w(TAG, "$id proposer failed: ${e.message}") }
        }
        DetectorChain.open(app)?.let { c -> return (c.detector to "${c.modelId} (fixed vocabulary, install the prompt-free model for 'every object')").also { proposerCache = it } }
        null
    }

    fun proposals(bitmap: Bitmap): List<Detection> = openProposer()?.first?.detect(bitmap).orEmpty()

    // ---- examples ----
    @Synchronized fun examples(): List<SceneExample> =
        if (examplesFile.exists()) runCatching { json.decodeFromString(ListSerializer(SceneExample.serializer()), examplesFile.readText()) }.getOrDefault(emptyList()) else emptyList()

    @Synchronized private fun saveExamples(all: List<SceneExample>) = examplesFile.writeText(json.encodeToString(ListSerializer(SceneExample.serializer()), all))

    /** label → (dev count, held-out count) for the current encoder. */
    fun counts(): Map<String, Pair<Int, Int>> = examples().filter { it.embedder == embedder.name }.groupBy { it.label }
        .mapValues { (_, v) -> v.filter { !it.heldOut }.map { it.key }.toSet().size to v.filter { it.heldOut }.map { it.key }.toSet().size }.toSortedMap()

    /** Saves [box] of [frame] as an example of [label]. Returns false when the crop is too small to embed. */
    @Synchronized fun addExample(frame: Bitmap, box: BBox, label: String, session: String, heldOut: Boolean): Boolean {
        val clean = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (clean.isEmpty()) return false
        val enc = embedder
        val x = enc.embed(frame, box) ?: return false
        val id = "ex_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
        // Jittered variants (box scale and shift) so the head tolerates the detector's different box tightness at run time.
        val rnd = java.util.Random(id.hashCode().toLong())
        val variants = (0 until JITTER).mapNotNull {
            val s = 0.88f + rnd.nextFloat() * 0.24f
            val w0 = (box.r - box.l) * s; val h0 = (box.b - box.t) * s
            val cx = (box.l + box.r) / 2 + (rnd.nextFloat() - 0.5f) * 0.16f * (box.r - box.l)
            val cy = (box.t + box.b) / 2 + (rnd.nextFloat() - 0.5f) * 0.16f * (box.b - box.t)
            enc.embed(frame, BBox((cx - w0 / 2).coerceIn(0f, 1f), (cy - h0 / 2).coerceIn(0f, 1f), (cx + w0 / 2).coerceIn(0f, 1f), (cy + h0 / 2).coerceIn(0f, 1f)))
        }
        val w = frame.width; val h = frame.height
        val x0 = (box.l * w).toInt().coerceIn(0, w - 1); val y0 = (box.t * h).toInt().coerceIn(0, h - 1)
        val x1 = (box.r * w).toInt().coerceIn(x0 + 1, w); val y1 = (box.b * h).toInt().coerceIn(y0 + 1, h)
        File(cropsDir, "$id.jpg").outputStream().use { Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0).compress(Bitmap.CompressFormat.JPEG, 85, it) }
        saveExamples(examples() + SceneExample(id, clean, session, heldOut, enc.name, x.toList(), id) + variants.mapIndexed { i, v -> SceneExample("${id}_j$i", clean, session, heldOut, enc.name, v.toList(), id) })
        return true
    }

    @Synchronized fun deleteLabel(label: String) {
        val all = examples()
        all.filter { it.label == label }.forEach { File(cropsDir, "${it.key}.jpg").delete() }
        saveExamples(all.filter { it.label != label })
    }

    @Synchronized fun deleteAll() { cropsDir.listFiles()?.forEach { it.delete() }; saveExamples(emptyList()); File(app.filesDir, "heads/$HEAD_ID").deleteRecursively(); activeHead = null }

    fun cropFile(id: String) = File(cropsDir, "$id.jpg")

    // ---- rules ----
    fun rules(): SceneRules = if (rulesFile.exists()) runCatching { json.decodeFromString(SceneRules.serializer(), rulesFile.readText()) }.getOrDefault(SceneRules()) else SceneRules()
    fun saveRules(r: SceneRules) = rulesFile.writeText(json.encodeToString(SceneRules.serializer(), r))

    // ---- training ----
    /** Labels that have enough dev examples to be trained; the others are left out and reported by the caller. */
    fun trainable(): List<String> = counts().filter { it.value.first >= MIN_PER_CLASS }.keys.toList()

    /** Trains a new head version on this phone; not active until [approve]. */
    fun train(): Pair<Head, TrainingReport> {
        val enc = embedder
        val use = trainable()
        require(use.size >= 2) { "Need at least 2 labels with ≥ $MIN_PER_CLASS examples each (have ${use.size})." }
        val pool = examples().filter { it.embedder == enc.name && it.label in use }
        // No explicit held-out? Reserve each label's newest session (only when the label has other sessions with enough examples left).
        val auto = mutableSetOf<String>()
        if (pool.none { it.heldOut }) for (l in use) {
            val mine = pool.filter { it.label == l }
            val sessions = mine.map { it.session }.distinct()
            if (sessions.size >= 2) {
                val newest = mine.filter { it.session == sessions.last() }
                if (mine.filter { it.session != sessions.last() }.map { it.key }.toSet().size >= MIN_PER_CLASS) auto += newest.map { it.id }
            }
        }
        autoHeldOut = auto.isNotEmpty()
        val data = pool.map { Example(it.x.toFloatArray(), it.label, it.session, it.heldOut || it.id in auto) }
        val parent = store.active()?.let { store.load(it) }?.takeIf { it.classes == use.sorted() && it.inputDim == enc.dim }
        val (head, report) = HeadTrainer.train(
            headId = HEAD_ID, classes = use.sorted(), data = data, config = TrainConfig(hidden = 16, epochs = 200, minPerClass = MIN_PER_CLASS),
            parent = parent, version = store.nextVersion(), datasetVersion = "${enc.name}:${data.size}", trainedAt = Instant.now().toString(),
            deviceFingerprint = "${Build.MANUFACTURER} ${Build.MODEL}", clockMillis = { SystemClock.elapsedRealtime() },
        )
        store.save(head, report)
        SessionLog.log("scene.train", "version" to head.version, "classes" to head.classes.size, "samples" to data.size, "ms" to report.trainMillis, "changed" to report.changedParameterCount)
        Log.i(TAG, "trained $HEAD_ID v${head.version}: ${report.parentSha256.take(8)} -> ${report.trainedWeightsSha256.take(8)} in ${report.trainMillis} ms")
        return head to report
    }

    fun approve(version: Int) { store.promote(version); activeHead = null }

    fun head(): Head? = activeHead ?: store.active()?.let { store.load(it) }?.takeIf { it.inputDim == embedder.dim }.also { activeHead = it }

    // ---- inference ----
    /** Names every proposal: head label if confident, else the detector's guess marked as such. */
    fun name(frame: Bitmap, props: List<Detection>): List<SceneObject> {
        val h = head()
        val enc = embedder
        return props.map { d ->
            val x = if (h != null) enc.embed(frame, d.box) else null
            if (h != null && x != null) {
                val p = h.predict(x); val i = p.indices.maxBy { p[it] }
                if (p[i] >= MIN_PROB) return@map SceneObject(h.classes[i], d.box, p[i].toDouble(), SceneObject.Source.HEAD)
            }
            SceneObject(d.cls, d.box, d.confidence, SceneObject.Source.DETECTOR)
        }
    }

    // ---- camera loop (foreground only) ----
    fun start(previewView: PreviewView, classify: Boolean, onFrame: (SceneFrame) -> Unit) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw SecurityException("camera permission")
        val owner = previewView.findViewTreeLifecycleOwner() ?: (context as? LifecycleOwner) ?: error("PreviewView is not attached to a lifecycle")
        val reader = TagFrameReader()
        val exec = Executors.newSingleThreadExecutor().also { executor = it }
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        running = true
        analysis.setAnalyzer(exec) { image ->
            try {
                if (!running) return@setAnalyzer
                val t0 = SystemClock.elapsedRealtimeNanos()
                val f = reader.read(image)
                if (!f.quality.usable()) return@setAnalyzer
                val bmp = f.colorBitmap(640)
                val props = proposals(bmp)
                val objs = if (classify) name(bmp, props) else props.map { SceneObject(it.cls, it.box, it.confidence, SceneObject.Source.DETECTOR) }
                onFrame(SceneFrame(bmp, props, objs, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6))
            } catch (e: Exception) { Log.e(TAG, "scene analysis failed", e) } finally { image.close() }
        }
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            val p = future.get(); provider = p; p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(app))
    }

    fun stop() {
        running = false
        provider?.unbindAll()
        executor?.shutdown(); executor = null
    }

    // ---- LLM opinion (advisory) ----
    private var llm: LlamaBackend? = null

    /** The local model's reading of the scene; null when it isn't installed or its answer can't be parsed. Blocking: call off the main thread. */
    fun llmOpinion(objects: List<SceneObject>, rules: SceneRules): SceneNarrator.Opinion? {
        val backend = llm ?: LlamaBackend.openIfInstalled(app)?.also { llm = it } ?: return null
        val reply = runCatching { backend.complete(SceneNarrator.prompt(objects, rules), 120) }.getOrNull() ?: return null
        return SceneNarrator.parse(reply)
    }

    fun llmInstalled(): Boolean = ModelRegistry.get(app).state("m6_smollm2").usable

    companion object {
        const val TAG = "SF.SCENE"
        const val HEAD_ID = "scene_objects"
        const val MIN_PER_CLASS = 4
        const val MIN_PROB = 0.6f
        const val JITTER = 5
    }
}
