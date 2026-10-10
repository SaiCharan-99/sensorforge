package com.application.sensorforge.sdk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import com.application.sensorforge.sdk.PackInfo
import com.application.sensorforge.sdk.PermissionMissing
import com.application.sensorforge.sdk.Verifier
import com.application.sensorforge.sdk.VerifierEvent
import com.application.sensorforge.sdk.VerifierResult
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.engine.Decision
import com.application.sensorforge.sdk.engine.StepResult
import com.application.sensorforge.sdk.engine.StepStatus
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.engine.VerifyEngine
import com.application.sensorforge.sdk.perception.Pt
import com.application.sensorforge.sdk.pose.Lm
import com.application.sensorforge.sdk.pose.Landmark
import com.application.sensorforge.sdk.pose.PoseFrame
import com.application.sensorforge.sdk.pose.RepAnalyzer
import com.application.sensorforge.sdk.pose.RepConfig
import com.application.sensorforge.sdk.runtime.BackendLog
import com.application.sensorforge.sdk.runtime.LatencyMeter
import com.application.sensorforge.sdk.runtime.ReceiptWriter
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * RepRight verifier (M3): CameraX frames -> MediaPipe Pose Landmarker (GPU, then CPU) ->
 * [RepAnalyzer] (therapist-configured angles, hold, tempo) -> timeline -> [VerifyEngine].
 * Keypoints lost or unclear never count as a pass.
 */
