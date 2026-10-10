package com.application.sensorforge.sdk.runtime

import android.content.Context
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.sdk.camera.IdentityForge
import com.application.sensorforge.sdk.forge.CaptureKind
import com.application.sensorforge.sdk.forge.ForgeProject
import com.application.sensorforge.sdk.forge.HeadPlan
import com.application.sensorforge.sdk.forge.TemplateCompiler
import com.application.sensorforge.sdk.forge.UseCaseDef
import com.application.sensorforge.sdk.forge.UseCases
import com.application.sensorforge.sdk.contracts.PackManifest
import com.application.sensorforge.sdk.models.LockMismatch
import com.application.sensorforge.sdk.models.ModelsLock
import com.application.sensorforge.sdk.pack.Pack
import com.application.sensorforge.sdk.pack.PackIntegrityError
import com.application.sensorforge.sdk.pack.PerceptionStackMismatch
import com.application.sensorforge.sdk.train.HeadStore
import java.io.File
import java.security.MessageDigest

/** What an opened `.sfpkg` contains, for the Import step and for HelloForge. */
data class PackSummary(
    val manifest: PackManifest,
    val sha256: String,
    val stepIds: List<String>,
    val headIds: List<String>,
    val lock: ModelsLock,
    val sizeBytes: Long,
    val path: String,
)

/** Typed outcome of opening a pack (never throws for expected failures, never falls through to PASS). */
sealed interface PackOpen {
    data class Ok(val summary: PackSummary) : PackOpen
    data class Mismatch(val mismatches: List<LockMismatch>) : PackOpen
    data class Invalid(val type: String, val message: String) : PackOpen
}

data class ExportResult(val file: File, val manifest: PackManifest, val warnings: List<String>)

/**
 * Forge for one use case on this phone: spec versions, plan, captured/simulated examples, head
 * training (via [IdentityForge] for crop heads), scoring/calibration, and `.sfpkg` export.
 * Pure logic lives in [ForgeProject]; this class adds Android storage, the model registry and heads.
 */
class ForgeSession internal constructor(private val context: Context, val def: UseCaseDef) {
    private val app = context.applicationContext
    val project = ForgeProject(File(app.filesDir, "forge/${def.id}"), def) {
        if (def.specAsset.startsWith("file:")) File(def.specAsset.removePrefix("file:")).readText()
        else app.assets.open(def.specAsset).use { it.readBytes().decodeToString() }
    }
    private val registry get() = ModelRegistry.get(app)
    private val crop = mutableMapOf<String, IdentityForge>()

    /** Crop-head forge (H1/H2) for this use case. */
    fun cropForge(headId: String): IdentityForge = crop.getOrPut(headId) {
        SensorForge.cropHeadForge(app, headId, def.cropTagClass.getValue(headId), def.heads.first { it.headId == headId }.classes)
    }

    fun cropHeads(): List<HeadPlan> = def.heads.filter { it.capture == CaptureKind.CROP }

    /** Head store for any of this use case's heads. */
    fun headStore(headId: String): HeadStore {
        val h = def.heads.first { it.headId == headId }
        return if (h.capture == CaptureKind.IMU) HeadStore(File(app.filesDir, "crashguard/heads"), headId) else HeadStore(File(app.filesDir, "heads"), headId)
    }

    /** class → (dev, held-out) counts for all crop heads; IMU counts come from the caller. */
    fun cropCounts(): Map<String, Pair<Int, Int>> = cropHeads().flatMap { cropForge(it.headId).counts().entries }.associate { it.key to it.value }

    fun progress(extra: Map<String, Pair<Int, Int>> = emptyMap()) = project.progress(cropCounts() + extra)

    fun isSetUp() = File(project.root, "setup_done").exists()

