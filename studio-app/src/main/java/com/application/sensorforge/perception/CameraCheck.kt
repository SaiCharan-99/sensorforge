package com.application.sensorforge.perception

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.SfCard
import java.util.concurrent.Executors

/** Live analysis stats: frame size, rotation and measured fps over the last second. */
data class AnalysisStats(
    val width: Int = 0, val height: Int = 0, val rotation: Int = 0, val fps: Double = 0.0, val frames: Long = 0,
    val dets: List<com.application.sensorforge.sdk.perception.Detection> = emptyList(), val detMs: Double = 0.0, val backend: String = "",
)

/**
 * Camera check (Phase 1 leftover): CameraX Preview + ImageAnalysis with
 * STRATEGY_KEEP_ONLY_LATEST, foreground only (unbinds with the lifecycle).
 * Every ImageProxy is closed. Logs to SF.CAM.
 */
@Composable
fun CameraCheckCard() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    SfCard {
        Text("Camera check", style = MaterialTheme.typography.titleMedium)
        if (!granted) {
            Text("Camera permission not granted: NOT_OBSERVABLE", style = MaterialTheme.typography.bodyMedium)
            PrimaryButton("Allow camera") { launcher.launch(Manifest.permission.CAMERA) }
        } else {
            CameraPreview()
        }
    }
}

@Composable
private fun CameraPreview() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var stats by remember { mutableStateOf(AnalysisStats()) }
    // Dataset capture for detector fine-tuning: upright JPEGs, ~3 per second, app-private.
    val recording = remember { java.util.concurrent.atomic.AtomicReference<java.io.File?>(null) }
    var recDir by remember { mutableStateOf<java.io.File?>(null) }
    var saved by remember { mutableStateOf(0) }
    val previewView = remember { PreviewView(ctx) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        var detector: com.application.sensorforge.sdk.camera.MpObjectDetector? = null
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get()
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val stamps = ArrayDeque<Long>()
            var lastSave = 0L
            var count = 0L
            analysis.setAnalyzer(executor) { img ->
                try {
                    val det = detector ?: com.application.sensorforge.sdk.camera.MpObjectDetector(ctx).also { detector = it }
                    val rot = img.imageInfo.rotationDegrees
                    val raw = img.toBitmap()
                    val upright = if (rot == 0) raw else android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                        android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
                    val dets = det.detect(upright)
                    recording.get()?.let { dir ->
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastSave >= 333) {
                            lastSave = nowMs
                            java.io.File(dir, "f_$nowMs.jpg").outputStream().use { upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                            saved++
                        }
                    }
                    val now = SystemClock.elapsedRealtimeNanos()
                    stamps.addLast(now); count++
                    while (stamps.isNotEmpty() && now - stamps.first() > 1_000_000_000L) stamps.removeFirst()
                    val s = AnalysisStats(img.width, img.height, rot, stamps.size.toDouble(), count, dets, det.lastMs, det.backend)
                    stats = s
                    if (count % 15 == 0L) Log.i(TAG, "analysis ${s.width}x${s.height} rot=${s.rotation} fps=${s.fps} M1 ${det.backend} ${"%.1f".format(det.lastMs)} ms: " +
                        dets.joinToString { "${it.cls} %.2f".format(it.confidence) })
                } finally {
                    img.close()
                }
            }
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            provider?.unbindAll()
            executor.execute { detector?.close() }
            executor.shutdown()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rec = recDir != null
        PrimaryButton(if (rec) "Stop recording ($saved frames)" else "Record dataset frames") {
            if (rec) { recording.set(null); Log.i(TAG, "dataset: saved $saved frames to ${recDir?.absolutePath}"); recDir = null }
            else {
                val d = java.io.File(ctx.filesDir, "dataset/s${System.currentTimeMillis()}").apply { mkdirs() }
                saved = 0; recDir = d; recording.set(d)
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView({ previewView.apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                for (d in stats.dets) {
                    val tl = androidx.compose.ui.geometry.Offset(d.box.l * size.width, d.box.t * size.height)
                    drawRect(androidx.compose.ui.graphics.Color(0xFF4F5BD5), tl,
                        androidx.compose.ui.geometry.Size(d.box.w * size.width, d.box.h * size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                }
            }
            Column(Modifier.padding(8.dp)) {
                stats.dets.forEach {
                    Text("${it.cls} ${"%.0f".format(it.confidence * 100)}%", color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.background(androidx.compose.ui.graphics.Color(0xAA1C2430)).padding(4.dp))
                }
            }
        }
        Text(
            if (stats.frames == 0L) "Waiting for frames…"
            else "LIVE ${stats.width}×${stats.height}, ${"%.0f".format(stats.fps)} fps · EfficientDet-Lite0 (COCO) on ${stats.backend}: ${"%.0f".format(stats.detMs)} ms",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val TAG = "SF.CAM"
