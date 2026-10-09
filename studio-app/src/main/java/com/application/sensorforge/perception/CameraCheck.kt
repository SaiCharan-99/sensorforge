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
data class AnalysisStats(val width: Int = 0, val height: Int = 0, val rotation: Int = 0, val fps: Double = 0.0, val frames: Long = 0)

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
    val previewView = remember { PreviewView(ctx) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
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
            var count = 0L
            analysis.setAnalyzer(executor) { img ->
                try {
                    val now = SystemClock.elapsedRealtimeNanos()
                    stamps.addLast(now); count++
                    while (stamps.isNotEmpty() && now - stamps.first() > 1_000_000_000L) stamps.removeFirst()
                    if (count % 15 == 0L) {
                        val s = AnalysisStats(img.width, img.height, img.imageInfo.rotationDegrees, stamps.size.toDouble(), count)
                        stats = s
                        Log.i(TAG, "analysis ${s.width}x${s.height} rot=${s.rotation} fps=${s.fps} frames=${s.frames}")
                    }
                } finally {
                    img.close()
                }
            }
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView({ previewView }, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        Text(
            if (stats.frames == 0L) "Waiting for frames…"
            else "LIVE analysis ${stats.width}×${stats.height}, rot ${stats.rotation}°, ${"%.0f".format(stats.fps)} fps (${stats.frames} frames)",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val TAG = "SF.CAM"