    /** Imports a video from the phone as a real (not simulated) timeline through the live perception path. */
    fun importClip(uri: android.net.Uri, onProgress: (Float) -> Unit = {}): com.application.sensorforge.sdk.camera.ClipImporter.Result {
        val head = def.heads.firstOrNull { it.capture == CaptureKind.CROP }?.let { headStore(it.headId).let { st -> st.active()?.let { v -> st.load(v) } } }
        return com.application.sensorforge.sdk.camera.ClipImporter(app, project.procedure(), head).run(uri, onProgress = onProgress)
    }

    /** Exports the active spec, approved heads, thresholds, regression and models.lock as `.sfpkg`. */
    fun exportPack(): ExportResult {
        val warnings = mutableListOf<String>()
        val heads = mutableMapOf<String, String>()
        val training = StringBuilder("{")
        for (h in def.heads) {
            val store = headStore(h.headId)
            val v = store.active()
            if (v == null) { warnings += "${h.slot} (${h.headId}) has no approved head: its fallback is used (${fallbackOf(h)})."; continue }
            heads[h.headId] = File(store.dirOf(v), "head.json").readText()
            if (training.length > 1) training.append(',')
            training.append("\"${h.headId}\":").append(File(store.dirOf(v), "training.json").readText())
        }
        training.append('}')
        val profile = if (project.isDetect()) listOf("sensors") else def.profile ?: TemplateCompiler.profileOf(project.procedure())
        val sensors = if (project.isDetect()) def.sensors.filter { it != "location" || true } else def.sensors
        val lock = registry.lockOf(def.models.map { if (it.startsWith("m2_")) com.application.sensorforge.sdk.camera.Embedders.best(app).modelId else it })
        if (!project.isDetect()) {
            val s = project.score()
            if (s.dev.n + s.heldOut.n == 0) warnings += "No examples were tested: this pack has no regression evidence."
            if (!s.shippable && s.dev.n + s.heldOut.n > 0) warnings += "False accepts: dev ${s.dev.falseAccepts}, held-out ${s.heldOut.falseAccepts}."
            if (project.examples().any { it.simulation }) warnings += "Some examples are SIMULATION; the numbers are not real accuracy."
        }
        val (file, manifest) = project.exportPack(heads, training.toString(), lock, profile, sensors)
        File(project.root, "setup_done").writeText(manifest.packSha256)
        return ExportResult(file, manifest, warnings)
    }

    private fun fallbackOf(h: HeadPlan) = com.application.sensorforge.sdk.models.ModelCatalog.ENTRIES.firstOrNull { it.slot == h.slot }?.fallbackClaim ?: "none"

    fun packs(): List<File> = project.packs()

    /** Latest exported pack path, or null (the shipped spec is used then). */
    fun latestPack(): File? = packs().firstOrNull()

    companion object {
        fun forId(context: Context, id: String) = ForgeSession(context, CustomUseCases.byId(context, id))

        /** Opens a pack file and reports a typed outcome (Import step, HelloForge). */
        fun openPack(context: Context, file: File): PackOpen = try {
            val bytes = file.readBytes()
            val p = Pack.open(bytes, ModelRegistry.get(context).currentHashes())
            val steps = runCatching {
                if (p.isDetect) emptyList() else com.application.sensorforge.sdk.contracts.Contracts.parseProcedure(p.contents.spec).steps.map { it.id }
            }.getOrDefault(emptyList())
            PackOpen.Ok(PackSummary(p.manifest, p.sha256, steps, p.contents.heads.keys.toList(), p.contents.lock, bytes.size.toLong(), file.absolutePath))
        } catch (e: PerceptionStackMismatch) {
            PackOpen.Mismatch(e.mismatches)
        } catch (e: PackIntegrityError) {
            PackOpen.Invalid("PackIntegrityError", e.message ?: "pack is not valid")
        } catch (e: com.application.sensorforge.sdk.contracts.ContractException) {
            PackOpen.Invalid("ContractError", e.message ?: "contract error")
        } catch (e: Exception) {
            PackOpen.Invalid(e::class.simpleName ?: "Error", e.message ?: "cannot open pack")
        }

        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
