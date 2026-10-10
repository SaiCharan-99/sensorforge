package com.application.sensorforge.forge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.forge.Calibration
import com.application.sensorforge.sdk.forge.CalibrationStatus
import com.application.sensorforge.sdk.forge.CaptureKind
import com.application.sensorforge.sdk.forge.ConstraintProposal
import com.application.sensorforge.sdk.forge.ExampleFile
import com.application.sensorforge.sdk.forge.Induction
import com.application.sensorforge.sdk.forge.Plan
import com.application.sensorforge.sdk.forge.PlanBuilder
import com.application.sensorforge.sdk.forge.Regression
import com.application.sensorforge.sdk.forge.RegressionDelta
import com.application.sensorforge.sdk.forge.RequirementProposal
import com.application.sensorforge.sdk.forge.Score
import com.application.sensorforge.sdk.forge.Split
import com.application.sensorforge.sdk.forge.StepCalibration
import com.application.sensorforge.sdk.forge.TemplateCompiler
import com.application.sensorforge.sdk.forge.UseCaseDef
import com.application.sensorforge.sdk.runtime.ExportResult
import com.application.sensorforge.sdk.runtime.ForgeSession
import com.application.sensorforge.sdk.runtime.PackOpen
import com.application.sensorforge.sdk.train.TrainingReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val FORGE_STEPS = listOf("Describe", "Plan", "Capture", "Train", "Test", "Export", "Import")

/** Everything the seven guided-setup pages show; one instance per open use case. */
data class ForgeUi(
    val def: UseCaseDef? = null,
    val step: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    // Describe
    val specVersion: Int = 0,
    val specText: String = "",
    val sentence: String = "",
    val draftText: String = "",
    val draftNotes: List<String> = emptyList(),
    val draftErrors: List<String> = emptyList(),
    val deltas: List<RegressionDelta>? = null,
    val showJson: Boolean = false,
    // Plan / Capture
    val plan: Plan? = null,
    val config: Map<String, String> = emptyMap(),
    val progress: List<PlanBuilder.Progress> = emptyList(),
    val heldOutNext: Boolean = false,
    val simulatedCount: Int = 0,
    // Train
    val reports: Map<String, TrainingReport> = emptyMap(),
    val activeHeads: Map<String, Int?> = emptyMap(),
    // Test
    val score: Score? = null,
    val order: List<ConstraintProposal> = emptyList(),
    val requirements: List<RequirementProposal> = emptyList(),
    val calibration: List<StepCalibration> = emptyList(),
    // Export / Import
    val export: ExportResult? = null,
    val packs: List<File> = emptyList(),
    val opened: PackOpen? = null,
) {
    val isDetect get() = def?.mode == "detect"
    val missing get() = PlanBuilder.missing(progress)
}

class ForgeViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(ForgeUi())
    val ui: StateFlow<ForgeUi> = _ui

    private var session: ForgeSession? = null
    /** IMU counts supplied by CrashGuardViewModel (class → dev, held-out). */
    private var imuCounts: Map<String, Pair<Int, Int>> = emptyMap()

    val useCaseId get() = _ui.value.def?.id

    fun open(id: String) {
        if (session?.def?.id == id) return
        val s = SensorForge.forge(getApplication(), id)
        session = s
        val p = s.project
        _ui.value = ForgeUi(def = s.def, step = 0)
        refresh()
        if (!s.def.mode.equals("detect")) _ui.update { it.copy(sentence = s.def.exampleSentence) }
        p.root.mkdirs()
    }

    fun close() { session = null; _ui.value = ForgeUi() }

    fun setStep(i: Int) = _ui.update { it.copy(step = i, message = null, error = null) }

    fun setImuCounts(c: Map<String, Pair<Int, Int>>) {
        if (c == imuCounts) return
        imuCounts = c
        if (session != null) refresh()
    }

    /** Re-reads everything from disk; cheap enough to call after any action. */
    fun refresh() {
        val s = session ?: return
        val p = s.project
        val heads = s.def.heads.associate { h -> h.headId to s.headStore(h.headId).active() }
        _ui.update {
            it.copy(
                specVersion = p.specVersion(), specText = p.specText(),
                draftText = if (it.draftText.isBlank()) p.specText() else it.draftText,
                plan = p.plan(), config = p.config(), progress = s.progress(imuCounts),
                simulatedCount = p.exampleFiles().count { e -> e.simulation } + s.cropHeads().sumOf { h -> s.cropForge(h.headId).simulatedCount() },
                activeHeads = heads, packs = s.packs(),
            )
        }
    }

    private fun log(kind: String, vararg d: Pair<String, Any?>) = com.application.sensorforge.sdk.runtime.SessionLog.log(kind, *d)
    private fun msg(m: String) = _ui.update { it.copy(message = m, error = null) }
    private fun err(m: String) = _ui.update { it.copy(error = m, message = null) }

    // ---- Describe ----
    fun setSentence(t: String) = _ui.update { it.copy(sentence = t) }
    fun setDraft(t: String) = _ui.update { it.copy(draftText = t, draftErrors = emptyList(), deltas = null) }
    fun toggleJson() = _ui.update { it.copy(showJson = !it.showJson) }

    fun compileSentence() {
        val s = session ?: return
        val cur = s.project.procedure()
        val d = TemplateCompiler.compile(cur.procedureId, _ui.value.sentence, version = cur.version + 1)
        val withSchema = d.spec.copy(parameters = d.spec.parameters)
        val text = Contracts.json.encodeToString(ProcedureSpec.serializer(), withSchema)
        _ui.update { it.copy(draftText = text, draftNotes = d.warnings + d.clauses.map { c -> "'${c.text}' → ${c.stepIds.joinToString().ifEmpty { "nothing" }}" + (c.note?.let { n -> " ($n)" } ?: "") }, draftErrors = s.project.check(text), deltas = null) }
        msg("Compiled with the offline template compiler (deterministic). Review the cards, then check and approve.")
    }

    fun resetDraft() = _ui.update { it.copy(draftText = it.specText, draftErrors = emptyList(), deltas = null, draftNotes = emptyList()) }

    fun checkDraft() {
        val s = session ?: return
        val errs = s.project.check(_ui.value.draftText)
        _ui.update { it.copy(draftErrors = errs, deltas = null) }
        if (errs.isEmpty() && !s.project.isDetect()) {
            val deltas = runCatching { Regression.deltas(s.project.procedure(), Contracts.parseProcedure(_ui.value.draftText), s.project.examples()) }.getOrDefault(emptyList())
            _ui.update { it.copy(deltas = deltas) }
            msg(if (deltas.isEmpty()) "Valid. No example changes verdict." else "Valid. ${deltas.size} example(s) change verdict; read them before approving.")
        } else if (errs.isEmpty()) msg("Valid.") else err("${errs.size} contract error(s).")
    }

    fun approveDraft() {
        val s = session ?: return
        try {
            val n = s.project.approveSpec(_ui.value.draftText)
            log("forge.approve_spec", "use_case" to s.def.id, "version" to n)
            _ui.update { it.copy(deltas = null, draftNotes = emptyList()) }
            val plan = s.project.resetPlan() // a new spec means a new example plan
            refresh(); _ui.update { it.copy(draftText = it.specText, plan = plan) }
            msg("Approved spec v$n. The plan was regenerated; regression snapshot saved.")
        } catch (e: Exception) { err("Not approved: ${e.message}") }
    }

    // ---- Plan ----
    fun setConfig(key: String, value: String) { session?.project?.setConfig(key, value); refresh() }
    fun acceptPlan() { session?.project?.acceptPlan(); refresh(); setStep(2); msg("Plan accepted. Capture is unlocked.") }
    fun resetPlan() { session?.project?.resetPlan(); refresh(); msg("Plan regenerated from the spec.") }
    fun removePlanItem(id: String) { val p = session?.project ?: return; p.savePlan(PlanBuilder.withoutItem(p.plan(), id)); refresh() }
    fun bumpTarget(id: String, d: Int) {
        val p = session?.project ?: return
        val plan = p.plan()
        val item = plan.items.firstOrNull { it.id == id } ?: return
        p.savePlan(PlanBuilder.withItem(plan, item.copy(targetCount = (item.targetCount + d).coerceAtLeast(item.minCount))))
        refresh()
    }

    /** Capture is allowed once the plan is accepted (no silent bypass). */
    val captureLocked get() = _ui.value.plan?.accepted != true

    // ---- Capture ----
    fun setHeldOut(v: Boolean) = _ui.update { it.copy(heldOutNext = v) }

    /** SIMULATION examples for a plan item; always flagged and shown with the SIMULATION chip. */
    fun simulateItem(itemId: String, n: Int) {
        val s = session ?: return
        val item = _ui.value.plan?.items?.firstOrNull { it.id == itemId } ?: return
        val split = if (_ui.value.heldOutNext) Split.HELD_OUT else Split.DEV
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (item.capture) {
                        CaptureKind.RUN -> s.project.simulate(itemId, n, split)
                        CaptureKind.CROP -> s.cropForge(s.def.heads.first { it.classes.contains(item.headClass) }.headId).simulate(item.headClass!!, n * 8, split == Split.HELD_OUT)
                        CaptureKind.IMU -> Unit // handled by CrashGuardViewModel.simulate
                    }
                }
                refresh(); msg("Added $n SIMULATION example(s) to '${item.title}'.")
            } catch (e: Exception) { err("Simulation failed: ${e.message}") }
        }
    }

    /**
     * Fills every plan item up to its target with SIMULATION examples (dev) plus one held-out where
     * the plan asks for it. IMU items go through [imu] (CrashGuardViewModel owns recordings).
     */
    fun simulateAll(imu: (cls: String, n: Int, heldOut: Boolean) -> Unit) {
        val s = session ?: return
        val items = _ui.value.progress
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (p in items) {
                        val item = p.item
                        val needDev = (item.targetCount - p.dev).coerceAtLeast(0)
                        val needHeld = if (item.needsHeldOut && p.heldOut == 0 && item.targetCount > 0) 1 else 0
                        when (item.capture) {
                            CaptureKind.RUN -> {
                                if (needDev > 0) s.project.simulate(item.id, needDev, Split.DEV)
                                if (needHeld > 0) s.project.simulate(item.id, needHeld, Split.HELD_OUT)
                            }
                            CaptureKind.CROP -> {
                                val f = s.cropForge(s.def.heads.first { item.headClass in it.classes }.headId)
                                if (needDev > 0) f.simulate(item.headClass!!, needDev * 8, false)
                                if (needHeld > 0) f.simulate(item.headClass!!, 8, true)
                            }
                            CaptureKind.IMU -> if (item.targetCount > 0) {
                                if (needDev > 0) imu(item.headClass!!, needDev, false)
                                if (needHeld > 0) imu(item.headClass!!, needHeld, true)
                            }
                        }
                    }
                }
                refresh(); msg("Plan filled with SIMULATION examples. They are labelled and are not real accuracy.")
            } catch (e: Exception) { err("Simulation failed: ${e.message}") }
        }
    }

    fun clearSimulated() {
        val s = session ?: return
        s.project.clearExamples(simulationOnly = true)
        s.cropHeads().forEach { s.cropForge(it.headId).clearSimulated() }
        refresh(); msg("SIMULATION examples removed.")
    }

    fun deleteExamples() {
        val s = session ?: return
        s.project.clearExamples(); s.cropHeads().forEach { s.cropForge(it.headId).clearExamples() }
        refresh(); msg("All examples removed.")
    }

    /** Imports a recorded timeline JSON (e.g. exported earlier) as a labelled example of [itemId]. */
    fun importTimeline(uri: Uri, itemId: String) {
        val s = session ?: return
        val item = _ui.value.plan?.items?.firstOrNull { it.id == itemId } ?: return
        val label = item.label ?: return err("Plan item '${item.title}' has no label; only run items can import timelines.")
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                val tl: Timeline = Contracts.parseTimeline(text)
                val n = s.project.exampleFiles().count { it.planItemId == itemId }
                val split = if (_ui.value.heldOutNext) Split.HELD_OUT else Split.DEV
                s.project.addExample(
                    ExampleFile("imp-$itemId-$n", itemId, label, split, "imp-$itemId-${split.name.lowercase()}", false, emptyMap(),
                        item.stepId?.takeIf { item.kind != com.application.sensorforge.sdk.forge.PlanKind.GOOD }?.let { listOf(it) } ?: emptyList(),
                        java.time.Instant.now().toString(), tl),
                )
                refresh(); msg("Imported timeline as '${item.title}' (${split.name.lowercase()}).")
            } catch (e: Exception) { err("Import failed (${e::class.simpleName}): ${e.message}") }
        }
    }

    /** Imports a video already on the phone: runs the live perception path over it and stores the timeline as a REAL example. */
    fun importClip(uri: Uri, itemId: String) {
        val s = session ?: return
        val item = _ui.value.plan?.items?.firstOrNull { it.id == itemId } ?: return
        val label = item.label ?: return err("Plan item '${item.title}' has no label; only run items can import clips.")
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { s.importClip(uri) }
                val n = s.project.exampleFiles().count { it.planItemId == itemId }
                val split = if (_ui.value.heldOutNext) Split.HELD_OUT else Split.DEV
                s.project.addExample(
                    ExampleFile("clip-$itemId-$n", itemId, label, split, "clip-$itemId-${split.name.lowercase()}", false, emptyMap(),
                        item.stepId?.takeIf { item.kind != com.application.sensorforge.sdk.forge.PlanKind.GOOD }?.let { listOf(it) } ?: emptyList(),
                        java.time.Instant.now().toString(), r.timeline),
                )
                log("forge.import_clip", "use_case" to s.def.id, "item" to itemId, "frames" to r.frames, "usable" to r.usableFrames, "detector" to r.detector)
                msg("Imported clip as '${item.title}' (${split.name.lowercase()}): ${r.usableFrames}/${r.frames} usable frames, detector ${r.detector}.")
            } catch (e: Exception) { err("Clip import failed (${e::class.simpleName}): ${e.message}") }
            _ui.update { it.copy(busy = false) }
            refresh()
        }
    }

    fun setPlanItemLabel(exampleId: String, label: com.application.sensorforge.sdk.forge.ExampleLabel) { session?.project?.relabel(exampleId, label = label); refresh() }
    fun examples() = session?.project?.exampleFiles().orEmpty()
    fun deleteExample(id: String) { session?.project?.deleteExample(id); refresh() }
    fun moveSplit(id: String, s: Split) { session?.project?.relabel(id, split = s); refresh() }

    fun cropForge(headId: String) = session!!.cropForge(headId)

    // ---- Train (crop heads; IMU heads train through CrashGuardViewModel) ----
    fun trainCrop(headId: String) {
        val s = session ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val (_, report) = withContext(Dispatchers.Default) { s.cropForge(headId).train() }
                _ui.update { it.copy(reports = it.reports + (headId to report)) }
                log("forge.train", "use_case" to s.def.id, "head" to headId, "version" to report.headVersion, "ms" to report.trainMillis, "parent" to report.parentSha256.take(12), "trained" to report.trainedWeightsSha256.take(12))
                msg("Trained ${headId} v${report.headVersion} (${report.trainMillis} ms). Approve it to use it.")
            } catch (e: Exception) { err("Train refused: ${e.message}") }
            _ui.update { it.copy(busy = false) }
            refresh()
        }
    }

    fun approveHead(headId: String, version: Int) { session?.cropForge(headId)?.approve(version); refresh(); msg("$headId v$version approved.") }

    // ---- Test ----
    fun runTest() {
        val s = session ?: return
        if (s.project.isDetect()) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) {
                    listOf(s.project.score(), s.project.inductionOrder(), s.project.inductionRequirements(), s.project.calibrate())
                }
                @Suppress("UNCHECKED_CAST")
                _ui.update { it.copy(score = r[0] as Score, order = r[1] as List<ConstraintProposal>, requirements = r[2] as List<RequirementProposal>, calibration = r[3] as List<StepCalibration>) }
                msg("Tested ${(r[0] as Score).rows.size} example(s).")
            } catch (e: Exception) { err("Test failed: ${e.message}") }
            _ui.update { it.copy(busy = false) }
        }
    }

    /** Applies calibrated thresholds as a new spec version (regression runs first via check/approve). */
    fun applyCalibration() {
        val s = session ?: return
        val cal = _ui.value.calibration
        if (cal.none { it.status != CalibrationStatus.INFEASIBLE }) return err("Nothing to apply: every step is infeasible or has no data.")
        try {
            val calibrated = Calibration.apply(s.project.procedure(), cal)
            val text = Contracts.json.encodeToString(ProcedureSpec.serializer(), calibrated.copy(version = calibrated.version + 1))
            val deltas = Regression.deltas(s.project.procedure(), Contracts.parseProcedure(text), s.project.examples())
            val broke = Regression.breaks(deltas)
            if (broke.isNotEmpty()) return err("Calibration would break ${broke.size} example(s): ${broke.first().consequence}")
            val n = s.project.approveSpec(text)
            s.project.saveThresholds(cal)
            refresh(); runTest()
            msg("Calibration applied as spec v$n (${deltas.size} verdict change(s), none worse). Calibration, not training.")
        } catch (e: Exception) { err("Calibration not applied: ${e.message}") }
    }

    // ---- Export / Import ----
    fun export() {
        val s = session ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { s.exportPack() }
                _ui.update { it.copy(export = r) }
                log("forge.export", "use_case" to s.def.id, "pack_sha256" to r.manifest.packSha256, "warnings" to r.warnings.size)
                msg("Exported ${r.file.name}")
            } catch (e: Exception) { err("Export failed: ${e.message}") }
            _ui.update { it.copy(busy = false) }
            refresh()
        }
    }

    fun openPackFile(file: File) {
        val r = ForgeSession.openPack(getApplication(), file)
        _ui.update { it.copy(opened = r) }
    }

    fun importPack(uri: Uri) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "import").apply { mkdirs() }
                File(dir, "import-${System.currentTimeMillis()}.sfpkg").also { out ->
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            openPackFile(f)
        }
    }

    /** Spec the Run screen should load: the opened/latest pack, else the active spec as a file. */
    fun runTarget(): String {
        val s = session ?: return ""
        (_ui.value.opened as? PackOpen.Ok)?.let { return it.summary.path }
        s.latestPack()?.let { return it.absolutePath }
        return File(s.project.root, "run_spec.json").also { it.writeText(s.project.specText()) }.absolutePath
    }

    /** Parameters for Run (e.g. start_time) from the Plan config; blank TIME values are not passed. */
    fun runParams(): Map<String, Any> {
        val kinds = _ui.value.def?.config.orEmpty().associate { it.key to it.kind }
        return _ui.value.config.filterValues { it.isNotBlank() }.mapValues { (k, v) ->
            if (kinds[k] == com.application.sensorforge.sdk.forge.ConfigField.Kind.ITEMS) v.split(',').map { it.trim() }.filter { it.isNotEmpty() } else v
        }
    }

    /** Saves a live Run result as a labelled example of plan item [itemId] (provenance: not simulation). */
    fun saveLive(itemId: String, timeline: Timeline) {
        val s = session ?: return
        val item = _ui.value.plan?.items?.firstOrNull { it.id == itemId } ?: return
        val label = item.label ?: return err("'${item.title}' is not a run item.")
        val n = s.project.exampleFiles().count { it.planItemId == itemId }
        val split = if (_ui.value.heldOutNext) Split.HELD_OUT else Split.DEV
        val params = runParams().mapValues { (_, v) -> (v as? List<*>)?.map { it.toString() } ?: listOf(v.toString()) }
        s.project.addExample(
            ExampleFile("live-$itemId-$n", itemId, label, split, "live-$itemId-${split.name.lowercase()}", false, params,
                item.stepId?.takeIf { item.kind != com.application.sensorforge.sdk.forge.PlanKind.GOOD }?.let { listOf(it) } ?: emptyList(),
                java.time.Instant.now().toString(), timeline),
        )
        log("forge.save_live", "use_case" to s.def.id, "item" to itemId, "split" to split.name, "verdict_expected" to label.expected.name)
        refresh(); msg("Saved live run to '${item.title}' (${split.name.lowercase()}).")
    }
}
