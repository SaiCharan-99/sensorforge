package com.application.sensorforge.sdk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import com.application.sensorforge.sdk.OverlayBox
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
import com.application.sensorforge.sdk.handover.HandObs
import com.application.sensorforge.sdk.handover.HandoverAnalyzer
import com.application.sensorforge.sdk.handover.HandoverConfig
import com.application.sensorforge.sdk.handover.HandoverFrame
import com.application.sensorforge.sdk.handover.HandoverSpecs
import com.application.sensorforge.sdk.handover.ItemObs
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.Pt
import com.application.sensorforge.sdk.perception.Tag
import com.application.sensorforge.sdk.perception.Tags
import com.application.sensorforge.sdk.runtime.BackendLog
import com.application.sensorforge.sdk.runtime.LatencyMeter
import com.application.sensorforge.sdk.runtime.ReceiptWriter
import com.application.sensorforge.sdk.sensors.LocationTracker
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HandoverProof verifier: CameraX frames -> item/slip tags (ZXing) + MediaPipe Hand Landmarker (M4)
 * -> [HandoverAnalyzer] (side zones) + step counter + location -> timeline -> [VerifyEngine].
 * Steps and location are optional sensors: if missing, the steps step reads NOT_OBSERVABLE (UNVERIFIED).
 */
