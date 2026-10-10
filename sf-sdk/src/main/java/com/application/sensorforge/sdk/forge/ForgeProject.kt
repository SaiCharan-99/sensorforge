package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.contracts.ContractException
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.models.ModelsLock
import com.application.sensorforge.sdk.pack.Pack
import com.application.sensorforge.sdk.pack.PackContents
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

@Serializable
data class ExampleFile(
    val id: String,
    val planItemId: String?,
    val label: ExampleLabel,
    val split: Split,
    val session: String,
    val simulation: Boolean,
    val params: Map<String, List<String>>,
    val missingSteps: List<String>,
    val createdUtc: String,
    val timeline: Timeline,
) {
    fun toExample() = VerifyExample(id, planItemId, label, split, session, simulation, timeline, params, missingSteps)
}

@Serializable
data class ThresholdsFile(val steps: List<StepThreshold>)

@Serializable
data class StepThreshold(val stepId: String, val threshold: Double, val status: String, val message: String)

@Serializable
data class RegressionFile(val examples: List<RegressionEntry>)

/**
 * File-backed Forge project for one use case (pure JVM: java.io only, so it is unit-tested).
 * Layout: `spec_v<n>.json` + `active_spec.txt`, `plan.json`, `config.json`, `examples/<id>.json`,
 * `thresholds.json`, `regression.json`, `packs/`. Spec versions are immutable; approving
 * a new one is explicit and re-runs regression first.
 */
class ForgeProject(val root: File, val def: UseCaseDef, private val shippedSpec: () -> String) {
    private val json = Contracts.json
    private val examplesDir get() = File(root, "examples")
    val packsDir get() = File(root, "packs")

    init { root.mkdirs() }

    // ---- spec ----
    fun specVersion(): Int = File(root, "active_spec.txt").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0

    fun specText(): String = if (specVersion() == 0) shippedSpec() else File(root, "spec_v${specVersion()}.json").readText()

    fun isDetect() = def.mode == "detect"

    fun procedure(): ProcedureSpec = Contracts.parseProcedure(specText())

    /** Validates then returns errors as strings (empty = valid). Nothing is saved. */
    fun check(text: String): List<String> = try {
        if (isDetect()) Validator.requireValid(Contracts.parseDetect(text)) else Validator.requireValid(Contracts.parseProcedure(text))
        emptyList()
    } catch (e: ContractException) { e.errors.map { "${it.path}: ${it.message}" } }

    /** Saves [text] as the next immutable spec version and makes it active. Throws if invalid. */
    fun approveSpec(text: String): Int {
        val errs = check(text)
        if (errs.isNotEmpty()) throw ContractException(errs.map { com.application.sensorforge.sdk.contracts.ContractError("$", it) })
        val n = (File(root, "spec_v1.json").parentFile.listFiles { f -> Regex("spec_v\\d+\\.json").matches(f.name) }.orEmpty()
            .mapNotNull { it.name.removePrefix("spec_v").removeSuffix(".json").toIntOrNull() }.maxOrNull() ?: 0) + 1
        File(root, "spec_v$n.json").writeText(text)
        File(root, "active_spec.txt").writeText("$n")
        if (!isDetect()) File(root, "regression.json").writeText(json.encodeToString(RegressionFile.serializer(), RegressionFile(Regression.snapshot(Contracts.parseProcedure(text), examples()))))
        return n
    }

