package com.application.sensorforge.scene

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.sensorforge.sdk.engine.Verdict as EngineVerdict
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.Detection
import com.application.sensorforge.sdk.runtime.SessionLog
import com.application.sensorforge.sdk.scene.SceneFrame
import com.application.sensorforge.sdk.scene.SceneJudge
import com.application.sensorforge.sdk.scene.SceneLab
import com.application.sensorforge.sdk.scene.SceneNarrator
import com.application.sensorforge.sdk.scene.SceneObject
import com.application.sensorforge.sdk.scene.SceneRules
import com.application.sensorforge.sdk.scene.SceneSmoother
import com.application.sensorforge.sdk.scene.SceneVerdict
import com.application.sensorforge.sdk.scene.Verdict
import com.application.sensorforge.sdk.scene.Where
import com.application.sensorforge.sdk.train.TrainingReport
import com.application.sensorforge.ui.ErrorCard
import com.application.sensorforge.ui.HashCard
import com.application.sensorforge.ui.InfoCard
import com.application.sensorforge.ui.LossCurveChart
import com.application.sensorforge.ui.Mono
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SectionTitle
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.VerdictBanner
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SceneViewModel(app: Application) : AndroidViewModel(app) {
    data class Ui(
        val tab: Int = 0,
        val live: Boolean = false,
        val proposer: String = "",
        val frame: Bitmap? = null,          // frozen frame being labelled
        val frameProps: List<Detection> = emptyList(),
        val labelled: Map<Int, String> = emptyMap(), // proposal index -> label saved from this frame
        val selected: Int? = null,
        val session: String = "",
        val heldOut: Boolean = false,
        val counts: Map<String, Pair<Int, Int>> = emptyMap(),
        val message: String? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val autoOther: Boolean = true,
        val autoHeld: Boolean = false,
        val report: TrainingReport? = null,
        val activeVersion: Int? = null,
        val rules: SceneRules = SceneRules(),
        val liveObjects: List<SceneObject> = emptyList(),
        val liveProps: List<Detection> = emptyList(),
        val frameMs: Double = 0.0,
        val verdict: Verdict = Verdict.UNVERIFIED,
        val judged: SceneVerdict? = null,
        val opinion: SceneNarrator.Opinion? = null,
        val opinionNote: String? = null,
        val llmBusy: Boolean = false,
    )

    private val lab = SceneLab(app)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private val smoother = SceneSmoother(5)
    @Volatile private var latest: SceneFrame? = null

    init { refresh() }

    fun refresh() = _ui.update {
        it.copy(counts = lab.counts(), activeVersion = lab.store.active(), rules = lab.rules(), proposer = lab.proposerName(),
            autoHeld = lab.autoHeldOut, report = it.report ?: lab.store.active()?.let { v -> runCatching { lab.store.report(v) }.getOrNull() })
    }

    fun setTab(t: Int) { stopCamera(); _ui.update { it.copy(tab = t, message = null, error = null, frame = null, selected = null) } }
    fun setHeldOut(v: Boolean) = _ui.update { it.copy(heldOut = v) }
    fun llmInstalled() = lab.llmInstalled()

    fun startCamera(view: PreviewView, classify: Boolean) {
        if (_ui.value.live) return
        smoother.reset()
        try {
            lab.start(view, classify) { f ->
                latest = f
                val judged = if (classify) SceneJudge.judge(f.objects, _ui.value.rules) else null
                val v = judged?.let { smoother.add(it.verdict) } ?: Verdict.UNVERIFIED
                _ui.update { it.copy(liveObjects = f.objects.filter { o -> o.label != "other" }, liveProps = f.proposals, frameMs = f.ms, judged = judged, verdict = v) }
            }
            _ui.update { it.copy(live = true, error = null) }
        } catch (e: Exception) { _ui.update { it.copy(error = "Cannot start the camera: ${e.message}") } }
    }

    fun stopCamera() { lab.stop(); _ui.update { it.copy(live = false) } }

    /** Freezes the newest analysed frame so its boxes can be named. */
    fun capture() {
        val f = latest ?: return _ui.update { it.copy(error = "No frame analysed yet. Point the camera at the objects and wait a moment.") }
        _ui.update { it.copy(frame = f.bitmap, frameProps = f.proposals, labelled = emptyMap(), selected = null, message = null, error = null,
            session = if (it.session.isEmpty()) "s${System.currentTimeMillis()}" else it.session) }
    }

    /** A box the developer drew by hand (the detector missed the object). Normalised 0..1. */
    fun addDrawn(l: Float, t: Float, rr: Float, b: Float) {
        val box = BBox(minOf(l, rr).coerceIn(0f, 1f), minOf(t, b).coerceIn(0f, 1f), maxOf(l, rr).coerceIn(0f, 1f), maxOf(t, b).coerceIn(0f, 1f))
        if (box.r - box.l < 0.03f || box.b - box.t < 0.03f) return
        _ui.update { it.copy(frameProps = it.frameProps + Detection("object", box, 1.0), selected = it.frameProps.size) }
    }

    fun newSession() = _ui.update { it.copy(session = "s${System.currentTimeMillis()}") }
    fun select(i: Int?) = _ui.update { it.copy(selected = i) }
    fun setAutoOther(v: Boolean) = _ui.update { it.copy(autoOther = v) }

    /** Leaves the frozen frame. Unnamed detector boxes can become 'other' examples so the head learns to reject stray boxes. */
    fun resume() {
        val u = _ui.value; val bmp = u.frame
        if (bmp != null && u.autoOther && u.labelled.isNotEmpty()) {
            val named = u.labelled.keys.mapNotNull { u.frameProps.getOrNull(it)?.box }
            val others = u.frameProps.filterIndexed { i, d -> i !in u.labelled && named.none { n -> d.box.iou(n) > 0.3f } }.take(6)
            val session = u.session.ifEmpty { "s0" }
            viewModelScope.launch { withContext(Dispatchers.Default) { others.forEach { lab.addExample(bmp, it.box, "other", session, u.heldOut) } }; refresh() }
        }
        _ui.update { it.copy(frame = null, selected = null) }
    }

    fun saveLabel(label: String) {
        val u = _ui.value; val i = u.selected ?: return; val bmp = u.frame ?: return
        val box = u.frameProps.getOrNull(i)?.box ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { lab.addExample(bmp, box, label, u.session.ifEmpty { "s0" }, u.heldOut) }
            if (ok) {
                _ui.update { it.copy(labelled = it.labelled + (i to label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')), selected = null, message = "Saved '${label.trim()}'.") }
                refresh()
            } else _ui.update { it.copy(error = "That box is too small or the label is empty.") }
        }
    }

    fun deleteLabel(l: String) { lab.deleteLabel(l); refresh() }
    fun deleteAll() { lab.deleteAll(); _ui.update { it.copy(report = null) }; refresh() }

    fun train() {
        _ui.update { it.copy(busy = true, message = "Training on this phone…", error = null) }
        viewModelScope.launch {
            try {
                val (head, report) = withContext(Dispatchers.Default) { lab.train() }
                _ui.update { it.copy(busy = false, report = report, autoHeld = lab.autoHeldOut, message = "Trained v${head.version} (${report.trainMillis} ms). Approve it to use it.") }
            } catch (e: Exception) { _ui.update { it.copy(busy = false, error = "Train refused: ${e.message}") } }
            refresh()
        }
    }

    fun approve(v: Int) { lab.approve(v); _ui.update { it.copy(message = "v$v is now the active head.") }; refresh() }

    fun setRules(r: SceneRules) { lab.saveRules(r); _ui.update { it.copy(rules = r) } }

    fun askLlm() {
        val f = latest ?: return
        val rules = _ui.value.rules
        _ui.update { it.copy(llmBusy = true, opinion = null, opinionNote = null) }
        viewModelScope.launch {
            val op = withContext(Dispatchers.Default) { lab.llmOpinion(f.objects, rules) }
            val code = SceneJudge.judge(f.objects, rules)
            SessionLog.log("scene.llm", "code" to code.verdict.name, "llm" to (op?.verdict?.name ?: "none"))
            _ui.update { it.copy(llmBusy = false, opinion = op, opinionNote = when {
                !lab.llmInstalled() -> "The local model is not installed."
                op == null -> "The local model's answer could not be read."
                op.verdict != code.verdict -> "The LLM and the code disagree. The code verdict is the one that counts."
                else -> "The LLM agrees with the code verdict."
            }) }
        }
    }

    override fun onCleared() { lab.stop() }
}

private val PALETTE = listOf(Color(0xFF1E7A46), Color(0xFF2F5FD0), Color(0xFFB45309), Color(0xFF9333EA), Color(0xFF0E7490), Color(0xFFB42318))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceneScreen(vm: SceneViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("‹ Home", onClick = onBack)
        Text("SceneCheck", style = MaterialTheme.typography.headlineLarge)
        Text("Box every object, name the boxes, train on this phone, then judge the scene.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1 Label", "2 Train", "3 Run").forEachIndexed { i, t ->
                if (ui.tab == i) PrimaryButton(t, modifier = Modifier.weight(1f)) {} else SecondaryButton(t, modifier = Modifier.weight(1f)) { vm.setTab(i) }
            }
        }
        ui.message?.let { InfoCard(it) }
        ui.error?.let { ErrorCard("Error", it) }
        when (ui.tab) {
            0 -> LabelTab(vm, ui)
            1 -> TrainTab(vm, ui)
            else -> RunTab(vm, ui)
        }
    }
}

