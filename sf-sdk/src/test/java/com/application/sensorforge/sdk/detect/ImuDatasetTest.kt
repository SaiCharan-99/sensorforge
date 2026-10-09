package com.application.sensorforge.sdk.detect

import com.application.sensorforge.sdk.train.HeadTrainer
import com.application.sensorforge.sdk.train.TrainConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImuDatasetTest {
    private fun fixtures(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/imu") }.first { it.isDirectory }

    private fun replays() = fixtures().listFiles { f -> f.name.startsWith("impact_") && f.extension == "csv" }!!
        .sorted().map { ImuReplay.parseCsv(it.name, it.readText()) }

    @Test fun replayFixturesAreLabelledSimulation() {
        val r = replays()
        assertEquals(12, r.size)
        r.forEach { assertTrue(it.name, it.simulation) }
    }

    @Test fun csvRoundTrip() {
        val r = replays().first()
        val back = ImuReplay.parseCsv("x", ImuReplay.toCsv(listOf("SIMULATION test"), r.samples))
        assertEquals(r.samples.size, back.samples.size)
        assertTrue(back.simulation)
    }

    @Test fun eachImpactReplayYieldsAnchoredWindows() {
        for (r in replays()) {
            val ex = ImuDataset.examples(r.samples, "impact_like", r.name, false, 3.0, 0.5, 100, 2.0)
            assertTrue(r.name, ex.size in 1..2)
            val peakG = ex.first().x[WindowFeatures.NAMES.indexOf("acc_g_max")]
            assertTrue("${r.name} peak $peakG", peakG > 5f)
            val stillStd = ex.first().x[WindowFeatures.NAMES.indexOf("post_peak_acc_std")]
            assertTrue("${r.name} still $stillStd", stillStd < 0.08f)
        }
    }

    @Test fun eventWindowsPutPeakEarly() {
        val ends = ImuDataset.eventWindowEnds(10_000_000_000L, 3.0, 0.5, 2.0)
        assertEquals(listOf(12_250_000_000L, 12_750_000_000L), ends)
    }

    /** Replays + a synthetic "normal" class: the trained head separates impact from rest. */
    @Test fun headSeparatesImpactReplaysFromStill() {
        val r = replays()
        val impact = r.flatMapIndexed { i, rep ->
            ImuDataset.examples(rep.samples, "impact_like", rep.name, i >= 9, 3.0, 0.5, 100, 2.0)
        }
        // last 4 s of each replay: phone lying still -> normal
        val still = r.flatMapIndexed { i, rep ->
            val tail = rep.samples.filter { it.tNanos >= rep.samples.maxOf { s -> s.tNanos } - 4_000_000_000L }
            ImuDataset.examples(tail, "normal", "still-${rep.name}", i >= 9, 3.0, 0.5, 100, 2.0)
        }
        val (head, rep) = HeadTrainer.train(
            "imu_risk", listOf("normal", "impact_like"), impact + still, TrainConfig(minPerClass = 5),
            datasetVersion = "test", trainedAt = "t", deviceFingerprint = "jvm",
        )
        assertEquals(1.0, rep.heldOutMetrics.getValue("impact_like").recall, 0.0)
        assertEquals(1.0, rep.heldOutMetrics.getValue("normal").recall, 0.0)
        assertTrue(head.version == 1)
    }
}
