package com.application.sensorforge.forge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.application.sensorforge.crashguard.CrashGuardViewModel
import com.application.sensorforge.crashguard.ModelCard
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.forge.CalibrationStatus
import com.application.sensorforge.sdk.forge.CaptureKind
import com.application.sensorforge.sdk.forge.ConfigField
import com.application.sensorforge.sdk.forge.InductionStatus
import com.application.sensorforge.sdk.forge.PlanBuilder
import com.application.sensorforge.sdk.forge.PlanKind
import com.application.sensorforge.sdk.runtime.PackOpen
import com.application.sensorforge.ui.ErrorCard
import com.application.sensorforge.ui.ForgeStepper
import com.application.sensorforge.ui.HashCard
import com.application.sensorforge.ui.InfoCard
import com.application.sensorforge.ui.LossCurveChart
import com.application.sensorforge.ui.Mono
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SectionTitle
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.SimulationChip
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.prettyStep
import com.application.sensorforge.ui.theme.MonoStyle
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Guided setup for one use case: stepper + the page of the current step. */
@Composable
fun ForgeFlow(
    vm: ForgeViewModel,
    cg: CrashGuardViewModel,
    onRun: (itemId: String?) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val def = ui.def ?: return
    val model by cg.modelUi.collectAsState()
    // IMU counts feed the plan progress for CrashGuard.
    val imu = model.counts.associate { it.label to (it.dev to it.heldOut) }
    androidx.compose.runtime.LaunchedEffect(imu) { vm.setImuCounts(imu) }

    val done = buildSet {
        if (ui.specVersion > 0 || ui.isDetect) add(0)
        if (ui.plan?.accepted == true) add(1)
        if (ui.plan?.accepted == true && ui.missing.isEmpty() && ui.progress.isNotEmpty()) add(2)
        if (ui.activeHeads.values.all { it != null } && ui.activeHeads.isNotEmpty()) add(3)
        if (ui.score != null) add(4)
        if (ui.export != null) add(5)
        if (ui.opened is PackOpen.Ok) add(6)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("‹ ${def.title}", onClick = onBack)
            Text("Guided setup", style = MaterialTheme.typography.titleMedium)
        }
        ForgeStepper(FORGE_STEPS, ui.step, done) { vm.setStep(it) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.message?.let { InfoCard(it) }
            ui.error?.let { ErrorCard("Error", it) }
            if (ui.simulatedCount > 0 && ui.step in 2..5) SimulationChip("${ui.simulatedCount} simulated example(s) are in this project")
            when (ui.step) {
                0 -> DescribePage(vm, ui)
                1 -> PlanPage(vm, ui)
                2 -> CapturePage(vm, ui, cg, onRun)
                3 -> TrainPage(vm, ui, cg)
                4 -> TestPage(vm, ui)
                5 -> ExportPage(vm, ui)
                else -> ImportPage(vm, ui, onRun)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.step > 0) SecondaryButton("Back", modifier = Modifier.weight(1f)) { vm.setStep(ui.step - 1) }
                if (ui.step < FORGE_STEPS.lastIndex) PrimaryButton("Next: ${FORGE_STEPS[ui.step + 1]}", modifier = Modifier.weight(1f)) { vm.setStep(ui.step + 1) }
            }
        }
    }
}

// ---------------------------------------------------------------- Describe

