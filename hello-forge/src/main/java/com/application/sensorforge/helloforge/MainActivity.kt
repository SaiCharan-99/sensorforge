package com.application.sensorforge.helloforge

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.sdk.Verifier
import com.application.sensorforge.sdk.VerifierResult
import java.io.File

/**
 * HelloForge (S19): the two-line import of an exported `.sfpkg`.
 *
 *     val verifier = SensorForge.load(context, packPath)
 *     verifier.start(previewView) { result -> show(result.verdict) }
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { HelloForge() } } }
    }
}

@Composable
private fun HelloForge() {
    val ctx = LocalContext.current
    val packFile = remember { File(ctx.filesDir, "pack.sfpkg") }
    var hasPack by remember { mutableStateOf(packFile.exists()) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var verifier by remember { mutableStateOf<Verifier?>(null) }
    var result by remember { mutableStateOf<VerifierResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var startTime by remember { mutableStateOf("") }
    val previewView = remember { PreviewView(ctx) }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> packFile.outputStream().use { o -> i.copyTo(o) } }
            hasPack = true; error = null; result = null
        } catch (e: Exception) { error = "${e::class.simpleName}: ${e.message}" }
    }

    // Peek at the pack (hash + stack check) without the camera, for the footer and early typed errors.
    val info = remember(hasPack, error, startTime) {
        if (!hasPack) null to null else try { SensorForge.load(ctx, packFile.absolutePath, params(startTime)).pack to null } catch (e: Exception) { null to "${e::class.simpleName}: ${e.message}" }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("HelloForge", style = MaterialTheme.typography.headlineMedium)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
        }
        val r = result
        Text(
            r?.verdict?.name ?: if (verifier != null) "VERIFYING…" else if (hasPack) "READY" else "NO PACK",
            style = MaterialTheme.typography.displaySmall,
        )
        r?.reasons?.forEach { Text("${it.code} · ${it.ref}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(startTime, { startTime = it }, label = { Text("start_time (UTC ISO, only if the pack needs it)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ picker.launch(arrayOf("*/*")) }, enabled = verifier == null) { Text("Choose .sfpkg") }
            when {
                !granted -> Button({ camLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                verifier == null -> Button({
                    try {
                        val v = SensorForge.load(ctx, packFile.absolutePath, params(startTime))   // line 1
                        verifier = v; result = null; error = null
                        v.start(previewView) { res -> result = res; verifier = null }              // line 2
                    } catch (e: Exception) { error = "${e::class.simpleName}: ${e.message}"; verifier = null }
                }, enabled = hasPack) { Text("Start") }
                else -> Button({ verifier?.stop() }) { Text("Stop and verify") }
            }
        }
        Text(
            info.first?.let { "pack ${it.id} v${it.version} · sha256 ${it.sha256.take(16)}… · ${it.perceptionStack}" } ?: info.second ?: "no pack loaded",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
    }
}

private fun params(startTime: String): Map<String, Any> =
    if (startTime.isBlank()) emptyMap() else mapOf("start_time" to startTime.trim())
