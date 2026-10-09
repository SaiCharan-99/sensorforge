package com.application.sensorforge.crashguard

import android.app.Application
import android.os.Build
import android.util.Log
import com.application.sensorforge.sdk.contracts.DetectSpec
import com.application.sensorforge.sdk.detect.ImuDataset
import com.application.sensorforge.sdk.detect.ImuReplay
import com.application.sensorforge.sdk.detect.ImuSample
import com.application.sensorforge.sdk.detect.RecordingStore
import com.application.sensorforge.sdk.train.Example
import com.application.sensorforge.sdk.train.Head
import com.application.sensorforge.sdk.train.HeadStore
import com.application.sensorforge.sdk.train.HeadTrainer
import com.application.sensorforge.sdk.train.TrainConfig
import com.application.sensorforge.sdk.train.TrainingReport
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Per-class example counts, split by dev / held-out and by provenance. */
data class ClassCount(val label: String, val dev: Int, val heldOut: Int, val sessions: Int, val simulation: Boolean)

/**
 * H3 data + training on device: live recordings (normal/shake/drop), impact_like from bundled
 * SIMULATION replays (tech spec §20), immutable head versions with explicit promotion.
 */
class ImuModel(private val app: Application, val spec: DetectSpec) {
    val recordings = RecordingStore(File(app.filesDir, "crashguard/recordings"))
    val heads = HeadStore(File(app.filesDir, "crashguard/heads"), spec.head.id)

    /** impact_like replays; the last 3 are the held-out sessions (fixed, never used for fitting). */
    val replays: List<ImuReplay> by lazy {
        app.assets.list("imu").orEmpty().filter { it.startsWith("impact_") && it.endsWith(".csv") }.sorted()
            .map { n -> ImuReplay.parseCsv(n, app.assets.open("imu/$n").bufferedReader().readText()) }
    }

    fun examples(): List<Example> {
        val w = spec.window
        val still = spec.corroboration.postEventStillnessS
        val live = recordings.list().flatMap {
            ImuDataset.examples(it.samples, it.label, it.session, it.heldOut, w.lengthS, w.hopS, w.imuHz, still)
        }
        val sim = replays.flatMapIndexed { i, r ->
            ImuDataset.examples(r.samples, "impact_like", r.name, i >= replays.size - 3, w.lengthS, w.hopS, w.imuHz, still)
        }
        return live + sim
    }

    fun counts(ex: List<Example>): List<ClassCount> = ImuDataset.CLASSES.map { c ->
        val e = ex.filter { it.label == c }
        ClassCount(c, e.count { !it.heldOut }, e.count { it.heldOut }, e.map { it.session }.distinct().size, c == "impact_like")
    }

    fun record(label: String, heldOut: Boolean, samples: List<ImuSample>) {
        val session = "$label-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        recordings.save(label, heldOut, session, samples)
    }

    /** Trains a new immutable version (warm start from the active head when compatible). CPU, Kotlin. */
    fun train(): Pair<Head, TrainingReport> {
        val ex = examples()
        val parent = heads.active()?.let { heads.load(it) }
        val t0 = System.nanoTime()
        val (head, report) = HeadTrainer.train(
            headId = spec.head.id,
            classes = ImuDataset.CLASSES,
            data = ex,
            config = TrainConfig(),
            parent = parent,
            version = heads.nextVersion(),
            datasetVersion = datasetVersion(ex),
            trainedAt = Instant.now().toString(),
            deviceFingerprint = deviceFingerprint(),
            clockMillis = { (System.nanoTime() - t0) / 1_000_000 },
        )
        heads.save(head, report)
        Log.i(
            TAG, "trained ${head.id} v${head.version} in ${report.trainMillis} ms on ${report.trainingBackend}; " +
                "train=${report.trainSamples} val=${report.valSamples} heldOut=${report.heldOutSamples} " +
                "parent=${report.parentSha256.take(12)} trained=${report.trainedWeightsSha256.take(12)} " +
                "changed=${report.changedParameterCount}/${report.parameterCount}",
        )
        return head to report
    }

    private fun datasetVersion(ex: List<Example>): String {
        val md = MessageDigest.getInstance("SHA-256")
        ex.sortedWith(compareBy({ it.session }, { it.label })).forEach { e ->
            md.update("${e.session}|${e.label}|${e.heldOut}|".toByteArray())
            e.x.forEach { md.update(java.lang.Float.floatToIntBits(it).toString().toByteArray()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        const val TAG = "SF.H3"

        fun deviceFingerprint(): String {
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"
            return "${Build.MANUFACTURER} ${Build.MODEL} soc=$soc sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY}"
        }
    }
}
