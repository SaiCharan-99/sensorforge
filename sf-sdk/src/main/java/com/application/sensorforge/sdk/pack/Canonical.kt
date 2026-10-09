package com.application.sensorforge.sdk.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Canonical JSON (sorted keys, no whitespace) so hashes are stable across writers. Pure. */
object Canonical {
    private val parser = Json

    fun canonicalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
        is JsonArray -> JsonArray(e.map { canonicalize(it) })
        else -> e
    }

    fun string(e: JsonElement): String = canonicalize(e).toString()

    fun ofText(json: String): String = string(parser.parseToJsonElement(json))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}
