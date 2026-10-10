package com.application.sensorforge.sdk.runtime

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Session JSONL log (tech spec §29): one line per decision, observation summary or action, in
 * `files/logs/session-<date>.jsonl`. Summaries only: no frames, audio or raw sensor streams.
 */
object SessionLog {
    private var dir: File? = null

    @Synchronized
    fun init(context: Context) { if (dir == null) dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() } }

    /** `kind` e.g. forge.approve_spec, forge.train, verify.result, crashguard.state. */
    @Synchronized
    fun log(kind: String, vararg data: Pair<String, Any?>) {
        val d = dir ?: return
        val o = JsonObject(mapOf("utc" to JsonPrimitive(Instant.now().toString()), "kind" to JsonPrimitive(kind)) +
            data.associate { (k, v) -> k to JsonPrimitive(v?.toString()) })
        File(d, "session-${LocalDate.now()}.jsonl").appendText(Json.encodeToString(JsonObject.serializer(), o) + "\n")
    }

    fun files(context: Context): List<File> = File(context.applicationContext.filesDir, "logs").listFiles { f -> f.name.startsWith("session-") }.orEmpty().sortedBy { it.name }

    @Synchronized
    fun deleteAll(context: Context) { files(context).forEach { it.delete() } }
}
