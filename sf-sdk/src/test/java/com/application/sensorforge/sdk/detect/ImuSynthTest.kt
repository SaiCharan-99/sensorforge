package com.application.sensorforge.sdk.detect

import com.application.sensorforge.sdk.train.HeadTrainer
import com.application.sensorforge.sdk.train.TrainConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Synthetic normal/shake/drop (SIMULATION) + impact_like replays train a real head whose hash changes. */
class ImuSynthTest {
    private val replays = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/imu") }.first { it.isDirectory }
        .listFiles { f -> f.name.startsWith("impact_") && f.extension == "csv" }!!.sorted().map { ImuReplay.parseCsv(it.name, it.readText()) }

    private fun data(): List<com.application.sensorforge.sdk.train.Example> {
        val synth = listOf("normal" to 6, "shake" to 6, "drop" to 6).flatMap { (label, n) ->
            (0 until n).flatMap { i ->
                val held = i == n - 1
                ImuDataset.examples(ImuSynth.generate(label, i.toLong()), label, "sim-$label-$i", held, 3.0, 0.5, 100, 2.0)
            }
        }
        val impacts = replays.flatMapIndexed { i, r -> ImuDataset.examples(r.samples, "impact_like", r.name, i >= replays.size - 3, 3.0, 0.5, 100, 2.0) }
        return synth + impacts
    }

    @Test fun everyLabelProducesEnoughWindows() {
        for (l in listOf("normal", "shake", "drop")) {
            val ex = (0 until 5).flatMap { ImuDataset.examples(ImuSynth.generate(l, it.toLong()), l, "s$it", false, 3.0, 0.5, 100, 2.0) }
            assertTrue("$l has ${ex.size} windows", ex.size >= 5)
        }
    }

    @Test fun trainsAndChangesHash() {
        val (head, rep) = HeadTrainer.train("imu_risk", ImuDataset.CLASSES, data(), TrainConfig(), null, datasetVersion = "sim", trainedAt = "t", deviceFingerprint = "jvm")
        assertNotEquals(rep.parentSha256, rep.trainedWeightsSha256)
        assertEquals(head.sha256(), rep.trainedWeightsSha256)
        // SIMULATION data: only check the pipeline works, not accuracy claims.
        assertTrue(rep.lossCurve.last() < rep.lossCurve.first())
        val shake = rep.heldOutMetrics.getValue("shake"); val impact = rep.heldOutMetrics.getValue("impact_like")
        assertTrue("shake recall ${shake.recall}", shake.recall >= 0.5)
        assertTrue("impact recall ${impact.recall}", impact.recall >= 0.5)
    }

    @Test fun simulationHeaderRoundTrips() {
        val dir = kotlin.io.path.createTempDirectory("rec").toFile()
        val store = RecordingStore(dir)
        store.saveSimulated("shake", true, "sim-shake-0", ImuSynth.generate("shake", 0))
        store.save("normal", false, "live-normal-0", ImuSynth.generate("normal", 1))
        val all = store.list().associateBy { it.session }
        assertTrue(all.getValue("sim-shake-0").simulation)
        assertTrue(!all.getValue("live-normal-0").simulation)
        assertTrue(all.getValue("sim-shake-0").heldOut)
    }
}