    // ---- config ----
    fun config(): Map<String, String> = File(root, "config.json").takeIf { it.exists() }
        ?.let { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it.readText()) }
        ?: def.config.associate { it.key to it.default }

    fun setConfig(key: String, value: String) =
        File(root, "config.json").writeText(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), config() + (key to value)))

    // ---- plan ----
    fun plan(): Plan = File(root, "plan.json").takeIf { it.exists() }?.let { json.decodeFromString(Plan.serializer(), it.readText()) } ?: defaultPlan()

    fun defaultPlan(): Plan = PlanBuilder.build(if (isDetect()) null else procedure(), def.heads, def.config.map { it.label })

    fun savePlan(p: Plan) = File(root, "plan.json").writeText(json.encodeToString(Plan.serializer(), p))

    fun resetPlan(): Plan = defaultPlan().also { savePlan(it) }

    fun acceptPlan() = savePlan(plan().copy(accepted = true))

    // ---- examples (RUN items) ----
    fun examples(): List<VerifyExample> = exampleFiles().map { it.toExample() }

    fun exampleFiles(): List<ExampleFile> = examplesDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        .mapNotNull { runCatching { json.decodeFromString(ExampleFile.serializer(), it.readText()) }.getOrNull() }

    fun addExample(ex: ExampleFile) {
        examplesDir.mkdirs()
        File(examplesDir, "${ex.id}.json").writeText(json.encodeToString(ExampleFile.serializer(), ex))
    }

    fun relabel(id: String, label: ExampleLabel? = null, split: Split? = null) {
        val e = exampleFiles().firstOrNull { it.id == id } ?: return
        addExample(e.copy(label = label ?: e.label, split = split ?: e.split))
    }

    fun deleteExample(id: String) { File(examplesDir, "$id.json").delete() }

    fun clearExamples(simulationOnly: Boolean = false) =
        exampleFiles().filter { !simulationOnly || it.simulation }.forEach { deleteExample(it.id) }

    /** Adds [n] simulated runs of a RUN plan item. Always labelled `simulation = true` (rule 2). */
    fun simulate(itemId: String, n: Int, split: Split = Split.DEV, nowUtc: String = java.time.Instant.now().toString()): List<ExampleFile> {
        val spec = procedure()
        val sc = Simulator.scenarios(spec).firstOrNull { it.id == itemId } ?: throw IllegalArgumentException("no simulation for plan item '$itemId'")
        val existing = exampleFiles().count { it.planItemId == itemId }
        return (0 until n).map { i ->
            val k = existing + i
            val run = Simulator.run(spec, sc, k)
            ExampleFile(
                "sim-$itemId-$k", itemId, labelOf(sc), split, "sim-${itemId}-${split.name.lowercase()}", true, run.params,
                if (sc.kind == ScenarioKind.MISSING) listOf(sc.stepId!!) else emptyList(), nowUtc, run.timeline,
            ).also { addExample(it) }
        }
    }

    private fun labelOf(sc: Scenario) = when (sc.expected) {
        com.application.sensorforge.sdk.engine.Verdict.PASS -> ExampleLabel.VALID
        com.application.sensorforge.sdk.engine.Verdict.FAIL -> ExampleLabel.INVALID
        com.application.sensorforge.sdk.engine.Verdict.UNVERIFIED -> ExampleLabel.INSUFFICIENT
    }

    /** Progress of every RUN plan item (head items are counted by the head forge). */
    fun progress(headCounts: Map<String, Pair<Int, Int>> = emptyMap()): List<PlanBuilder.Progress> {
        val ex = exampleFiles()
        return plan().items.map { item ->
            when (item.capture) {
                CaptureKind.RUN -> PlanBuilder.Progress(item, ex.count { it.planItemId == item.id && it.split == Split.DEV }, ex.count { it.planItemId == item.id && it.split == Split.HELD_OUT })
                else -> headCounts[item.headClass].let { PlanBuilder.Progress(item, it?.first ?: 0, it?.second ?: 0) }
            }
        }
    }

    // ---- test ----
    fun score(): Score = Scoring.score(procedure(), examples())
    fun inductionOrder() = Induction.order(procedure(), examples())
    fun inductionRequirements() = Induction.requirements(procedure(), examples())
    fun calibrate() = Calibration.calibrate(procedure(), examples())

    fun saveThresholds(cal: List<StepCalibration>) =
        File(root, "thresholds.json").writeText(json.encodeToString(ThresholdsFile.serializer(), ThresholdsFile(cal.map { StepThreshold(it.stepId, it.threshold, it.status.name, it.message) })))

    fun thresholds(): ThresholdsFile? = File(root, "thresholds.json").takeIf { it.exists() }?.let { json.decodeFromString(ThresholdsFile.serializer(), it.readText()) }

    // ---- export ----
    /**
     * Builds `<packs>/<id>-v<n>.sfpkg`. [heads] maps head id → head JSON (only approved heads),
     * [trainingJson] holds proof artifacts, [lock] the frozen base models in use.
     */
    fun exportPack(heads: Map<String, String>, trainingJson: String, lock: ModelsLock, profile: List<String>, sensors: List<String>): Pair<File, com.application.sensorforge.sdk.contracts.PackManifest> {
        val spec = specText()
        val errs = check(spec)
        require(errs.isEmpty()) { "spec invalid: ${errs.first()}" }
        val version = maxOf(specVersion(), 1)
        val thresholds = thresholds()?.let { json.encodeToString(ThresholdsFile.serializer(), it) } ?: "{\"steps\":[]}"
        val regression = File(root, "regression.json").takeIf { it.exists() }?.readText()
            ?: json.encodeToString(RegressionFile.serializer(), RegressionFile(if (isDetect()) emptyList() else Regression.snapshot(procedure(), examples())))
        val (bytes, manifest) = Pack.build(
            PackContents(Pack.manifestFor(def.id, version, def.mode, profile, sensors), spec, heads, thresholds, regression, trainingJson, lock),
        )
        packsDir.mkdirs()
        val out = File(packsDir, "${def.id}-v$version-${manifest.packSha256.take(8)}.sfpkg")
        out.writeBytes(bytes)
        return out to manifest
    }

    fun packs(): List<File> = packsDir.listFiles { f -> f.extension == "sfpkg" }.orEmpty().sortedByDescending { it.lastModified() }

    companion object {
        fun exampleListSerializer() = ListSerializer(ExampleFile.serializer())
    }
}
