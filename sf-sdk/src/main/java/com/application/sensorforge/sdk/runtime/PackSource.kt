package com.application.sensorforge.sdk.runtime

import android.content.Context
import android.os.Build
import com.application.sensorforge.sdk.engine.Decision
import com.application.sensorforge.sdk.pack.Canonical
import com.application.sensorforge.sdk.pack.LoadedPack
import com.application.sensorforge.sdk.pack.Pack
import com.application.sensorforge.sdk.pack.ReceiptInput
import com.application.sensorforge.sdk.pack.Receipts
import com.application.sensorforge.sdk.train.Head
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** Resolved pack input: a bare spec JSON (asset/file) or a `.sfpkg` that passed integrity + models.lock. */
internal class ResolvedPack(
    val specText: String,
    val sha256: String,
    val pack: LoadedPack?,
) {
    /** Heads shipped in the pack (override the on-device approved head). */
    fun heads(): Map<String, Head> = pack?.contents?.heads.orEmpty().mapValues { Json { ignoreUnknownKeys = true }.decodeFromString(Head.serializer(), it.value) }
}

internal object PackSource {
    fun readBytes(context: Context, path: String): ByteArray =
        if (path.startsWith("asset:")) context.applicationContext.assets.open(path.removePrefix("asset:")).use { it.readBytes() }
        else File(path).readBytes()

    fun resolve(context: Context, path: String): ResolvedPack {
        val bytes = readBytes(context, path)
        if (Pack.looksLikePack(bytes)) {
            val loaded = Pack.open(bytes, ModelRegistry.get(context).currentHashes())
            return ResolvedPack(loaded.contents.spec, loaded.sha256, loaded)
        }
        return ResolvedPack(bytes.decodeToString(), Canonical.sha256(bytes), null)
    }
}

/** Writes `receipt.json` (tech spec §24) next to a clip. */
internal object ReceiptWriter {
    fun write(
        dir: File, packId: String, packVersion: Int, packSha: String, stack: String, params: Map<String, String>,
        decision: Decision, timelineText: String, elapsedNs: Long, simulation: Boolean,
    ): File {
        val json = Receipts.create(
            ReceiptInput(
                packId, packVersion, packSha, stack, params, decision.verdict.name, null,
                decision.reasons.map { Triple(it.code.name, it.ref, it.message) },
                decision.steps.map { it.id to it.status.name },
                clipSha256 = null, timelineSha256 = Canonical.sha256(timelineText),
                createdAtUtc = Instant.now().toString(), elapsedNs = elapsedNs,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}", simulation = simulation,
            )
        )
        return File(dir, "receipt.json").also { it.writeText(json) }
    }
}
