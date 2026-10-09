package com.application.sensorforge.sdk.train

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HeadTrainerTest {
    private val classes = listOf("normal", "shake", "drop", "impact_like")

    /** Four separable Gaussian clusters in 8 dims; 3 sessions per class + 1 held-out session. */
    private fun data(seed: Long = 1): List<Example> {
        val r = Random(seed)
        return classes.flatMapIndexed { ci, c ->
            (0 until 4).flatMap { s ->
                (0 until 10).map {
                    val x = FloatArray(8) { d -> ((if (d % 4 == ci) 3.0 else 0.0) + r.nextGaussian() * 0.5).toFloat() }
                    Example(x, c, "$c-s$s", heldOut = s == 3)
                }
            }
        }
    }

    private fun train(parent: Head? = null, hidden: Int = 16, d: List<Example> = data()) =
        HeadTrainer.train("imu_risk", classes, d, TrainConfig(hidden = hidden), parent, datasetVersion = "ds1", trainedAt = "t", deviceFingerprint = "test")

    @Test fun learnsSeparableClassesAndReportsHeldOut() {
        val (head, rep) = train()
        assertEquals(1, head.version)
        rep.heldOutMetrics.values.forEach { assertTrue("recall ${it.recall}", it.recall >= 0.9) }
        assertTrue(rep.lossCurve.last() < rep.lossCurve.first())
        assertEquals(40, rep.heldOutSamples)
        assertEquals(rep.trainedWeightsSha256, head.sha256())
    }

    @Test fun trainingChangesWeightsHash() {
        val (h1, r1) = train()
        assertNotEquals(r1.parentSha256, r1.trainedWeightsSha256)
        val (h2, r2) = train(parent = h1, d = data(seed = 2))
        assertEquals(2, h2.version)
        assertEquals(h1.sha256(), r2.parentSha256)
        assertNotEquals(h1.sha256(), h2.sha256())
        assertTrue(r2.changedParameterCount > 0)
    }

    @Test fun deterministicForSameSeed() = assertEquals(train().first.sha256(), train().first.sha256())

    @Test fun logisticVariantTrains() {
        val (_, rep) = train(hidden = 0)
        rep.heldOutMetrics.values.forEach { assertTrue(it.recall >= 0.9) }
    }

    @Test fun heldOutNeverInTrainOrVal() {
        val (_, rep) = train()
        assertEquals(120, rep.trainSamples + rep.valSamples)
    }

    @Test fun splitKeepsSessionsWhole() {
        val dev = data().filter { !it.heldOut }
        val (tr, va) = HeadTrainer.splitBySession(dev, classes, TrainConfig())
        assertTrue(va.isNotEmpty())
        assertTrue((tr.map { it.session }.toSet() intersect va.map { it.session }.toSet()).isEmpty())
        classes.forEach { c -> assertTrue(tr.any { it.label == c }) }
    }

    @Test(expected = TrainingError::class) fun refusesTooFewExamples() {
        val d = data()
        train(d = d.filterNot { it.label == "drop" } + d.filter { it.label == "drop" && !it.heldOut }.take(4))
    }
}