/** Camera preview with permission handling; starts the analysis loop while visible. */
@Composable
private fun CameraBox(vm: SceneViewModel, ui: SceneViewModel.Ui, classify: Boolean, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(Unit) { onDispose { vm.stopCamera() } }
    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black, RoundedCornerShape(12.dp))) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        content()
        if (!ui.live) Text(if (granted) "Camera off." else "Camera permission not granted.", color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
    if (!granted) PrimaryButton("Allow camera") { launcher.launch(Manifest.permission.CAMERA) }
    else if (!ui.live) PrimaryButton("Start camera") { vm.startCamera(previewView, classify) }
    else SecondaryButton("Stop camera") { vm.stopCamera() }
}

@Composable
private fun Boxes(items: List<Triple<BBox, String, Color>>, thick: Float = 3f, highlight: Int? = null) {
    Canvas(Modifier.fillMaxSize()) {
        items.forEachIndexed { i, (b, text, col) ->
            val tl = Offset(b.l * size.width, b.t * size.height)
            val sz = Size((b.r - b.l) * size.width, (b.b - b.t) * size.height)
            drawRect(col, tl, sz, style = Stroke(if (i == highlight) thick * 2.5f else thick))
            val p = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 34f; isAntiAlias = true }
            val bg = android.graphics.Paint().apply { color = android.graphics.Color.argb(210, (col.red * 255).toInt(), (col.green * 255).toInt(), (col.blue * 255).toInt()) }
            val w = p.measureText(text) + 12f
            drawContext.canvas.nativeCanvas.drawRect(tl.x, tl.y - 42f, tl.x + w, tl.y, bg)
            drawContext.canvas.nativeCanvas.drawText(text, tl.x + 6f, tl.y - 10f, p)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelTab(vm: SceneViewModel, ui: SceneViewModel.Ui) {
    InfoCard("Detector: ${ui.proposer}. It draws a box around each object it can see; its name is only a guess. Capture a frame, tap a box, give it your name. Repeat across different angles and lighting.")
    val frame = ui.frame
    if (frame == null) {
        CameraBox(vm, ui, classify = false) {
            Boxes(ui.liveProps.mapIndexed { i, d -> Triple(d.box, "${d.cls} ${(d.confidence * 100).toInt()}", PALETTE[i % PALETTE.size]) })
        }
        if (ui.live) Mono("${ui.liveProps.size} box(es) · ${"%.0f".format(ui.frameMs)} ms per frame")
        PrimaryButton("Capture frame to label", enabled = ui.live && ui.frameMs > 0) { vm.capture() }
        if (ui.live && ui.frameMs == 0.0) Text("Waiting for the first frame… if this stays, the lens may be covered or too dark.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        else if (ui.live && ui.liveProps.isEmpty()) Text("No boxes found, but you can still capture and draw the boxes yourself.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black, RoundedCornerShape(12.dp))) {
            Image(frame.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Boxes(ui.frameProps.mapIndexed { i, d ->
                val named = ui.labelled[i]
                Triple(d.box, named ?: "${i + 1}: ${d.cls}", if (named != null) Color(0xFF1E7A46) else PALETTE[i % PALETTE.size])
            }, highlight = ui.selected)
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragNow by remember { mutableStateOf<Offset?>(null) }
            Canvas(Modifier.fillMaxSize()) {
                val a = dragStart; val b = dragNow
                if (a != null && b != null) drawRect(Color.Yellow, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), style = Stroke(5f))
            }
            Box(Modifier.fillMaxSize().pointerInput(ui.frameProps.size) {
                detectDragGestures(
                    onDragStart = { dragStart = it; dragNow = it },
                    onDrag = { c, _ -> dragNow = c.position },
                    onDragEnd = {
                        val a = dragStart; val b = dragNow
                        if (a != null && b != null) vm.addDrawn(a.x / size.width, a.y / size.height, b.x / size.width, b.y / size.height)
                        dragStart = null; dragNow = null
                    },
                    onDragCancel = { dragStart = null; dragNow = null },
                )
            }.pointerInput(ui.frameProps) {
                detectTapGestures { p ->
                    val x = p.x / size.width; val y = p.y / size.height
                    val hit = ui.frameProps.withIndex().filter { (_, d) -> x in d.box.l..d.box.r && y in d.box.t..d.box.b }
                        .minByOrNull { (_, d) -> (d.box.r - d.box.l) * (d.box.b - d.box.t) }
                    vm.select(hit?.index)
                }
            })
        }
        val sel = ui.selected
        if (sel == null) Text("Tap a box to name it, or drag on the picture to draw your own box. ${ui.labelled.size} of ${ui.frameProps.size} named in this frame.", style = MaterialTheme.typography.bodyMedium)
        else {
            var text by remember(sel) { mutableStateOf(ui.frameProps[sel].cls.replace(' ', '_')) }
            SfCard {
                Text("Name box ${sel + 1}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Label (e.g. box, charger, cable)") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ui.counts.keys.forEach { l -> SecondaryButton(l) { text = l } } }
                PrimaryButton("Save label", enabled = text.isNotBlank()) { vm.saveLabel(text) }
                Text("Not a thing you care about? Label it 'other' so the head learns to ignore it.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ui.heldOut, { vm.setHeldOut(it) })
            Text("Held-out (used only to test, never to train)", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ui.autoOther, { vm.setAutoOther(it) })
            Text("Unnamed boxes in this frame become other (teaches it what to ignore)", style = MaterialTheme.typography.bodyMedium)
        }
        SecondaryButton("Done with this frame: back to camera") { vm.resume(); vm.newSession() }
    }
    CountsCard(vm, ui)
}

@Composable
private fun CountsCard(vm: SceneViewModel, ui: SceneViewModel.Ui) {
    SfCard {
        Text("Labelled examples", style = MaterialTheme.typography.titleMedium)
        if (ui.counts.isEmpty()) Text("None yet.", color = TextSecondary)
        ui.counts.forEach { (l, c) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Mono("$l: ${c.first} dev · ${c.second} held-out" + if (c.first < SceneLab.MIN_PER_CLASS) "  (needs ${SceneLab.MIN_PER_CLASS})" else "")
                SecondaryButton("Delete") { vm.deleteLabel(l) }
            }
        }
    }
}

@Composable
private fun TrainTab(vm: SceneViewModel, ui: SceneViewModel.Ui) {
    SectionTitle("Train the object-naming head on this phone")
    InfoCard("The detector and the image encoder stay frozen. A small neural head learns your labels from the examples; its weights change (hashes below). Needs at least 2 labels with ${SceneLab.MIN_PER_CLASS}+ examples each.")
    CountsCard(vm, ui)
    val ready = ui.counts.filter { it.value.first >= SceneLab.MIN_PER_CLASS }
    PrimaryButton(if (ui.busy) "Training…" else "Train head", enabled = !ui.busy && ready.size >= 2) { vm.train() }
    if (ready.size < 2) Text("Disabled: only ${ready.size} label(s) have ${SceneLab.MIN_PER_CLASS}+ examples.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    ui.report?.let { r ->
        SfCard {
            Text("Version v${r.headVersion} · ${r.trainMillis} ms on ${r.trainingBackend} · best epoch ${r.bestEpoch}", style = MaterialTheme.typography.bodyMedium)
            Text("train ${r.trainSamples} · val ${r.valSamples} · held-out ${r.heldOutSamples} · changed ${r.changedParameterCount}/${r.parameterCount}", style = MaterialTheme.typography.bodyMedium)
            LossCurveChart(r.lossCurve, r.valLossCurve)
            HashCard("Weights sha256 (parent → trained)", r.parentSha256, r.trainedWeightsSha256)
            Text("Validation (sessions the head did not fit): " + r.perClassMetrics.entries.joinToString { "${it.key} P %.2f R %.2f (n=%d)".format(it.value.precision, it.value.recall, it.value.support) }.ifEmpty { "none" }, style = MaterialTheme.typography.bodyMedium)
            if (ui.autoHeld) Text("No held-out box was ticked, so each label's newest session was held out automatically.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text("Held-out: " + r.heldOutMetrics.entries.joinToString { "${it.key} P %.2f R %.2f (n=%d)".format(it.value.precision, it.value.recall, it.value.support) }.ifEmpty { "none yet: label more frames (each Done with this frame starts a new session), then train again" }, style = MaterialTheme.typography.bodyMedium)
            if (ui.activeVersion != r.headVersion) PrimaryButton("Approve v${r.headVersion}") { vm.approve(r.headVersion) }
        }
    }
    Mono("Active head: " + (ui.activeVersion?.let { "v$it" } ?: "none (objects keep the detector's guessed names)"))
    SecondaryButton("Delete all examples and heads") { vm.deleteAll() }
}

@Composable
private fun RunTab(vm: SceneViewModel, ui: SceneViewModel.Ui) {
    var container by remember(ui.rules) { mutableStateOf(ui.rules.container) }
    var inside by remember(ui.rules) { mutableStateOf(ui.rules.inside.joinToString(", ")) }
    var outside by remember(ui.rules) { mutableStateOf(ui.rules.outside.joinToString(", ")) }
    fun parse(s: String) = s.split(',').map { it.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }.filter { it.isNotEmpty() }
    SfCard {
        Text("Rules", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(container, { container = it }, Modifier.fillMaxWidth(), label = { Text("Container label") }, singleLine = true)
        OutlinedTextField(inside, { inside = it }, Modifier.fillMaxWidth(), label = { Text("Must be inside (comma separated)") })
        OutlinedTextField(outside, { outside = it }, Modifier.fillMaxWidth(), label = { Text("Must be outside (comma separated)") })
        SecondaryButton("Save rules") { vm.setRules(SceneRules(parse(container).firstOrNull() ?: "box", parse(inside), parse(outside))) }
        if (ui.counts.isNotEmpty()) Text("Your labels: ${ui.counts.keys.joinToString()}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
    if (ui.activeVersion == null) InfoCard("No trained head yet: boxes carry the detector's guessed names, so the rules only work if those names match. Train one first for your own labels.")
    CameraBox(vm, ui, classify = true) {
        Boxes(ui.liveObjects.mapIndexed { i, o ->
            Triple(o.box, "${o.label} ${(o.confidence * 100).toInt()}" + if (o.source == SceneObject.Source.DETECTOR) "?" else "", if (o.source == SceneObject.Source.HEAD) Color(0xFF1E7A46) else PALETTE[i % PALETTE.size])
        })
    }
    if (ui.live) {
        Mono("${ui.liveObjects.size} object(s) · ${"%.0f".format(ui.frameMs)} ms per frame · ? = only the detector's guess")
        VerdictBanner(when (ui.verdict) { Verdict.PASS -> EngineVerdict.PASS; Verdict.FAIL -> EngineVerdict.FAIL; Verdict.UNVERIFIED -> EngineVerdict.UNVERIFIED },
            ui.judged?.reasons?.firstOrNull() ?: if (ui.verdict == Verdict.UNVERIFIED) "Needs 5 identical frames in a row." else null)
        SfCard {
            Text("Code verdict (geometry, the one that counts)", style = MaterialTheme.typography.titleMedium)
            ui.judged?.items?.forEach {
                val (col, glyph) = when { it.actual == Where.NOT_SEEN -> SfTheme.colors.unverified to "?"; it.ok -> SfTheme.colors.pass to "✓"; else -> SfTheme.colors.fail to "✕" }
                StatusChip("${it.label}: ${it.actual.name.lowercase()} (wanted ${it.expected.name.lowercase()})" + (it.overlap?.let { o -> " · ${(o * 100).toInt()}% in" } ?: ""), col, glyph)
            } ?: Text("The container '${ui.rules.container}' is not visible.", color = TextSecondary)
        }
        SfCard {
            Text("LLM reasoning (advisory)", style = MaterialTheme.typography.titleMedium)
            Text("The local model reads the boxes and rules and gives its own verdict. It is slow (10–20 s) and can be wrong; it never changes the code verdict above.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            PrimaryButton(if (ui.llmBusy) "Thinking…" else "Ask the LLM about this scene", enabled = !ui.llmBusy && vm.llmInstalled()) { vm.askLlm() }
            ui.opinion?.let { Mono("LLM: ${it.verdict.name} · ${it.reason}") }
            ui.opinionNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
