package com.application.sensorforge.repright

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.sdk.Verifier
import com.application.sensorforge.sdk.VerifierEvent
import com.application.sensorforge.sdk.VerifierResult
import com.application.sensorforge.sdk.engine.StepStatus
import com.application.sensorforge.sdk.pose.Exercise
import com.application.sensorforge.sdk.pose.RepConfig
import com.application.sensorforge.sdk.pose.RepLive
import com.application.sensorforge.sdk.pose.Side
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RepRightViewModel(app: Application) : AndroidViewModel(app) {
    enum class Phase { CONFIG, LIVE, RESULT }

    data class Ui(
        val phase: Phase = Phase.CONFIG,
        val live: RepLive? = null,
        val points: List<com.application.sensorforge.sdk.perception.Pt>? = null,
        val vis: List<Float>? = null,
        val aspect: Float = 9f / 16f,
        val config: RepConfig = RepConfig.kneeExtension(),
        val result: VerifierResult? = null,
        val error: String? = null,
        val backend: String? = null,
        val stepsSeen: Set<String> = emptySet(),
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var verifier: Verifier? = null
    private var job: Job? = null

    fun setConfig(c: RepConfig) { _ui.value = _ui.value.copy(config = c, error = null) }

    fun start(view: PreviewView) {
        if (verifier != null) return
        try {
            val v = SensorForge.repRight(getApplication(), _ui.value.config)
            verifier = v
            _ui.value = _ui.value.copy(phase = Phase.LIVE, error = null, live = null, points = null, result = null, stepsSeen = emptySet())
            job = viewModelScope.launch {
                v.events().collect { e ->
                    when (e) {
                        is VerifierEvent.Pose -> _ui.value = _ui.value.copy(live = e.live, points = e.points, vis = e.visibility)
                        is VerifierEvent.Frame -> _ui.value = _ui.value.copy(aspect = e.aspect)
                        is VerifierEvent.Live -> _ui.value = _ui.value.copy(stepsSeen = e.decision.steps.filter { it.status == StepStatus.OBSERVED }.map { it.id }.toSet())
                        is VerifierEvent.Backend -> _ui.value = _ui.value.copy(backend = e.line)
                        is VerifierEvent.Coaching -> Unit
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
fun RepRightScreen(vm: RepRightViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("‹ Home") { vm.reset(); onBack() }
        Text("RepRight", style = MaterialTheme.typography.headlineLarge)
        when (ui.phase) {
            RepRightViewModel.Phase.RESULT -> Result(ui, vm)
            else -> ConfigAndLive(ui, vm)
        }
    }
}

private fun num(s: String) = s.trim().toDoubleOrNull()

@Composable
private fun ConfigAndLive(ui: RepRightViewModel.Ui, vm: RepRightViewModel) {
    val ctx = LocalContext.current
    val live = ui.phase == RepRightViewModel.Phase.LIVE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val c = ui.config

    // S14b: therapist configuration (editable only while not running).
    var tree by remember { mutableStateOf(c.exercise == Exercise.TREE_POSE) }
    var side by remember { mutableStateOf(c.side) }
    var reps by remember { mutableStateOf(c.reps.toString()) }
    var amin by remember { mutableStateOf(c.angleMin.toInt().toString()) }
    var amax by remember { mutableStateOf(c.angleMax.toInt().toString()) }
    var hold by remember { mutableStateOf(c.holdS.toString()) }
    var tmin by remember { mutableStateOf(c.tempoMinS.toString()) }
    var tmax by remember { mutableStateOf(c.tempoMaxS.toString()) }
    var cfgError by remember { mutableStateOf<String?>(null) }

    fun apply(): RepConfig? = try {
        val base = if (tree) RepConfig.treePose(side) else RepConfig.kneeExtension(side)
        val cfg = base.copy(
            reps = if (tree) 1 else reps.trim().toInt(), angleMin = num(amin)!!, angleMax = num(amax)!!, holdS = num(hold)!!,
            tempoMinS = if (tree) 0.0 else num(tmin)!!, tempoMaxS = if (tree) 600.0 else num(tmax)!!,
        )
        cfgError = null; vm.setConfig(cfg); cfg
    } catch (e: Exception) { cfgError = if (e is IllegalArgumentException && e.message?.startsWith("reps") == true) e.message else "Check the numbers: ${e.message ?: "all fields need a number"}"; null }

    ui.error?.let { ErrorCard("Cannot start", it) }

    if (!live) SfCard {
        Text("Therapist configuration", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tree) SecondaryButton("Knee extension") { tree = false; amin = "160"; amax = "185"; hold = "1.0" }
            else PrimaryButton("Knee extension") {}
            if (!tree) SecondaryButton("Tree pose") { tree = true; amin = "165"; amax = "185"; hold = "5.0" } else PrimaryButton("Tree pose") {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (side == Side.LEFT) PrimaryButton(if (tree) "Standing: left" else "Left leg") {} else SecondaryButton(if (tree) "Standing: left" else "Left leg") { side = Side.LEFT }
            if (side == Side.RIGHT) PrimaryButton(if (tree) "Standing: right" else "Right leg") {} else SecondaryButton(if (tree) "Standing: right" else "Right leg") { side = Side.RIGHT }
        }
        if (!tree) NumField("Repetitions", reps, KeyboardType.Number) { reps = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(if (tree) "Standing knee min °" else "Angle min °", amin, KeyboardType.Number, Modifier.weight(1f)) { amin = it }
            NumField("max °", amax, KeyboardType.Number, Modifier.weight(1f)) { amax = it }
        }
        NumField("Hold at least (s)", hold, KeyboardType.Decimal) { hold = it }
        if (!tree) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Rep time min (s)", tmin, KeyboardType.Decimal, Modifier.weight(1f)) { tmin = it }
            NumField("max (s)", tmax, KeyboardType.Decimal, Modifier.weight(1f)) { tmax = it }
        } else Text("Lifted knee must be bent 20–100° at the same time.", style = MaterialTheme.typography.bodyMedium)
        cfgError?.let { Text(it, color = SfTheme.colors.failSolid) }
    }

    // Preview + skeleton (S14)
    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black, RoundedCornerShape(12.dp))) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        if (live) Skeleton(ui)
        if (live && ui.live?.keypointsOk == false) StatusChip("Keypoints lost: not counted", SfTheme.colors.unverified, "!", Modifier.align(Alignment.TopCenter).padding(8.dp))
        ui.live?.feedback?.takeIf { live }?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xCC1C2430), RoundedCornerShape(12.dp)).padding(12.dp))
        }
        if (!live) Text(if (granted) "Camera off. Press Start." else "Camera permission not granted: NOT_OBSERVABLE", color = Color.White, modifier = Modifier.align(Alignment.Center))
    }

    if (!granted) PrimaryButton("Allow camera") { launcher.launch(Manifest.permission.CAMERA) }
    else if (!live) PrimaryButton("Start RepRight") { if (apply() != null) vm.start(previewView) }
    else PrimaryButton("Stop and verify") { vm.stop() }

    if (live) {
        val l = ui.live
        SfCard {
            Text(if (c.exercise == Exercise.TREE_POSE) "Tree pose" else "Reps ${l?.repsDone ?: 0} / ${c.reps}", style = MaterialTheme.typography.headlineMedium)
            AngleBar(l?.angleDeg, c.angleMin, c.angleMax)
            Mono("angle ${l?.angleDeg?.let { "%.0f°".format(it) } ?: "—"} · target ${c.angleMin.toInt()}–${c.angleMax.toInt()}° · ${l?.phase ?: "waiting"}")
            Mono("hold %.1f / %.1f s".format(l?.holdProgressS ?: 0.0, c.holdS))
            if (c.exercise == Exercise.KNEE_EXTENSION) Mono("rep time ${c.tempoMinS}–${c.tempoMaxS} s")
            ui.backend?.let { Mono(it) }
        }
    }
    InfoCard("Meets-criteria wording only: RepRight checks the angles, hold and tempo you configured. It is not medical advice or a diagnosis.")
}

