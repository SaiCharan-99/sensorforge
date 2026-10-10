package com.application.sensorforge.sdk

import android.content.Context
import com.application.sensorforge.sdk.camera.CameraVerifier
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.SpecExpander
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.perception.Tags
import com.application.sensorforge.sdk.runtime.PackSource
import com.application.sensorforge.sdk.train.Head
import com.application.sensorforge.sdk.train.HeadStore
import java.io.File
import java.security.MessageDigest

/**
 * Public SDK entry point (tech spec §4). End apps must use only this API.
 *
 * `packPath` is, until `.sfpkg` packs land (Phase 4), a verify-mode procedure spec JSON:
 * `asset:<path>` for a bundled asset, otherwise a file path.
 */
object SensorForge {
    const val SCHEMA_VERSION = "3.0"

    /** Identity heads (H1) live here: `files/heads/identity_<procedure_id>/v<n>`. */
    fun headsDir(context: Context) = File(context.filesDir, "heads")
    fun identityHeadId(procedureId: String) = "identity_$procedureId"

    fun load(context: Context, packPath: String, params: Map<String, Any> = emptyMap()): Verifier {
        val app = context.applicationContext
        val resolved = PackSource.resolve(app, packPath)
        val raw = Contracts.parseProcedure(resolved.specText)
        Validator.requireValid(raw)
        val lists = params.mapValues { (_, v) -> (v as? List<*>)?.map { it.toString() } ?: listOf(v.toString()) }
        val spec = SpecExpander.expand(raw, lists)
        Validator.requireValid(spec)

        val needsCamera = spec.entities.isNotEmpty()
        if (!needsCamera) throw CapabilityMissing("Only camera verify packs are supported by load() so far")
        val head = if (spec.entities.any { it.head == "identity" }) resolved.heads().values.firstOrNull() ?: activeHead(app, identityHeadId(spec.procedureId)) else null
        val info = PackInfo(
            id = spec.procedureId, version = spec.version, mode = spec.mode,
            sha256 = resolved.sha256, perceptionStack = Tags.STACK, stepIds = spec.steps.map { it.id },
        )
        return CameraVerifier(context, spec, info, head, File(app.filesDir, "verify/${spec.procedureId}"), lists)
    }

    /** Receipt (tech spec §24) read back from disk: fields for S16 and the recomputed validity. */
    data class ReceiptView(val fields: List<Pair<String, String>>, val verification: com.application.sensorforge.sdk.pack.ReceiptVerification, val json: String)

    /** Verifies `receipt.json` (and the neighbouring `timeline.json`, if present) from the stored bytes. */
    fun readReceipt(path: String): ReceiptView {
        val f = File(path)
        val json = f.readText()
        val tl = File(f.parentFile, "timeline.json").takeIf { it.exists() }?.readText()
        return ReceiptView(
            runCatching { com.application.sensorforge.sdk.pack.Receipts.fields(json) }.getOrDefault(emptyList()),
            com.application.sensorforge.sdk.pack.Receipts.verify(json, tl), json,
        )
    }

    /** Identity head (H1) authoring for a procedure: capture labelled crops, train, approve. */
    fun identityForge(context: Context, procedureId: String, tagClass: String, classes: List<String>) =
        com.application.sensorforge.sdk.camera.IdentityForge(context, identityHeadId(procedureId), tagClass, classes)

    /** Crop head (H1/H2) authoring by head id, e.g. `state_sealsafe`. */
    fun cropHeadForge(context: Context, headId: String, tagClass: String, classes: List<String>) =
        com.application.sensorforge.sdk.camera.IdentityForge(context, headId, tagClass, classes)

    /** Guided setup (Describe, Plan, Capture, Train, Test, Export, Import) for one use case. */
    fun forge(context: Context, useCaseId: String) = com.application.sensorforge.sdk.runtime.ForgeSession.forId(context, useCaseId)

    /** Built-in plus developer-forged use cases ("Forge new verifier"). */
    fun useCases(context: Context) = com.application.sensorforge.sdk.runtime.CustomUseCases.all(context)
    fun useCase(context: Context, id: String) = com.application.sensorforge.sdk.runtime.CustomUseCases.byId(context, id)
    fun createUseCase(context: Context, title: String, sentence: String) = com.application.sensorforge.sdk.runtime.CustomUseCases.create(context, title, sentence)
    fun deleteUseCase(context: Context, id: String) = com.application.sensorforge.sdk.runtime.CustomUseCases.delete(context, id)

    /** Opens a `.sfpkg` and reports a typed outcome (hash, models.lock check). */
    fun openPack(context: Context, file: File) = com.application.sensorforge.sdk.runtime.ForgeSession.openPack(context, file)

    private fun activeHead(context: Context, id: String): Head? {
        val store = HeadStore(headsDir(context), id)
        return store.active()?.let { store.load(it) }
    }

    internal fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

/** Printable tags for tagged-prop perception (the M1 fallback, declared as "controlled props"). */
object TagSheet {
    fun render(payload: String, px: Int = 512): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, px, px,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val out = IntArray(px * px) { if (m[it % px, it / px]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return android.graphics.Bitmap.createBitmap(out, px, px, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** Tags for a procedure: zone corners + floor for containers, state markers, one per entity (with expected code). */
    fun forPack(context: android.content.Context, packPath: String): List<Pair<String, String>> {
        val app = context.applicationContext
        val text = if (packPath.startsWith("asset:")) app.assets.open(packPath.removePrefix("asset:")).use { it.readBytes() }.decodeToString()
        else File(packPath).readText()
        val spec = Contracts.parseProcedure(text)
        val out = mutableListOf<Pair<String, String>>()
        val zones = spec.steps.mapNotNull { it.zone }.distinct()
        for (z in zones) {
            (0..3).forEach { out += "Corner ${it + 1} of $z" to Tags.zoneCorner(z, it) }
            out += "Floor of $z" to Tags.floor(z)
        }
        for (s in spec.steps.filter { it.state != null }) {
            val cls = spec.entities.first { it.id == s.entity }.cls
            out += "$cls ${s.state} (inside lid)" to Tags.state(cls, s.state!!)
            out += "$cls closed (lid top)" to Tags.state(cls, "closed")
        }
        val containers = zones.map { it.removeSuffix("_interior") }.toSet()
        for (e in spec.entities.filter { it.cls !in containers }) out += "${e.cls} ${e.expectedCode.orEmpty()}".trim() to Tags.obj(e.cls, e.expectedCode)
        for (f in spec.prohibited.filterIsInstance<com.application.sensorforge.sdk.contracts.ForbiddenClass>()) out += "${f.cls} (negative)" to Tags.obj(f.cls)
        for (m in spec.prohibited.filterIsInstance<com.application.sensorforge.sdk.contracts.MaxInstances>()) {
            val e = spec.entities.first { it.id == m.entity }
            out += "${e.cls} duplicate (negative)" to Tags.obj(e.cls, "DUP")
        }
        return out.distinctBy { it.second }
    }
}
