package com.application.sensorforge.sdk.detect

import java.io.File

/** One stored recording. `session` (the file stem) is the train/val split unit. */
data class Recording(val session: String, val label: String, val heldOut: Boolean, val simulation: Boolean, val samples: List<ImuSample>)

/**
 * Labelled IMU recordings in app-private storage (`<root>/<session>.csv`). The header carries
 * label, split and LIVE/SIMULATION provenance; the body is the [ImuReplay] CSV format.
 */
class RecordingStore(private val root: File) {
    fun save(label: String, heldOut: Boolean, session: String, samples: List<ImuSample>): Recording {
        root.mkdirs()
        val header = listOf("LIVE recording on this device", "label=$label", "held_out=$heldOut")
        File(root, "$session.csv").writeText(ImuReplay.toCsv(header, samples))
        return Recording(session, label, heldOut, false, samples)
    }

    fun list(): List<Recording> = root.listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }.map { f ->
        val text = f.readText()
        val meta = text.lineSequence().takeWhile { it.startsWith("#") }
            .mapNotNull { it.removePrefix("# ").split('=', limit = 2).takeIf { p -> p.size == 2 } }
            .associate { it[0] to it[1] }
        val r = ImuReplay.parseCsv(f.name, text)
        Recording(f.nameWithoutExtension, meta.getValue("label"), meta["held_out"] == "true", r.simulation, r.samples)
    }

    fun delete(session: String) = File(root, "$session.csv").delete()

    fun deleteAll() { root.listFiles()?.forEach { it.delete() } }
}