internal class PoseVerifier(
    private val context: Context,
    private val cfg: RepConfig,
    private val spec: ProcedureSpec,
    override val pack: PackInfo,
    private val modelAsset: String,
    private val outDir: File,
) : Verifier {
    private val events = MutableSharedFlow<VerifierEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var analyzer: RepAnalyzer? = null
    private var landmarker: PoseLandmarker? = null
    private var onResult: ((VerifierResult) -> Unit)? = null
    @Volatile private var running = false
    private var startNs = 0L
    private var startUtcMs = 0L
    private var clip = ""

    override fun events(): Flow<VerifierEvent> = events.asSharedFlow()

    override fun start(previewView: PreviewView?, onResult: (VerifierResult) -> Unit) {
        requireNotNull(previewView) { "RepRight needs a PreviewView" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw PermissionMissing(Manifest.permission.CAMERA)
        val owner = previewView.findViewTreeLifecycleOwner() ?: (context as? LifecycleOwner) ?: error("PreviewView is not attached to a lifecycle")
        this.onResult = onResult
        clip = "clip-${System.currentTimeMillis()}"
        File(outDir, clip).mkdirs()
        val lmk = openLandmarker()
        landmarker = lmk.first
        val backend = lmk.second
        val meter = LatencyMeter("pose_landmarker_heavy.task", "MediaPipe Tasks 0.10.21 (TFLite)", "delegate", backend, "upright bitmap 480")
        val reader = TagFrameReader()
        var an: RepAnalyzer? = null
        val exec = Executors.newSingleThreadExecutor().also { executor = it }
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        running = true
        startNs = SystemClock.elapsedRealtimeNanos(); startUtcMs = System.currentTimeMillis()

        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setResolutionSelector(selector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

        var lastLive = 0.0
        var lastLog = 0L
        val stamps = ArrayDeque<Long>()
        analysis.setAnalyzer(exec) { image ->
            try {
                if (!running) return@setAnalyzer
                val now = SystemClock.elapsedRealtimeNanos()
                val f = reader.read(image)
                if (an == null) an = RepAnalyzer(cfg, f.aspect).also { analyzer = it }
                val t = (now - startNs) / 1e9
                val t0 = SystemClock.elapsedRealtimeNanos()
                val bmp = f.colorBitmap(480)
                val res = lmk.first.detect(BitmapImageBuilder(bmp).build())
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                meter.add(ms)
                bmp.recycle()
                val pts = res.landmarks().firstOrNull()?.map { Landmark(it.x(), it.y(), it.z(), minOf(it.visibility().orElse(0f), it.presence().orElse(1f))) }
                val live = an!!.add(PoseFrame(t, if (f.quality.usable()) pts else null))
                stamps.addLast(now); while (stamps.isNotEmpty() && now - stamps.first() > 1_000_000_000L) stamps.removeFirst()
                events.tryEmit(VerifierEvent.Frame(emptyList(), emptyMap(), f.aspect, f.quality.usable(), stamps.size.toDouble(), ms))
                events.tryEmit(VerifierEvent.Pose(pts?.map { Pt(it.x, it.y) }, pts?.map { it.vis }, live))
                if (t - lastLive >= 1.0) {
                    lastLive = t
                    val d = decide(an!!.timeline(clip, t, startUtcMs))
                    events.tryEmit(VerifierEvent.Live(d, spec.steps.firstOrNull { s -> d.steps.firstOrNull { it.id == s.id }?.status != StepStatus.OBSERVED }?.id, t))
                }
                if (now - lastLog > 3_000_000_000L) {
                    lastLog = now
                    val line = "pose heavy $backend %.0f ms, %d fps, %s".format(ms, stamps.size, if (pts == null) "no person" else "person")
                    Log.i(TAG, line)
                    events.tryEmit(VerifierEvent.Backend(line))
                }
            } catch (e: Exception) {
                Log.e(TAG, "analysis failed", e)
            } finally {
                image.close()
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            Log.i(TAG, "started ${pack.id} on $backend")
        }, ContextCompat.getMainExecutor(context))
    }

    private fun openLandmarker(): Pair<PoseLandmarker, String> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).setDelegate(d).build())
                    .setRunningMode(RunningMode.IMAGE).setNumPoses(1)
                    .build()
                val l = PoseLandmarker.createFromOptions(context.applicationContext, opts)
                BackendLog.init(context)
                BackendLog.record("pose_landmarker_heavy.task", "MediaPipe Tasks 0.10.21 (TFLite)", "delegate", d.name, "upright bitmap 480", "load", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                return l to d.name
            } catch (e: Exception) {
                Log.w(TAG, "pose delegate $d unavailable: ${e.message}")
            }
        }
        error("No MediaPipe delegate could load $modelAsset")
    }

    private fun decide(tl: Timeline): Decision = try {
        VerifyEngine.evaluate(spec, tl, pack.perceptionStack)
    } catch (e: Exception) {
        Decision(Verdict.UNVERIFIED, emptyList(), spec.steps.map { StepResult(it.id, StepStatus.NOT_OBSERVABLE, it.required) })
    }

    override fun stop() {
        if (!running) return
        running = false
        provider?.unbindAll()
        val exec = executor ?: return
        exec.shutdown()
        exec.awaitTermination(2, TimeUnit.SECONDS)
        landmarker?.close()
        val an = analyzer
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - startNs
        val timeline = (an?.timeline(clip, elapsedNs / 1e9, startUtcMs)) ?: Timeline(clip, RepAnalyzer.STACK, elapsedNs / 1e9)
        val decision = decide(timeline)
        val dir = File(outDir, clip).apply { mkdirs() }
        val timelineText = Contracts.json.encodeToString(Timeline.serializer(), timeline)
        File(dir, "timeline.json").writeText(timelineText)
        val params = mapOf(
            "exercise" to cfg.exercise.name, "side" to cfg.side.name, "reps" to cfg.reps.toString(), "angle_min" to cfg.angleMin.toString(), "angle_max" to cfg.angleMax.toString(),
            "hold_s" to cfg.holdS.toString(), "tempo_min_s" to cfg.tempoMinS.toString(), "tempo_max_s" to cfg.tempoMaxS.toString(),
        )
        val receipt = ReceiptWriter.write(dir, pack.id, pack.version, pack.sha256, pack.perceptionStack, params, decision, timelineText, elapsedNs, simulation = false)
        Log.i(TAG, "stop $clip: ${decision.verdict} ${decision.reasons.map { "${it.code}:${it.ref}" }} reps=${an?.repsDone}")
        onResult?.invoke(VerifierResult(decision.verdict, decision.reasons, decision.steps, timeline, emptyMap(), receipt.absolutePath))
    }

    companion object { const val TAG = "SF.POSE" }
}
