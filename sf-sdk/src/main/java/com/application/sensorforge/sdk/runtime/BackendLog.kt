package com.application.sensorforge.sdk.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** One logged model run (rule 4): runtime, provider, backend, latency. */
@Serializable
data class BackendRecord(
    val model: String,
    val runtime: String,
    val provider: String,
    val backend: String,
    val input: String,
    /** load | cold | warm | train | infer */
    val kind: String,
    val ms: Double,
    val utc: String = Instant.now().toString(),
    /** Airplane mode was on when this was measured. */
    val offline: Boolean = false,
    val note: String = "",
)

/** Per-model summary for the backend log screen (S17) and the measurement log (§26). */
data class BackendSummary(
    val model: String,
    val runtime: String,
    val provider: String,
    val backend: String,
    val input: String,
    val loadMs: Double?,
    val coldMs: Double?,
    val warmP50: Double?,
    val warmP95: Double?,
    val runs: Int,
    val offlineRuns: Int,
    val lastUtc: String,
)

/**
 * Backend log: every model load/run is recorded here and appended to
 * `files/logs/backend.jsonl`. Nothing claims a backend unless a record exists.
 */
object BackendLog {
    private const val TAG = "SF.BACKEND"
    private const val KEEP = 4000
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private var appContext: Context? = null
    private val _records = MutableStateFlow<List<BackendRecord>>(emptyList())
    val records: StateFlow<List<BackendRecord>> = _records.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        appContext = context.applicationContext
        val f = File(context.applicationContext.filesDir, "logs/backend.jsonl").apply { parentFile?.mkdirs() }
        file = f
        if (f.exists()) {
            _records.value = f.readLines().takeLast(KEEP).mapNotNull { runCatching { json.decodeFromString(BackendRecord.serializer(), it) }.getOrNull() }
        }
    }

    @Synchronized
    fun record(r: BackendRecord) {
        val rec = appContext?.let { r.copy(offline = r.offline || Offline.airplaneOn(it)) } ?: r
        _records.value = (_records.value + rec).takeLast(KEEP)
        file?.appendText(json.encodeToString(BackendRecord.serializer(), rec) + "\n")
        Log.i(TAG, "${rec.model} ${rec.kind} ${"%.1f".format(rec.ms)} ms runtime=${rec.runtime} provider=${rec.provider} backend=${rec.backend} input=${rec.input}${if (rec.offline) " OFFLINE" else ""} ${rec.note}")
    }

    fun record(model: String, runtime: String, provider: String, backend: String, input: String, kind: String, ms: Double, note: String = "") =
        record(BackendRecord(model, runtime, provider, backend, input, kind, ms, note = note))

    fun summaries(): List<BackendSummary> = _records.value.groupBy { it.model }.map { (m, rs) ->
        val last = rs.last()
        val warm = rs.filter { it.kind == "warm" || it.kind == "infer" }.map { it.ms }.sorted()
        fun pct(p: Int) = warm.takeIf { it.isNotEmpty() }?.let { it[(it.size * p / 100).coerceAtMost(it.size - 1)] }
        BackendSummary(
            m, last.runtime, last.provider, last.backend, last.input,
            rs.lastOrNull { it.kind == "load" }?.ms, rs.lastOrNull { it.kind == "cold" }?.ms, pct(50), pct(95),
            rs.size, rs.count { it.offline }, last.utc,
        )
    }.sortedBy { it.model }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
        file?.delete()
    }
}

/** Airplane-mode state; "Offline" is shown only after a run measured with it on. */
object Offline {
    fun airplaneOn(context: Context): Boolean =
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
}

/** Rolling warm-latency recorder: logs the first run as cold, then one warm record per [every] runs. */
class LatencyMeter(
    private val model: String,
    private val runtime: String,
    private val provider: String,
    private val backend: String,
    private val input: String,
    private val every: Int = 15,
) {
    private var n = 0
    fun add(ms: Double) {
        n++
        if (n == 1) BackendLog.record(model, runtime, provider, backend, input, "cold", ms)
        else if (n % every == 0) BackendLog.record(model, runtime, provider, backend, input, "warm", ms)
    }
}
