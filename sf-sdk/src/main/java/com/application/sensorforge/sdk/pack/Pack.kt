package com.application.sensorforge.sdk.pack

import com.application.sensorforge.sdk.contracts.ContractError
import com.application.sensorforge.sdk.contracts.ContractException
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.PackManifest
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.contracts.Vocabulary
import com.application.sensorforge.sdk.models.LockMismatch
import com.application.sensorforge.sdk.models.ModelCatalog
import com.application.sensorforge.sdk.models.ModelsLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Frozen base models differ from what the pack was made with (tech spec §5). Fail closed. */
class PerceptionStackMismatch(val mismatches: List<LockMismatch>) :
    Exception("Perception stack mismatch: " + mismatches.joinToString { "${it.id} expected ${it.expected.take(8)}… got ${it.actual?.take(8) ?: "missing"}" })

/** The pack bytes are not a readable `.sfpkg` or its hash does not match its manifest. */
class PackIntegrityError(message: String) : Exception(message)

/** Contents of a verifier pack (tech spec §5). All values are JSON text except [assets]. */
data class PackContents(
    val manifest: PackManifest,
    val spec: String,
    val heads: Map<String, String> = emptyMap(),
    val thresholds: String = "{}",
    val regression: String = "{\"examples\":[]}",
    val training: String = "{}",
    val lock: ModelsLock,
    val assets: Map<String, ByteArray> = emptyMap(),
)

/** A pack that passed integrity, contract and (optionally) models.lock checks. */
class LoadedPack(val contents: PackContents, val bytes: ByteArray) {
    val manifest get() = contents.manifest
    val sha256 get() = manifest.packSha256
    val isDetect get() = manifest.mode == "detect"
}

object Pack {
    private const val MANIFEST = "manifest.json"
    private val DOC_ORDER = listOf("spec.json", "thresholds.json", "regression.json", "training.json", "models.lock")

    /**
     * `pack_sha256` = SHA-256 over the sorted list of `name:sha256(canonical content)` for every
     * entry, with the manifest hashed while its own `pack_sha256` is empty.
     */
    fun computeSha256(files: Map<String, ByteArray>): String {
        val lines = files.toSortedMap().map { (name, bytes) ->
            val content = when {
                name == MANIFEST -> {
                    val m = Contracts.parseManifest(bytes.decodeToString()).copy(packSha256 = "")
                    Canonical.string(Contracts.json.encodeToJsonElement(PackManifest.serializer(), m)).toByteArray()
                }
                name.endsWith(".json") || name == "models.lock" -> Canonical.ofText(bytes.decodeToString()).toByteArray()
                else -> bytes
            }
            "$name:${Canonical.sha256(content)}"
        }
        return Canonical.sha256(lines.joinToString("\n"))
    }

    /** Builds `.sfpkg` bytes. The returned manifest carries the computed `pack_sha256`. */
    fun build(c: PackContents): Pair<ByteArray, PackManifest> {
        val files = linkedMapOf<String, ByteArray>()
        val unsigned = c.manifest.copy(packSha256 = "")
        files[MANIFEST] = Contracts.json.encodeToString(PackManifest.serializer(), unsigned).toByteArray()
        files["spec.json"] = c.spec.toByteArray()
        c.heads.toSortedMap().forEach { (id, text) -> files["heads/$id.json"] = text.toByteArray() }
        files["thresholds.json"] = c.thresholds.toByteArray()
        files["regression.json"] = c.regression.toByteArray()
        files["training.json"] = c.training.toByteArray()
        files["models.lock"] = Contracts.json.encodeToString(ModelsLock.serializer(), c.lock).toByteArray()
        c.assets.toSortedMap().forEach { (n, b) -> files["assets/$n"] = b }

        val sha = computeSha256(files)
        val signed = unsigned.copy(packSha256 = sha)
        files[MANIFEST] = Contracts.json.encodeToString(PackManifest.serializer(), signed).toByteArray()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            // Fixed entry time so the same content gives the same bytes.
            (listOf(MANIFEST) + DOC_ORDER + files.keys.filter { it !in DOC_ORDER && it != MANIFEST }.sorted()).distinct().forEach { name ->
                z.putNextEntry(ZipEntry(name).apply { time = 0L })
                z.write(files.getValue(name))
                z.closeEntry()
            }
        }
        return out.toByteArray() to signed
    }

    /**
     * Opens a pack: zip → hash check → manifest + spec validation.
     * @param currentHashes model id → sha256 now available (see ModelRegistry); null skips the
     *   models.lock check (JVM tests / inspection). When given, any mismatch throws
     *   [PerceptionStackMismatch] (fail closed).
     */
    fun open(bytes: ByteArray, currentHashes: Map<String, String?>? = null): LoadedPack {
        val files = readZip(bytes)
        val manifestBytes = files[MANIFEST] ?: throw PackIntegrityError("not a SensorForge pack: manifest.json missing")
        val manifest = try { Contracts.parseManifest(manifestBytes.decodeToString()) } catch (e: ContractException) { throw PackIntegrityError("manifest unreadable: ${e.message}") }
        val actual = computeSha256(files)
        if (actual != manifest.packSha256) throw PackIntegrityError("pack hash mismatch: manifest says ${manifest.packSha256.take(12)}…, content is ${actual.take(12)}…")

        Validator.requireValid(manifest)
        fun text(n: String) = files[n]?.decodeToString() ?: throw PackIntegrityError("$n missing")
        val spec = text("spec.json")
        val heads = files.filterKeys { it.startsWith("heads/") }.mapKeys { it.key.removePrefix("heads/").removeSuffix(".json") }.mapValues { it.value.decodeToString() }
        if (manifest.mode == "detect") Validator.requireValid(Contracts.parseDetect(spec)) // a detect pack without a head uses the calibrated threshold
        else Validator.requireValid(Contracts.parseProcedure(spec))
        val lock = Contracts.json.decodeFromString(ModelsLock.serializer(), text("models.lock"))
        if (currentHashes != null) {
            val bad = ModelCatalog.check(lock, currentHashes)
            if (bad.isNotEmpty()) throw PerceptionStackMismatch(bad)
        }
        return LoadedPack(
            PackContents(
                manifest, spec, heads, text("thresholds.json"), text("regression.json"), text("training.json"), lock,
                files.filterKeys { it.startsWith("assets/") }.mapKeys { it.key.removePrefix("assets/") },
            ),
            bytes,
        )
    }

    fun looksLikePack(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    require(!e.name.contains("..") && !e.name.startsWith("/")) { "unsafe entry ${e.name}" }
                    out[e.name] = z.readBytes()
                }
            }
        } catch (e: Exception) {
            throw PackIntegrityError("unreadable pack: ${e.message}")
        }
        return out
    }

    /** Manifest for a freshly forged pack. */
    fun manifestFor(packId: String, version: Int, mode: String, profile: List<String>, sensors: List<String>) =
        PackManifest(Vocabulary.SCHEMA_VERSION, packId, version, mode, profile, sensors, createdOnDevice = true, packSha256 = "")
}

/** Used for ContractError-style reporting of problems found before opening. */
fun packError(path: String, message: String) = ContractException(listOf(ContractError(path, message)))
