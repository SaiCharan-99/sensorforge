package com.application.sensorforge.handover

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.sensorforge.sdk.OverlayBox
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.sdk.TagSheet
import com.application.sensorforge.sdk.Verifier
import com.application.sensorforge.sdk.VerifierEvent
import com.application.sensorforge.sdk.VerifierResult
import com.application.sensorforge.sdk.handover.HandoverConfig
import com.application.sensorforge.sdk.handover.HandoverLive
import com.application.sensorforge.sdk.handover.ItemState
import com.application.sensorforge.sdk.perception.Tags
import com.application.sensorforge.sdk.runtime.SessionLog
import com.application.sensorforge.ui.ErrorCard
import com.application.sensorforge.ui.InfoCard
import com.application.sensorforge.ui.Mono
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.ReceiptView
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SectionTitle
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.VerdictBanner
import com.application.sensorforge.ui.prettyStep
import com.application.sensorforge.ui.statusColors
import com.application.sensorforge.ui.theme.SfTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HandoverViewModel(app: Application) : AndroidViewModel(app) {
    enum class Phase { CONFIG, LIVE, RESULT }

    data class Ui(
        val phase: Phase = Phase.CONFIG,
        val config: HandoverConfig = HandoverConfig(),
        val live: HandoverLive? = null,
        val hands: List<List<com.application.sensorforge.sdk.perception.Pt>> = emptyList(),
        val boxes: List<OverlayBox> = emptyList(),
        val aspect: Float = 9f / 16f,
        val result: VerifierResult? = null,
        val error: String? = null,
        val backend: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var verifier: Verifier? = null
    private var job: Job? = null

    fun setConfig(c: HandoverConfig) { _ui.value = _ui.value.copy(config = c, error = null) }

    fun start(view: PreviewView) {
        if (verifier != null) return
        try {
            val v = SensorForge.handoverProof(getApplication(), _ui.value.config)
            verifier = v
            _ui.value = _ui.value.copy(phase = Phase.LIVE, error = null, live = null, hands = emptyList(), boxes = emptyList(), result = null)
            job = viewModelScope.launch {
                v.events().collect { e ->
                    when (e) {
                        is VerifierEvent.Handover -> _ui.value = _ui.value.copy(live = e.live, hands = e.hands)
                        is VerifierEvent.Frame -> _ui.value = _ui.value.copy(aspect = e.aspect, boxes = e.boxes)
                        is VerifierEvent.Backend -> _ui.value = _ui.value.copy(backend = e.line)
                        is VerifierEvent.Live, is VerifierEvent.Coaching, is VerifierEvent.Pose -> Unit
                    }
                }
            }
            v.start(view) { r ->
                SessionLog.log("verify.result", "pack" to v.pack.id, "pack_sha256" to v.pack.sha256, "verdict" to r.verdict.name, "reasons" to r.reasons.joinToString { x -> "${x.code}:${x.ref}" }, "receipt" to r.receiptPath)
                _ui.value = _ui.value.copy(phase = Phase.RESULT, result = r)
            }
        } catch (e: Exception) {
            verifier = null
            _ui.value = _ui.value.copy(phase = Phase.CONFIG, error = "${e::class.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        val v = verifier ?: return
        verifier = null
        v.stop()
        job?.cancel()
    }

    fun reset() { stop(); _ui.value = Ui(config = _ui.value.config) }
}

@Composable
fun HandoverScreen(vm: HandoverViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("‹ Home") { vm.reset(); onBack() }
        Text("HandoverProof", style = MaterialTheme.typography.headlineLarge)
        if (ui.phase == HandoverViewModel.Phase.RESULT) Result(ui, vm) else ConfigAndLive(ui, vm)
    }
}

@Composable
private fun ConfigAndLive(ui: HandoverViewModel.Ui, vm: HandoverViewModel) {
    val ctx = LocalContext.current
    val live = ui.phase == HandoverViewModel.Phase.LIVE
    var camera by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camera = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val c = ui.config
    var items by remember { mutableStateOf(c.items.joinToString(",")) }
    var slip by remember { mutableStateOf(c.slipCode) }
    var steps by remember { mutableStateOf(c.minSteps.toString()) }
    var left by remember { mutableStateOf(c.partnerOnLeft) }
    var cfgError by remember { mutableStateOf<String?>(null) }
    var showTags by remember { mutableStateOf(false) }

    fun apply(): HandoverConfig? = try {
        val cfg = HandoverConfig(items = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }, slipCode = slip.trim(), minSteps = steps.trim().toInt(), partnerOnLeft = left)
        cfgError = null; vm.setConfig(cfg); cfg
    } catch (e: Exception) { cfgError = e.message ?: "Check the fields."; null }

    ui.error?.let { ErrorCard("Cannot start", it) }

    if (!live) SfCard {
        Text("Host configuration", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(items, { items = it }, label = { Text("Items (codes, comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(slip, { slip = it }, label = { Text("Signed slip code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(steps, { steps = it }, label = { Text("Steps walked at least (0 = not required)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Text("Partner side of the picture", style = MaterialTheme.typography.bodyMedium)
        if (left) PrimaryButton("Partner on left (selected)") {} else SecondaryButton("Partner on left") { left = true }
        if (!left) PrimaryButton("Partner on right (selected)") {} else SecondaryButton("Partner on right") { left = false }
        cfgError?.let { Text(it, color = SfTheme.colors.failSolid) }
        SecondaryButton(if (showTags) "Hide tags" else "Show tags to print") { if (apply() != null) showTags = !showTags }
    }
    if (!live && showTags) TagsCard(ui.config)

    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black, RoundedCornerShape(12.dp))) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        if (live) Overlay(ui)
        if (live) ui.live?.feedback?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xCC1C2430), RoundedCornerShape(12.dp)).padding(12.dp))
        }
        if (!live) Text(if (camera) "Camera off. Press Start." else "Camera permission not granted: NOT_OBSERVABLE", color = Color.White, modifier = Modifier.align(Alignment.Center))
    }

    if (!camera) PrimaryButton("Allow camera") { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }
    else if (!live) {
        PrimaryButton("Start HandoverProof") { if (apply() != null) vm.start(previewView) }
        SecondaryButton("Allow step counter and location (optional)") {
            launcher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    } else PrimaryButton("Stop and verify") { vm.stop() }

    if (live) {
        val l = ui.live
        SfCard {
            SectionTitle("Items")
            c.items.forEach { code ->
                val s = l?.items?.get(code) ?: ItemState.NOT_SEEN
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tone = when (s) {
                        ItemState.HANDED -> SfTheme.colors.pass
                        ItemState.TAKEN_BACK -> SfTheme.colors.fail
                        ItemState.OUT_OF_VIEW -> SfTheme.colors.unverified
                        else -> SfTheme.colors.pending
                    }
                    StatusChip(s.text, tone, when (s) { ItemState.HANDED -> "✓"; ItemState.TAKEN_BACK -> "✕"; ItemState.OUT_OF_VIEW -> "!"; else -> "…" })
                    Text("Item $code")
                }
            }
            Mono("slip: ${l?.slipRead ?: "not read yet"} (expects ${c.slipCode})")
            Mono("steps walked: ${l?.steps?.toString() ?: "sensor off or not allowed"}${if (c.minSteps > 0) " (needs ${c.minSteps})" else ""}")
            Mono("location: ${l?.locationAccuracyM?.let { "recorded, accuracy %.0f m (not sole evidence)".format(it) } ?: "not recorded"}")
            ui.backend?.let { Mono(it) }
        }
    }
    InfoCard("Items and the slip are read from printed tags (controlled props). Hands come from the on-device Hand Landmarker. Side zones decide who holds the item; RelateAnything (M5) is not installed, so side zones are the method.")
}

@Composable
private fun TagsCard(cfg: HandoverConfig) {
    val tags = remember(cfg) { cfg.items.map { "Item ${it}" to Tags.obj("item", it) } + ("Slip ${cfg.slipCode}" to Tags.obj("slip", cfg.slipCode)) }
    SfCard {
        SectionTitle("Printable tags (at least 4 cm)")
        tags.forEach { (label, payload) ->
            val bmp = remember(payload) { TagSheet.render(payload, 256).asImageBitmap() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(bmp, label, Modifier.size(96.dp))
                Mono(label + "\n" + payload)
            }
        }
    }
}

/** Side split line, hand points and tag boxes (upright normalised coordinates, fitted to the preview). */
@Composable
private fun Overlay(ui: HandoverViewModel.Ui) {
    val c = SfTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        val boxAspect = size.width / size.height
        val (w, h) = if (ui.aspect > boxAspect) size.width to size.width / ui.aspect else size.height * ui.aspect to size.height
        val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
        val x = ox + ui.config.splitX * w
        drawLine(c.info.border, Offset(x, oy), Offset(x, oy + h), 4f)
        ui.boxes.forEach { b ->
            val tl = Offset(ox + b.l * w, oy + b.t * h)
            drawRect(c.pass.border, tl, androidx.compose.ui.geometry.Size((b.r - b.l) * w, (b.b - b.t) * h), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
        }
        ui.hands.forEach { hand -> hand.forEach { p -> drawCircle(c.chart1, 6f, Offset(ox + p.x * w, oy + p.y * h)) } }
    }
}

@Composable
private fun Result(ui: HandoverViewModel.Ui, vm: HandoverViewModel) {
    val r = ui.result!!
    VerdictBanner(r.verdict, r.reasons.firstOrNull()?.message)
    SfCard {
        SectionTitle("Steps")
        r.steps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (sc, g) = statusColors(s.status, SfTheme.colors)
                StatusChip(s.status.name, sc, g)
                Text(prettyStep(s.id))
            }
        }
        r.reasons.forEach { Text("${it.code} · ${it.ref}\n${it.message}") }
    }
    ReceiptView(r.receiptPath)
    PrimaryButton("Run again") { vm.reset() }
}
