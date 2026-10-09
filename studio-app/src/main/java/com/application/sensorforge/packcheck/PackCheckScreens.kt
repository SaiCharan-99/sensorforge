package com.application.sensorforge.packcheck

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.application.sensorforge.sdk.TagSheet
import com.application.sensorforge.sdk.VerifierResult
import com.application.sensorforge.sdk.engine.StepStatus
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.theme.MonoStyle
import com.application.sensorforge.ui.theme.SfTheme

private val STEP_LABELS = mapOf(
    "box_opened" to "Box opened",
    "charger_out" to "Charger out of the box",
    "adapter_out" to "Adapter out of the box",
    "adapter_label" to "Adapter label read",
    "box_empty" to "Box empty",
)

/** S08 idle card / live screen / S09 result, by phase. */
@Composable
fun PackCheckScreen(vm: PackCheckViewModel, ui: PackCheckViewModel.Ui) {
    when (ui.phase) {
        PackCheckViewModel.Phase.RESULT -> ResultScreen(ui.result!!, vm)
        else -> LiveScreen(vm, ui)
    }
}

@Composable
private fun LiveScreen(vm: PackCheckViewModel, ui: PackCheckViewModel.Ui) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    var showTags by remember { mutableStateOf(false) }
    val live = ui.phase == PackCheckViewModel.Phase.LIVE

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PackCheck", style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Verify · camera", SfTheme.colors.info, "◉")
            StatusChip("Controlled props (QR tags)", SfTheme.colors.pending, "▣")
        }
        ui.error?.let { Text(it, color = SfTheme.colors.failSolid) }

        // Preview + overlay; the preview stays composed so start() can bind to it.
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black, RoundedCornerShape(12.dp))) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            ui.frame?.takeIf { live }?.let { f -> Overlay(f) }
            if (live && ui.frame?.usable == false) {
                StatusChip("Camera unstable: dark or blurred", SfTheme.colors.unverified, "!", Modifier.align(Alignment.TopCenter).padding(8.dp))
            }
            ui.coaching?.takeIf { live }?.let {
                Text(
                    it.message, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color(0xCC1C2430), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
            if (!live) Text(
                if (granted) "Camera off. Press Start." else "Camera permission not granted: NOT_OBSERVABLE",
                color = Color.White, modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!granted) PrimaryButton("Allow camera") { launcher.launch(Manifest.permission.CAMERA) }
        else if (!live) PrimaryButton("Start PackCheck") { vm.start(previewView) }
        else PrimaryButton("Stop and verify") { vm.stop() }

        if (live) SfCard {
            Text("Steps (live)", style = MaterialTheme.typography.titleMedium)
            val steps = ui.live?.decision?.steps.orEmpty().associateBy { it.id }
            (ui.pack?.stepIds ?: STEP_LABELS.keys.toList()).forEach { id ->
                val observed = steps[id]?.status == StepStatus.OBSERVED
                val active = ui.live?.activeStepId == id
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(
                        if (observed) "Seen" else if (active) "Now" else "Waiting",
                        if (observed) SfTheme.colors.pass else if (active) SfTheme.colors.info else SfTheme.colors.pending,
                        if (observed) "✓" else if (active) "▶" else "·",
                    )
                    Text(STEP_LABELS[id] ?: id)
                }
            }
            ui.frame?.let { Text("%.0f fps · decode %.0f ms".format(it.fps, it.decodeMs), style = MonoStyle) }
            ui.backend?.let { Text(it, style = MonoStyle) }
        }

        if (!live) IdentityHeadCard(vm, ui.head, previewView)
        if (!live) {
            SfCard {
                Text("Props", style = MaterialTheme.typography.titleMedium)
                Text("Each prop carries a printed QR tag. Box: 4 corner tags on the rim, a floor tag inside, 'open' tag inside the lid, 'closed' tag on top. Adapter label AD-42.")
                SecondaryButton(if (showTags) "Hide tags" else "Show tags") { showTags = !showTags }
                if (showTags) {
                    var exported by remember { mutableStateOf<String?>(null) }
                    SecondaryButton("Save tag PNGs to app files") { exported = vm.exportTags().absolutePath }
                    exported?.let { Text("Saved to $it", style = MonoStyle) }
                    vm.tags().forEach { (label, payload) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(TagSheet.render(payload, 256).asImageBitmap(), label, Modifier.size(128.dp))
                            Column { Text(label); Text(payload, style = MonoStyle) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentityHeadCard(vm: PackCheckViewModel, h: PackCheckViewModel.HeadUi, previewView: PreviewView) = SfCard {
    Text("Identity head (H1)", style = MaterialTheme.typography.titleMedium)
    Text("Genuine charger vs look-alike impostor cable, both wearing the charger tag. Embedding: handcrafted YUV histogram (no image encoder yet). Trained on this phone, CPU.")
    h.counts.forEach { (c, n) -> Text("$c: ${n.first} dev · ${n.second} held-out", style = MonoStyle) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(h.heldOut, { vm.setHeldOut(it) })
        Text("Next capture is held-out")
    }
    val busy = h.capturing != null
    SecondaryButton("Capture genuine charger (4 s)", enabled = !busy) { vm.capture(previewView, "charger") }
    SecondaryButton("Capture impostor cable (4 s)", enabled = !busy) { vm.capture(previewView, "impostor_cable") }
    PrimaryButton("Train", enabled = !busy) { vm.train() }
    h.report?.let { r ->
        com.application.sensorforge.ui.HashCard("Head v${r.headVersion}", r.parentSha256, r.trainedWeightsSha256)
        com.application.sensorforge.ui.LossCurveChart(r.lossCurve, r.valLossCurve)
        Text("${r.changedParameterCount}/${r.parameterCount} params changed · ${r.trainMillis} ms · ${r.trainingBackend}", style = MonoStyle)
        Text("Held-out: " + r.heldOutMetrics.entries.joinToString { "${it.key} P %.2f R %.2f (n=%d)".format(it.value.precision, it.value.recall, it.value.support) }.ifEmpty { "none (capture held-out sessions)" }, style = MonoStyle)
        if (h.active != r.headVersion) PrimaryButton("Approve v${r.headVersion}") { vm.approve() }
    }
    Text("Active: " + (h.active?.let { "v$it" } ?: "none (tags decide class)"), style = MonoStyle)
    h.message?.let { Text(it) }
    SecondaryButton("Clear examples", enabled = !busy) { vm.clearExamples() }
}

@Composable
private fun Overlay(f: com.application.sensorforge.sdk.VerifierEvent.Frame) {
    val c = SfTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        // Analysis image is letterboxed into the 9:16 box with FIT_CENTER, like the preview.
        val boxAspect = size.width / size.height
        val (w, h) = if (f.aspect > boxAspect) size.width to size.width / f.aspect else size.height * f.aspect to size.height
        val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
        fun p(x: Float, y: Float) = Offset(ox + x * w, oy + y * h)
        for ((_, pts) in f.zones) {
            val path = Path().apply { moveTo(p(pts[0].x, pts[0].y).x, p(pts[0].x, pts[0].y).y); pts.drop(1).forEach { lineTo(p(it.x, it.y).x, p(it.x, it.y).y) }; close() }
            drawPath(path, c.chart3, style = Stroke(4f))
        }
        for (b in f.boxes) {
            val col = if (b.cls.startsWith("impostor")) c.failSolid else c.chart1
            drawRect(col, p(b.l, b.t), Size((b.r - b.l) * w, (b.b - b.t) * h), style = Stroke(5f))
        }
    }
}

@Composable
private fun ResultScreen(r: VerifierResult, vm: PackCheckViewModel) {
    val c = SfTheme.colors
    val (colors, glyph) = when (r.verdict) {
        Verdict.PASS -> c.pass to "✓"
        Verdict.FAIL -> c.fail to "✕"
        Verdict.UNVERIFIED -> c.unverified to "?"
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PackCheck result", style = MaterialTheme.typography.headlineLarge)
        Box(Modifier.fillMaxWidth().background(colors.container, RoundedCornerShape(16.dp)).padding(20.dp)) {
            Text("$glyph ${r.verdict}", style = MaterialTheme.typography.displaySmall, color = colors.content)
        }
        if (r.reasons.isNotEmpty()) SfCard {
            Text("Reasons", style = MaterialTheme.typography.titleMedium)
            r.reasons.forEach { Text("${it.code} · ${it.ref}\n${it.message}") }
        }
        SfCard {
            Text("Steps", style = MaterialTheme.typography.titleMedium)
            r.steps.forEach { s ->
                val (sc, g) = when (s.status) {
                    StepStatus.OBSERVED -> c.pass to "✓"
                    StepStatus.ABSENT -> c.fail to "✕"
                    StepStatus.NOT_OBSERVABLE -> c.unverified to "?"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(s.status.name, sc, g)
                    Text((STEP_LABELS[s.id] ?: s.id) + (s.t?.let { " @ %.1f s".format(it) } ?: ""))
                }
            }
        }
        val frame = r.steps.firstOrNull { it.evidenceFrames.isNotEmpty() && it.status == StepStatus.OBSERVED }
            ?.evidenceFrames?.firstOrNull()?.let { r.evidenceFiles[it] }
            ?: r.evidenceFiles.values.lastOrNull()
        frame?.let { path ->
            BitmapFactory.decodeFile(path)?.let { bmp ->
                SfCard {
                    Text("Evidence frame", style = MaterialTheme.typography.titleMedium)
                    Image(bmp.asImageBitmap(), "evidence", Modifier.fillMaxWidth())
                }
            }
        }
        SfCard {
            Text("Timeline", style = MaterialTheme.typography.titleMedium)
            Text("${r.timeline.clipId} · ${r.timeline.perceptionStack}", style = MonoStyle)
            Text("%.1f s · %d tracks · %d primitives".format(r.timeline.durationS, r.timeline.tracks.size, r.timeline.primitives.size), style = MonoStyle)
            Text("Receipt: added in Phase 4", style = MonoStyle)
        }
        PrimaryButton("New check") { vm.reset() }
    }
}
