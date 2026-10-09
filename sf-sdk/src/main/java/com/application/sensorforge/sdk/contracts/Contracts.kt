package com.application.sensorforge.sdk.contracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Typed contract error (tech spec §8). */
class ContractError(val path: String, override val message: String) : Exception("$path: $message")

class ContractException(val errors: List<ContractError>) :
    Exception(errors.joinToString("; ") { "${it.path}: ${it.message}" })

/** Strict JSON: unknown keys are rejected. */
object Contracts {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun parseProcedure(text: String): ProcedureSpec = parse("$", text) { json.decodeFromString<ProcedureSpec>(it) }
    fun parseDetect(text: String): DetectSpec = parse("$", text) { json.decodeFromString<DetectSpec>(it) }
    fun parseTimeline(text: String): Timeline = parse("$", text) { json.decodeFromString<Timeline>(it) }
    fun parseManifest(text: String): PackManifest = parse("$", text) { json.decodeFromString<PackManifest>(it) }

    private inline fun <T> parse(path: String, text: String, block: (String) -> T): T =
        try {
            block(text)
        } catch (e: SerializationException) {
            throw ContractException(listOf(ContractError(path, e.message ?: "invalid JSON")))
        } catch (e: IllegalArgumentException) {
            throw ContractException(listOf(ContractError(path, e.message ?: "invalid JSON")))
        }
}
