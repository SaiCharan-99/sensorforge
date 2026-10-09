package com.application.sensorforge.sdk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.application.sensorforge.sdk.OverlayBox
import com.application.sensorforge.sdk.PackInfo
import com.application.sensorforge.sdk.PermissionMissing
import com.application.sensorforge.sdk.Verifier
import com.application.sensorforge.sdk.VerifierEvent
import com.application.sensorforge.sdk.VerifierResult
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.engine.VerifyEngine
import com.application.sensorforge.sdk.perception.Coach
import com.application.sensorforge.sdk.perception.Detection
import com.application.sensorforge.sdk.perception.Tags
import com.application.sensorforge.sdk.perception.TimelineBuilder
import com.application.sensorforge.sdk.train.Head
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Camera verify-mode runtime (tech spec §21–§23) on tagged-prop perception.
 * Foreground only: binds to the preview's lifecycle and never runs without a visible preview.
 */
internal class CameraVerifier(
    private val context: Context,
    private val spec: ProcedureSpec,
    override val pack: PackInfo,
    /** Identity head (H1) on crop embeddings, if one is approved. */
    private val identityHead: Head?,
    private val outDir: File,
) : Verifier {
    private val events = MutableSharedFlow<VerifierEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var builder: TimelineBuilder? = null
    private var onResult: ((VerifierResult) -> Unit)? = null
    private val evidence = mutableMapOf<Long, String>()
    @Volatile private var running = false

    override fun events(): Flow<VerifierEvent> = events.asSharedFlow()

    override fun start(previewView: PreviewView?, onResult: (VerifierResult) -> Unit) {
        requireNotNull(previewView) { "Camera packs need a PreviewView" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw PermissionMissing(Manifest.permission.CAMERA)
        }
        val owner = previewView.findViewTreeLifecycleOwner() ?: (context as? LifecycleOwner)
            ?: error("PreviewView is not attached to a lifecycle")
        this.onResult = onResult
        val clip = "clip-${System.currentTimeMillis()}"
        val clipDir = File(outDir, clip).apply { mkdirs() }
        val b = TimelineBuilder(clip, Tags.STACK, targetsOf = { cls -> spec.entities.filter { it.cls == cls }.map { it.id }.ifEmpty { listOf(cls) } })
        builder = b
        evidence.clear()
        val coach = Coach(spec)
        val reader = TagFrameReader()
        val exec = Executors.newSingleThreadExecutor().also { executor = it }
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        running = true

        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        var index = 0L
        var lastLive = 0.0
        val stamps = ArrayDeque<Long>()
        val decodeMs = ArrayDeque<Double>()
        var lastLog = 0L
        analysis.setAnalyzer(exec) { image ->
            try {
                if (!running) return@setAnalyzer
                val now = SystemClock.elapsedRealtimeNanos()
                val f = reader.read(image)
                val head = identityHead
                val obs = Tags.observe(now, index++, f.codes, f.quality) { d -> classify(head, d, f) }
                val before = b.primitiveCount
                b.add(obs)
                if (b.primitivesFrom(before).any { obs.index in it.evidenceFrames }) saveEvidence(clipDir, obs.index, f.thumbnail())

                stamps.addLast(now); while (now - stamps.first() > 1_000_000_000L) stamps.removeFirst()
                decodeMs.addLast(f.decodeMs); if (decodeMs.size > 30) decodeMs.removeFirst()
                val boxes = b.activeTracks.filter { b.nowS - it.tLast < 0.3 }.map {
                    OverlayBox(it.trackId, it.cls, it.box.l, it.box.t, it.box.r, it.box.b, it.code, it.confidence)
                }
                events.tryEmit(
                    VerifierEvent.Frame(boxes, b.zones.mapValues { it.value.pts }, f.aspect, f.quality.usable(), stamps.size.toDouble(), f.decodeMs)
                )
                if (now - lastLog > 5_000_000_000L) {
                    lastLog = now
                    val sorted = decodeMs.sorted()
                    val line = "codes: ZXing core 3.5.3 QRCodeMultiReader, runtime JVM, provider CPU, ${f.width}x${f.height} rot ${f.rotation}, " +
                        "decode p50 %.1f ms p95 %.1f ms, %d fps, luma %.0f sharp %.0f".format(
                            sorted[sorted.size / 2], sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)], stamps.size,
                            f.quality.brightness, f.quality.sharpness,
                        ) + (head?.let { ", identity head v${it.version} (CPU, ${it.classes})" } ?: "")
                    Log.i(TAG, line)
                    events.tryEmit(VerifierEvent.Backend(line))
                }
                if (b.nowS - lastLive >= 1.0) {
                    lastLive = b.nowS
                    val live = VerifyEngine.evaluate(spec, b.timeline())
                    events.tryEmit(VerifierEvent.Live(live, coach.activeStep(live)?.id, b.nowS))
                    coach.check(live, b.nowS) { b.lastObservableAt(it) }?.let {
                        Log.i(TAG, "coaching ${it.stepId}: ${it.message}")
                        events.tryEmit(VerifierEvent.Coaching(it))
                    }
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
            Log.i(TAG, "started ${pack.id} v${pack.version} stack ${pack.perceptionStack}")
        }, ContextCompat.getMainExecutor(context))
    }

    override fun stop() {
        if (!running) return
        running = false
        provider?.unbindAll()
        val exec = executor ?: return
        exec.shutdown()
        exec.awaitTermination(2, TimeUnit.SECONDS)
        val b = builder ?: return
        val timeline: Timeline = b.timeline(close = true)
        val decision = VerifyEngine.evaluate(spec, timeline, pack.perceptionStack)
        val dir = File(outDir, timeline.clipId).apply { mkdirs() }
        File(dir, "timeline.json").writeText(Contracts.json.encodeToString(Timeline.serializer(), timeline))
        Log.i(TAG, "stop ${timeline.clipId}: ${decision.verdict} ${decision.reasons.map { "${it.code}:${it.ref}" }} " +
            "(${timeline.primitives.size} primitives, ${timeline.tracks.size} tracks, ${"%.1f".format(timeline.durationS)} s)")
        onResult?.invoke(VerifierResult(decision.verdict, decision.reasons, decision.steps, timeline, evidence.toMap()))
    }

    private fun classify(head: Head?, d: Detection, f: TagFrameReader.Frame): Detection {
        if (head == null || d.cls !in head.classes) return d
        val x = f.embed(identityCrop(d.box)) ?: return d
        val p = head.predict(x)
        val best = p.indices.maxBy { p[it] }
        return d.copy(cls = head.classes[best], confidence = p[best].toDouble())
    }

    private fun saveEvidence(dir: File, frame: Long, bmp: Bitmap) {
        val f = File(dir, "frame_$frame.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        evidence[frame] = f.absolutePath
    }

    companion object { const val TAG = "SF.PERC" }
}