internal class HandoverVerifier(
    private val context: Context,
    private val cfg: HandoverConfig,
    private val spec: ProcedureSpec,
    override val pack: PackInfo,
    private val modelAsset: String,
    private val outDir: File,
) : Verifier {
    private val events = MutableSharedFlow<VerifierEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var landmarker: HandLandmarker? = null
    private var onResult: ((VerifierResult) -> Unit)? = null
    private val analyzer = HandoverAnalyzer(cfg)
    private val lock = Any()
    private val location = LocationTracker(context)
    private var sensors: SensorManager? = null
    private var stepListener: SensorEventListener? = null
    @Volatile private var running = false
    private var startNs = 0L
    private var startUtcMs = 0L
    private var clip = ""

    override fun events(): Flow<VerifierEvent> = events.asSharedFlow()

    private fun now() = (SystemClock.elapsedRealtimeNanos() - startNs) / 1e9

    override fun start(previewView: PreviewView?, onResult: (VerifierResult) -> Unit) {
        requireNotNull(previewView) { "HandoverProof needs a PreviewView" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw PermissionMissing(Manifest.permission.CAMERA)
        val owner = previewView.findViewTreeLifecycleOwner() ?: (context as? LifecycleOwner) ?: error("PreviewView is not attached to a lifecycle")
        this.onResult = onResult
        clip = "clip-${System.currentTimeMillis()}"
        File(outDir, clip).mkdirs()
        val (lmk, backend) = openLandmarker()
        landmarker = lmk
        val meter = LatencyMeter("hand_landmarker.task", "MediaPipe Tasks 0.10.21 (TFLite)", "delegate", backend, "upright bitmap 480")
        val reader = TagFrameReader()
        val exec = Executors.newSingleThreadExecutor().also { executor = it }
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        running = true
        startNs = SystemClock.elapsedRealtimeNanos(); startUtcMs = System.currentTimeMillis()
        startSteps()
        location.start()

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
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val f = reader.read(image)
                val t = (nowNs - startNs) / 1e9
                val usable = f.quality.usable()

                val items = mutableListOf<ItemObs>(); val slips = mutableListOf<String>(); val boxes = mutableListOf<OverlayBox>()
                for (c in f.codes) {
                    val tag = Tags.parse(c.text) as? Tag.Obj ?: continue
                    if (c.points.isEmpty()) continue
                    val box = BBox.around(c.points, grow = 1.4f)
                    when {
                        tag.cls == "item" && tag.instance in cfg.items -> { items += ItemObs(tag.instance!!, box); boxes += OverlayBox("item_${tag.instance}", "item", box.l, box.t, box.r, box.b, tag.instance, Tags.DECODE_CONFIDENCE) }
                        tag.cls == "slip" -> { slips += tag.instance.orEmpty(); boxes += OverlayBox("slip", "slip", box.l, box.t, box.r, box.b, tag.instance, Tags.DECODE_CONFIDENCE) }
                    }
                }
                val t0 = SystemClock.elapsedRealtimeNanos()
                val bmp = f.colorBitmap(480)
                val res = lmk.detect(BitmapImageBuilder(bmp).build())
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                meter.add(ms)
                bmp.recycle()
                val hands = res.landmarks().map { h -> HandObs(h.map { Pt(it.x(), it.y()) }) }

                val live = synchronized(lock) { analyzer.add(HandoverFrame(t, usable, items, if (usable) hands else emptyList(), slips)) }
                stamps.addLast(nowNs); while (stamps.isNotEmpty() && nowNs - stamps.first() > 1_000_000_000L) stamps.removeFirst()
                events.tryEmit(VerifierEvent.Frame(boxes, emptyMap(), f.aspect, usable, stamps.size.toDouble(), ms))
                events.tryEmit(VerifierEvent.Handover(hands.map { h -> h.points }, cfg.splitX, cfg.partnerOnLeft, live))
                if (t - lastLive >= 1.0) {
                    lastLive = t
                    pollLocation(t)
                    val d = decide(synchronized(lock) { analyzer.timeline(clip, t, startUtcMs) })
                    events.tryEmit(VerifierEvent.Live(d, spec.steps.firstOrNull { s -> d.steps.firstOrNull { it.id == s.id }?.status != StepStatus.OBSERVED }?.id, t))
                }
                if (nowNs - lastLog > 3_000_000_000L) {
                    lastLog = nowNs
                    val line = "hands $backend %.0f ms, %d fps, %d hand(s)".format(ms, stamps.size, hands.size)
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

    private fun pollLocation(t: Double) {
        val fix = location.lastFix() ?: return
        if (fix.ageMs <= 120_000) synchronized(lock) { analyzer.onLocation(t, fix.accuracyM) }
    }

    /** Step counter needs ACTIVITY_RECOGNITION; without it (or the sensor) the steps step is NOT_OBSERVABLE. */
    private fun startSteps() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensors = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (sensor == null || !granted) {
            Log.w(TAG, "step counter unavailable (sensor=${sensor != null}, permission=$granted)")
            synchronized(lock) { analyzer.onSteps(0.0, 0, available = false) }
            return
        }
        var base: Float? = null
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values[0]
                if (base == null) base = v
                synchronized(lock) { analyzer.onSteps(now(), (v - base!!).toInt().coerceAtLeast(0)) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        stepListener = l
        synchronized(lock) { analyzer.onSteps(0.0, 0) }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun openLandmarker(): Pair<HandLandmarker, String> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).setDelegate(d).build())
                    .setRunningMode(RunningMode.IMAGE).setNumHands(2)
                    .build()
                val l = HandLandmarker.createFromOptions(context.applicationContext, opts)
                BackendLog.init(context)
                BackendLog.record("hand_landmarker.task", "MediaPipe Tasks 0.10.21 (TFLite)", "delegate", d.name, "upright bitmap 480", "load", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                return l to d.name
            } catch (e: Exception) {
                Log.w(TAG, "hand delegate $d unavailable: ${e.message}")
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
        stepListener?.let { sensors?.unregisterListener(it) }
        location.stop()
        provider?.unbindAll()
        val exec = executor ?: return
        exec.shutdown()
        exec.awaitTermination(2, TimeUnit.SECONDS)
        landmarker?.close()
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - startNs
        val timeline = synchronized(lock) { analyzer.timeline(clip, elapsedNs / 1e9, startUtcMs) }
        val decision = decide(timeline)
        val dir = File(outDir, clip).apply { mkdirs() }
        val timelineText = Contracts.json.encodeToString(Timeline.serializer(), timeline)
        File(dir, "timeline.json").writeText(timelineText)
        val params = mapOf(
            "items" to cfg.items.joinToString(","), "slip_code" to cfg.slipCode, "min_steps" to cfg.minSteps.toString(),
            "partner_side" to (if (cfg.partnerOnLeft) "left" else "right"), "split_x" to cfg.splitX.toString(), "settle_s" to cfg.settleS.toString(),
        )
        val receipt = ReceiptWriter.write(dir, pack.id, pack.version, pack.sha256, pack.perceptionStack, params, decision, timelineText, elapsedNs, simulation = false)
        Log.i(TAG, "stop $clip: ${decision.verdict} ${decision.reasons.map { "${it.code}:${it.ref}" }} handed=${analyzer.handedCount}")
        onResult?.invoke(VerifierResult(decision.verdict, decision.reasons, decision.steps, timeline, emptyMap(), receipt.absolutePath))
    }

    companion object { const val TAG = "SF.HANDOVER" }
}