@Composable
private fun NumField(label: String, value: String, kb: KeyboardType, modifier: Modifier = Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = modifier.fillMaxWidth())

/** Live joint angle on a 0–180° bar with the target range shaded. */
@Composable
private fun AngleBar(angle: Double?, min: Double, max: Double) {
    val c = SfTheme.colors
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        fun x(a: Double) = (a.coerceIn(0.0, 180.0) / 180.0 * size.width).toFloat()
        drawRect(c.pending.container, Offset.Zero, size)
        drawRect(c.pass.border, Offset(x(min), 0f), Size(x(max) - x(min), size.height))
        angle?.let { drawRect(Color.Black, Offset(x(it) - 3f, 0f), Size(6f, size.height)) }
    }
}

private val BONES = listOf(
    11 to 12, 11 to 23, 12 to 24, 23 to 24,
    23 to 25, 25 to 27, 24 to 26, 26 to 28,
    11 to 13, 13 to 15, 12 to 14, 14 to 16,
)

@Composable
private fun Skeleton(ui: RepRightViewModel.Ui) {
    val pts = ui.points ?: return
    val vis = ui.vis
    val c = SfTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        val boxAspect = size.width / size.height
        val (w, h) = if (ui.aspect > boxAspect) size.width to size.width / ui.aspect else size.height * ui.aspect to size.height
        val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
        fun p(i: Int) = Offset(ox + pts[i].x * w, oy + pts[i].y * h)
        for ((a, b) in BONES) if (a < pts.size && b < pts.size && (vis?.get(a) ?: 1f) > 0.5f && (vis?.get(b) ?: 1f) > 0.5f) drawLine(c.chart1, p(a), p(b), 6f)
        for (i in 11 until minOf(pts.size, 29)) drawCircle(if ((vis?.get(i) ?: 1f) > 0.5f) c.pass.border else c.unverified.border, 7f, p(i))
    }
}

@Composable
private fun Result(ui: RepRightViewModel.Ui, vm: RepRightViewModel) {
    val r = ui.result!!
    VerdictBanner(r.verdict, r.reasons.firstOrNull()?.message)
    SfCard {
        SectionTitle("Steps")
        r.steps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (sc, g) = com.application.sensorforge.ui.statusColors(s.status, SfTheme.colors)
                StatusChip(s.status.name, sc, g)
                Text(prettyStep(s.id))
            }
        }
        r.reasons.forEach { Text("${it.code} · ${it.ref}\n${it.message}") }
    }
    ReceiptView(r.receiptPath)
    PrimaryButton("Run again") { vm.reset() }
}