@Composable
private fun DescribePage(vm: ForgeViewModel, ui: ForgeUi) {
    SectionTitle("1 · Describe")
    if (ui.isDetect) {
        InfoCard("Detect packs (CrashGuard) are configured from the sensor spec below: window, gate, corroboration and the H3 head. They are not authored from a sentence. Calibrate the impact threshold on the Run screen.")
        SfCard { Mono(ui.specText) }
        return
    }
    val spec = remember(ui.specText) { runCatching { Contracts.parseProcedure(ui.specText) }.getOrNull() }
    SfCard {
        Text("Active spec · v${maxOf(ui.specVersion, 1)}${if (ui.specVersion == 0) " (shipped)" else ""}", style = MaterialTheme.typography.titleMedium)
        spec?.steps?.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(if (s.required) "required" else "optional", if (s.required) SfTheme.colors.info else SfTheme.colors.pending, "${i + 1}")
                Column {
                    Text(prettyStep(s.id))
                    Text("${s.primitive}${s.state?.let { " · $it" }.orEmpty()}${s.zone?.let { " · $it" }.orEmpty()} · min conf ${s.minConfidence}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
        }
        spec?.let { sp ->
            if (sp.constraints.isNotEmpty() || sp.prohibited.isNotEmpty()) {
                Text("Rules", style = MaterialTheme.typography.titleMedium)
                sp.constraints.forEach { Mono(it.toString().take(110)) }
                sp.prohibited.forEach { Mono(it.toString().take(110)) }
            }
        } ?: ErrorCard("SpecUnreadable", "The active spec could not be read.")
    }
    SfCard {
        Text("Describe it in a sentence", style = MaterialTheme.typography.titleMedium)
        Text("Offline template compiler: show X, open X, remove X from Y, read the X label CODE, at most one X, never show X, only after start.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        OutlinedTextField(ui.sentence, vm::setSentence, Modifier.fillMaxWidth(), label = { Text("Procedure sentence") }, minLines = 2)
        val ctx = LocalContext.current
        var voiceState by remember { mutableStateOf<String?>(null) }
        val voice = remember { VoiceInput(ctx, { vm.setSentence(it) }, { voiceState = it }) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { voice.stop() } }
        val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) voice.start() else voiceState = "Microphone permission denied. Type the sentence instead." }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Compile sentence", enabled = ui.sentence.isNotBlank()) { vm.compileSentence() }
            SecondaryButton("Speak", enabled = voice.available) {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        voiceState?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
        if (!voice.available) Text("Voice input (M7) unavailable: no on-device recognizer on this phone.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text("Local LLM (M6): not active, no native runtime bundled. Guided authoring (templates) is used. Laya (O1): omitted.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        ui.draftNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    SecondaryButton(if (ui.showJson) "Hide JSON" else "Edit JSON") { vm.toggleJson() }
    if (ui.showJson || ui.draftErrors.isNotEmpty()) {
        OutlinedTextField(ui.draftText, vm::setDraft, Modifier.fillMaxWidth().height(260.dp), textStyle = MonoStyle, label = { Text("Draft spec (JSON)") })
    }
    ui.draftErrors.forEach { ErrorCard("ContractError", it) }
    ui.deltas?.forEach { d ->
        InfoCard(d.consequence)
    }
    val changed = ui.draftText != ui.specText
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Check draft", enabled = changed) { vm.checkDraft() }
        SecondaryButton("Discard draft", enabled = changed) { vm.resetDraft() }
    }
    val checked = ui.deltas != null && ui.draftErrors.isEmpty()
    val breaks = ui.deltas.orEmpty().any { it.broke }
    PrimaryButton(if (breaks) "Approve anyway (regression worsens)" else "Approve as v${ui.specVersion + 1}", enabled = changed && checked) { vm.approveDraft() }
    if (changed && !checked) Text("Check the draft first: approving runs regression against your examples.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
}

// ---------------------------------------------------------------- Plan

@Composable
private fun kindChip(k: PlanKind) = when (k) {
    PlanKind.GOOD -> Triple("good", SfTheme.colors.pass, "✓")
    PlanKind.BAD -> Triple("bad", SfTheme.colors.fail, "✕")
    PlanKind.UNCLEAR -> Triple("unclear", SfTheme.colors.unverified, "?")
}

@Composable
private fun PlanPage(vm: ForgeViewModel, ui: ForgeUi) {
    SectionTitle("2 · Plan the examples")
    val plan = ui.plan ?: return
    InfoCard("Capture is driven by this checklist: good, bad and unclear examples, with how-to and the verdict each should give. Edit counts or remove items, then accept.")
    val def = ui.def!!
    if (def.config.isNotEmpty()) SfCard {
        Text("Configuration before capture", style = MaterialTheme.typography.titleMedium)
        def.config.forEach { f ->
            val v = ui.config[f.key].orEmpty()
            var text by remember(f.key, v) { mutableStateOf(v) }
            OutlinedTextField(text, { text = it; vm.setConfig(f.key, it) }, Modifier.fillMaxWidth(), label = { Text(f.label) }, supportingText = { Text(configHint(f, text)) }, singleLine = true)
            if (f.kind == ConfigField.Kind.TIME) SecondaryButton("Start time = now") { text = System.currentTimeMillis().toString(); vm.setConfig(f.key, text) }
        }
    }
    plan.items.forEach { item ->
        val prog = ui.progress.firstOrNull { it.item.id == item.id }
        SfCard {
            val (k, c, g) = kindChip(item.kind)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(k, c, g)
                Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(item.instruction)
            item.expectedVerdict?.let { Mono("expected: ${it.name}${item.expectedReason?.let { r -> " · $r" }.orEmpty()}") }
            Text("${item.capture.name.lowercase()} · min ${item.minCount} · target ${item.targetCount}${if (item.needsHeldOut) " · ≥1 held-out" else ""}" + (prog?.let { " · have ${it.dev}+${it.heldOut}" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("−", enabled = item.targetCount > item.minCount) { vm.bumpTarget(item.id, -1) }
                SecondaryButton("+") { vm.bumpTarget(item.id, 1) }
                SecondaryButton("Remove") { vm.removePlanItem(item.id) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Regenerate from spec") { vm.resetPlan() }
    }
    PrimaryButton(if (plan.accepted) "Plan accepted ✓" else "Accept plan and start capture", enabled = !plan.accepted && plan.items.isNotEmpty()) { vm.acceptPlan() }
}

private fun configHint(f: ConfigField, text: String): String = when (f.kind) {
    ConfigField.Kind.TIME -> text.toLongOrNull()?.let { "UTC " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it)) } ?: f.hint.ifEmpty { "UTC epoch milliseconds" }
    else -> f.hint
}

// ---------------------------------------------------------------- Capture

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapturePage(vm: ForgeViewModel, ui: ForgeUi, cg: CrashGuardViewModel, onRun: (String?) -> Unit) {
    SectionTitle("3 · Capture")
    if (ui.plan?.accepted != true) {
        ErrorCard("PlanNotAccepted", "Accept the plan first: capture is driven by it.", "Go to Plan") { vm.setStep(1) }
        return
    }
    val ctx = LocalContext.current
    val def = ui.def!!
    val cropHeads = def.heads.filter { it.capture == CaptureKind.CROP }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var capturing by remember { mutableStateOf<String?>(null) }
    var pickItem by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { u -> pickItem?.let { vm.importTimeline(u, it) } } }
    val clipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { u -> pickItem?.let { vm.importClip(u, it) } } }
    val model by cg.modelUi.collectAsState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(ui.heldOutNext, vm::setHeldOut)
        Text("Next examples are held-out (never used to fit or calibrate)", style = MaterialTheme.typography.bodyMedium)
    }
    if (ui.missing.isNotEmpty()) InfoCard("Still needed: " + ui.missing.joinToString("; "))

    if (cropHeads.isNotEmpty()) {
        SfCard {
            Text("Camera", style = MaterialTheme.typography.titleMedium)
            AndroidView({ previewView }, Modifier.fillMaxWidth().height(220.dp))
            if (!granted) ErrorCard("PermissionDenied: camera", "Crop capture needs the camera. Without it nothing can be captured (never faked).", "Allow camera") { launcher.launch(Manifest.permission.CAMERA) }
        }
    }

    ui.progress.forEach { p ->
        val item = p.item
        SfCard {
            val (k, c, g) = kindChip(item.kind)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(k, c, g)
                Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(item.instruction, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Mono("dev ${p.dev}/${item.minCount} · held-out ${p.heldOut}")
                if (p.devMet) StatusChip("enough", SfTheme.colors.pass, "✓") else StatusChip("needs ${item.minCount - p.dev} more", SfTheme.colors.unverified, "!")
            }
            when (item.capture) {
                CaptureKind.RUN -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Record live") { onRun(item.id) }
                    SecondaryButton("Import video", enabled = !ui.busy) { pickItem = item.id; clipPicker.launch(arrayOf("video/*")) }
                    SecondaryButton("Import JSON") { pickItem = item.id; picker.launch(arrayOf("application/json", "*/*")) }
                    SecondaryButton("Simulate ×3") { vm.simulateItem(item.id, 3) }
                }
                CaptureKind.CROP -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val head = def.heads.first { item.headClass in it.classes }
                    SecondaryButton(if (capturing == item.id) "Capturing…" else "Capture 4 s", enabled = granted && capturing == null) {
                        capturing = item.id
                        vm.cropForge(head.headId).capture(previewView, item.headClass!!, ui.heldOutNext) { vm.refresh(); capturing = null }
                    }
                    SecondaryButton("Simulate") { vm.simulateItem(item.id, 1) }
                }
                CaptureKind.IMU -> {
                    val cls = item.headClass!!
                    val replay = def.heads.any { cls in it.replayOnly }
                    if (replay) Text("From the bundled replay fixtures (labelled SIMULATION).", style = MaterialTheme.typography.bodyMedium)
                    else {
                        val rec = model.recorder
                        if (rec != null && rec.label == cls) StatusChip("recording ${rec.label}: ${rec.remainingS} s", SfTheme.colors.info, "●")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Record", enabled = rec == null) { cg.setHeldOut(ui.heldOutNext); cg.record(cls) }
                            SecondaryButton("Simulate ×3") { cg.setHeldOut(ui.heldOutNext); cg.simulate(cls, 3) }
                        }
                    }
                }
            }
        }
    }
    var confirm by remember { mutableStateOf(false) }
    SecondaryButton("Fill plan with SIMULATION examples") { vm.simulateAll { c, n, h -> cg.simulateSync(c, n, h) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Remove simulated", enabled = ui.simulatedCount > 0) { vm.clearSimulated() }
        SecondaryButton("Delete all examples") { confirm = true }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete all examples?") },
        text = { Text("Removes every captured and simulated example of this use case. Approved heads and spec versions are kept.") },
        confirmButton = { TextButton({ confirm = false; vm.deleteExamples(); if (def.mode == "detect") cg.deleteAllRecordings() }) { Text("Delete", color = SfTheme.colors.failSolid) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } },
    )
    if (!ui.isDetect) ExampleList(vm)
}

@Composable
private fun ExampleList(vm: ForgeViewModel) {
    val ex = vm.examples()
    if (ex.isEmpty()) return
    SfCard {
        Text("Captured runs (${ex.size})", style = MaterialTheme.typography.titleMedium)
        ex.takeLast(12).forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (e.simulation) "sim" else "live", if (e.simulation) SfTheme.colors.simulation else SfTheme.colors.info, if (e.simulation) "▶" else "●")
                Text("${e.id} · ${e.label.name.lowercase()} · ${e.split.name.lowercase()}", style = MonoStyle, modifier = Modifier.weight(1f))
                TextButton({ vm.deleteExample(e.id) }) { Text("✕", color = SfTheme.colors.failSolid) }
            }
        }
    }
}

