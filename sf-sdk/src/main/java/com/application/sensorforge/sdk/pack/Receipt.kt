package com.application.sensorforge.sdk.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Inputs of an evidence receipt (tech spec §24). Pure. */
data class ReceiptInput(
    val packId: String,
    val packVersion: Int,
    val packSha256: String,
    val perceptionStack: String,
    val params: Map<String, String>,
    /** PASS / FAIL / UNVERIFIED for verify mode; null for detect. */
    val verdict: String?,
    /** Incident state name for detect mode; null for verify. */
    val incidentState: String?,
    /** (code, ref, message) */
    val reasons: List<Triple<String, String, String>>,
    /** (step id, status) */
    val steps: List<Pair<String, String>>,
    val clipSha256: String?,
    val timelineSha256: String?,
    val createdAtUtc: String,
    val elapsedNs: Long,
    val deviceModel: String,
    /** True when the evidence came from a replay/fixture (rule 2). */
    val simulation: Boolean = false,
)

enum class ReceiptCheck { VALID, INVALID }

data class ReceiptVerification(val result: ReceiptCheck, val reason: String?) {
    val valid get() = result == ReceiptCheck.VALID
    /** UI text. */
    val label get() = if (valid) "Receipt valid" else "Receipt invalid"
}

object Receipts {
    private const val HASH = "receipt_sha256"

    /** Receipt JSON (pretty-free, canonical key order) including its own `receipt_sha256`. */
    fun create(r: ReceiptInput): String {
        val body = buildJsonObject {
            put("pack_id", r.packId)
            put("pack_version", r.packVersion)
            put("pack_sha256", r.packSha256)
            put("perception_stack", r.perceptionStack)
            put("params", JsonObject(r.params.mapValues { JsonPrimitive(it.value) }))
            put("verdict", r.verdict?.let { JsonPrimitive(it) } ?: JsonNull)
            put("incident_state", r.incidentState?.let { JsonPrimitive(it) } ?: JsonNull)
            put("reasons", JsonArray(r.reasons.map { (c, ref, m) -> buildJsonObject { put("code", c); put("ref", ref); put("message", m) } }))
            put("steps", JsonArray(r.steps.map { (id, st) -> buildJsonObject { put("id", id); put("status", st) } }))
            put("clip_sha256", r.clipSha256?.let { JsonPrimitive(it) } ?: JsonNull)
            put("timeline_sha256", r.timelineSha256?.let { JsonPrimitive(it) } ?: JsonNull)
            put("created_at_utc", r.createdAtUtc)
            put("elapsed_ns", r.elapsedNs)
            put("device_model", r.deviceModel)
            put("simulation", r.simulation)
        }
        val sha = Canonical.sha256(Canonical.string(body))
        return Canonical.string(JsonObject(body + (HASH to JsonPrimitive(sha))))
    }

    /**
     * Recomputes the receipt hash; any changed byte of a field → invalid.
     * [timelineText] / [clipBytes], when given, are re-hashed against the recorded hashes.
     */
    fun verify(receiptJson: String, timelineText: String? = null, clipBytes: ByteArray? = null): ReceiptVerification {
        val obj = try { Json.parseToJsonElement(receiptJson).jsonObject } catch (e: Exception) {
            return ReceiptVerification(ReceiptCheck.INVALID, "unreadable JSON")
        }
        val recorded = (obj[HASH] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ReceiptVerification(ReceiptCheck.INVALID, "no receipt_sha256")
        val actual = Canonical.sha256(Canonical.string(JsonObject(obj - HASH)))
        if (actual != recorded) return ReceiptVerification(ReceiptCheck.INVALID, "receipt hash does not match its content")
        if (timelineText != null) {
            val want = (obj["timeline_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (want != Canonical.sha256(timelineText)) return ReceiptVerification(ReceiptCheck.INVALID, "timeline does not match timeline_sha256")
        }
        if (clipBytes != null) {
            val want = (obj["clip_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (want != Canonical.sha256(clipBytes)) return ReceiptVerification(ReceiptCheck.INVALID, "clip does not match clip_sha256")
        }
        return ReceiptVerification(ReceiptCheck.VALID, null)
    }

    /** Field → string value, for the S16 receipt screen (monospace list). */
    fun fields(receiptJson: String): List<Pair<String, String>> =
        Json.parseToJsonElement(receiptJson).jsonObject.map { (k, v) -> k to display(v) }

    private fun display(e: JsonElement): String = when (e) {
        is JsonPrimitive -> if (e is JsonNull) "null" else e.content
        else -> e.toString()
    }

    fun field(receiptJson: String, name: String) = Json.parseToJsonElement(receiptJson).jsonObject[name]?.jsonPrimitive?.content
}