// ---------------------------------------------------------------- Train

@Composable
private fun TrainPage(vm: ForgeViewModel, ui: ForgeUi, cg: CrashGuardViewModel) {
    SectionTitle("4 · Train")
    val def = ui.def!!
    if (ui.plan?.accepted != true) {
        ErrorCard("PlanNotAccepted", "Accept the plan and capture examples first.", "Go to Plan") { vm.setStep(1) }
        return
    }
    InfoCard("Training changes real weights (hashes shown before and after). Threshold tuning is calibration and lives in Test.")
    if (def.heads.isEmpty()) InfoCard("This verifier has no trainable head: object classes come from tagged props and the spec decides the verdict. Nothing to train; go on to Test.")
    def.heads.forEach { h ->
        if (h.capture == CaptureKind.IMU) {
            val model by cg.modelUi.collectAsState()
            ModelCard(model, cg)
        } else {
            val items = ui.progress.filter { it.item.headClass in h.classes && it.item.capture == CaptureKind.CROP }
            val gate = items.filter { !it.devMet }
            val report = ui.reports[h.headId]
            val active = ui.activeHeads[h.headId]
            SfCard {
                Text("${h.slot} · ${h.headId}", style = MaterialTheme.typography.titleMedium)
                Text("Embedding: handcrafted YUV histogram (M2 encoder not installed: fallback). Trained on this phone, CPU.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                items.forEach { Mono("${it.item.headClass}: ${it.dev} dev · ${it.heldOut} held-out (min ${it.item.minCount})") }
                if (gate.isNotEmpty()) Text("Needs: " + gate.joinToString("; ") { "${it.item.headClass} ${it.dev}/${it.item.minCount}" }, color = SfTheme.colors.unverified.content)
                PrimaryButton(if (ui.busy) "Training…" else "Train ${h.slot}", enabled = !ui.busy && gate.isEmpty()) { vm.trainCrop(h.headId) }
                report?.let { r ->
                    Text("Version v${r.headVersion} · ${r.trainMillis} ms on ${r.trainingBackend} · best epoch ${r.bestEpoch}", style = MaterialTheme.typography.bodyMedium)
                    Text("train ${r.trainSamples} · val ${r.valSamples} · held-out ${r.heldOutSamples} · changed ${r.changedParameterCount}/${r.parameterCount}", style = MaterialTheme.typography.bodyMedium)
                    LossCurveChart(r.lossCurve, r.valLossCurve)
                    HashCard("Weights sha256 (parent → trained)", r.parentSha256, r.trainedWeightsSha256)
                    Text("Held-out: " + r.heldOutMetrics.entries.joinToString { "${it.key} P %.2f R %.2f (n=%d)".format(it.value.precision, it.value.recall, it.value.support) }.ifEmpty { "none (capture a held-out session)" }, style = MonoStyle)
                    if (active != r.headVersion) PrimaryButton("Approve v${r.headVersion}") { vm.approveHead(h.headId, r.headVersion) }
                }
                Mono("Active: " + (active?.let { "v$it" } ?: "none (tag decides the class)"))
            }
        }
    }
}

// ---------------------------------------------------------------- Test

@Composable
private fun TestPage(vm: ForgeViewModel, ui: ForgeUi) {
    SectionTitle("5 · Test")
    if (ui.isDetect) {
        InfoCard("CrashGuard is tested with held-out windows on the Train step and live replays on the Run screen (countdown, cancel, simulated SOS).")
        return
    }
    PrimaryButton(if (ui.busy) "Testing…" else "Run test on all examples", enabled = !ui.busy) { vm.runTest() }
    val score = ui.score ?: run { InfoCard("Nothing tested yet. Capture or simulate examples, then run the test."); return }
    if (score.rows.isEmpty()) { ErrorCard("NoExamples", "No examples to test. Capture or simulate some first.", "Go to Capture") { vm.setStep(2) }; return }
    SfCard {
        Text(if (score.shippable) "No false accepts" else "False accepts present", style = MaterialTheme.typography.titleMedium, color = if (score.shippable) SfTheme.colors.pass.content else SfTheme.colors.fail.content)
        listOf("dev" to score.dev, "held-out" to score.heldOut).forEach { (n, s) ->
            Mono("$n: n=${s.n} · correct ${s.correct} · false accepts ${s.falseAccepts} · false rejects ${s.falseRejects} · wrong kind ${s.wrongKind}")
        }
        if (score.rows.any { it.simulation }) SimulationChip("includes simulated examples: not real accuracy")
    }
    SfCard {
        Text("Per example", style = MaterialTheme.typography.titleMedium)
        score.rows.forEach { r ->
            val ok = r.actual == r.expected
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(r.actual.name, when (r.actual) { Verdict.PASS -> SfTheme.colors.pass; Verdict.FAIL -> SfTheme.colors.fail; Verdict.UNVERIFIED -> SfTheme.colors.unverified }, if (ok) "✓" else "≠")
                Text("${r.exampleId} (${r.split.name.lowercase()}) expected ${r.expected.name}", style = MonoStyle)
            }
            if (!ok && r.reasons.isNotEmpty()) Text(r.reasons.joinToString(), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
    if (ui.order.isNotEmpty() || ui.requirements.isNotEmpty()) SfCard {
        Text("Induction (from valid dev examples)", style = MaterialTheme.typography.titleMedium)
        ui.order.forEach {
            Text("${when (it.status) { InductionStatus.PROPOSED -> "▸"; InductionStatus.ANY_ORDER -> "⇄"; InductionStatus.NEEDS_DEVELOPER -> "?" }} ${it.note}", style = MaterialTheme.typography.bodyMedium)
        }
        ui.requirements.forEach {
            Text("${prettyStep(it.stepId)}: ${if (it.required) "required" else "optional"} (seen ${it.observedIn}/${it.of})${if (it.needsConfirmation) " · confirm" else ""}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    SfCard {
        Text("Threshold calibration", style = MaterialTheme.typography.titleMedium)
        Text("Calibration picks thresholds from dev examples only. It is not training.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        ui.calibration.forEach {
            val col = when (it.status) { CalibrationStatus.OK -> SfTheme.colors.pass; CalibrationStatus.FRAGILE -> SfTheme.colors.unverified; CalibrationStatus.INFEASIBLE -> SfTheme.colors.fail }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(it.status.name.lowercase(), col, when (it.status) { CalibrationStatus.OK -> "✓"; CalibrationStatus.FRAGILE -> "!"; CalibrationStatus.INFEASIBLE -> "✕" })
                Text("${prettyStep(it.stepId)} → %.2f".format(it.threshold))
            }
            Text(it.message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        SecondaryButton("Apply as new spec version", enabled = ui.calibration.any { it.status != CalibrationStatus.INFEASIBLE }) { vm.applyCalibration() }
    }
}

// ---------------------------------------------------------------- Export

@Composable
private fun ExportPage(vm: ForgeViewModel, ui: ForgeUi) {
    SectionTitle("6 · Export")
    val ctx = LocalContext.current
    InfoCard("A .sfpkg holds the spec, approved heads, thresholds, regression, training proof and models.lock, sealed by pack_sha256.")
    PrimaryButton(if (ui.busy) "Exporting…" else "Export .sfpkg", enabled = !ui.busy) { vm.export() }
    ui.export?.let { r ->
        SfCard {
            Text(r.file.name, style = MaterialTheme.typography.titleMedium)
            Mono("pack_id ${r.manifest.packId} · v${r.manifest.packVersion} · ${r.manifest.mode}")
            Mono("pack_sha256 ${r.manifest.packSha256}")
            Text("${r.file.length() / 1024} KB · profile ${r.manifest.perceptionProfile.joinToString()}", style = MaterialTheme.typography.bodyMedium)
            r.warnings.forEach { Text("⚠ $it", color = SfTheme.colors.unverified.content, style = MaterialTheme.typography.bodyMedium) }
            SecondaryButton("Share…") { share(ctx, r.file) }
        }
    }
    if (ui.packs.isNotEmpty()) SfCard {
        Text("Packs on this phone", style = MaterialTheme.typography.titleMedium)
        ui.packs.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { Mono(f.name, Modifier.weight(1f)); TextButton({ share(ctx, f) }) { Text("Share") } } }
    }
}

private fun share(ctx: android.content.Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share ${f.name}"))
}

// ---------------------------------------------------------------- Import

@Composable
private fun ImportPage(vm: ForgeViewModel, ui: ForgeUi, onRun: (String?) -> Unit) {
    SectionTitle("7 · Import")
    InfoCard("Opening a pack checks pack_sha256 and models.lock. A perception stack that differs from this phone fails closed.")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importPack(it) } }
    SecondaryButton("Pick a .sfpkg file") { picker.launch(arrayOf("*/*")) }
    ui.packs.forEach { f -> SecondaryButton("Open ${f.name}") { vm.openPackFile(f) } }
    when (val o = ui.opened) {
        null -> Unit
        is PackOpen.Ok -> SfCard {
            val s = o.summary
            StatusChip("hash ok · stack ok", SfTheme.colors.pass, "✓")
            Text("${s.manifest.packId} v${s.manifest.packVersion} (${s.manifest.mode})", style = MaterialTheme.typography.titleMedium)
            Mono("sha256 ${s.sha256}")
            if (s.stepIds.isNotEmpty()) Text("Steps: " + s.stepIds.joinToString { prettyStep(it) }, style = MaterialTheme.typography.bodyMedium)
            Text("Heads: " + s.headIds.joinToString().ifEmpty { "none (fallbacks used)" }, style = MaterialTheme.typography.bodyMedium)
            Text("${s.sizeBytes / 1024} KB", style = MaterialTheme.typography.bodyMedium)
            if (s.manifest.mode == "verify") PrimaryButton("Run live with this pack") { onRun(null) }
            else InfoCard("Detect packs run from the CrashGuard Run screen.")
        }
        is PackOpen.Mismatch -> ErrorCard("PerceptionStackMismatch", "This pack was frozen against other models: " + o.mismatches.joinToString { "${it.id} (expected ${it.expected.take(8)}, have ${it.actual?.take(8) ?: "missing"})" } + ". It is refused rather than guessed (fail closed).")
        is PackOpen.Invalid -> ErrorCard(o.type, o.message)
    }
}
