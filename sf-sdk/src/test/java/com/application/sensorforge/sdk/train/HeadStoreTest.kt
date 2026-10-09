package com.application.sensorforge.sdk.train

import com.application.sensorforge.sdk.detect.ImuChannel
import com.application.sensorforge.sdk.detect.ImuSample
import com.application.sensorforge.sdk.detect.RecordingStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

class HeadStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun data(): List<Example> {
        val r = Random(3)
        return listOf("a", "b").flatMapIndexed { ci, c ->
            (0 until 3).flatMap { s -> (0 until 8).map { Example(FloatArray(4) { d -> (if (d == ci) 2f else 0f) + r.nextGaussian().toFloat() * 0.3f }, c, "$c$s") } }
        }
    }

    private fun train(store: HeadStore, parent: Head?) = HeadTrainer.train(
        "h", listOf("a", "b"), data(), TrainConfig(hidden = 4), parent, store.nextVersion(), "ds", "t", "jvm",
    )

    @Test fun versionsAreImmutableAndPromotionIsExplicit() {
        val store = HeadStore(tmp.root, "h")
        val (h1, r1) = train(store, null)
        store.save(h1, r1)
        assertNull(store.active())
        store.promote(1)
        val (h2, r2) = train(store, h1)
        assertEquals(2, h2.version)
        store.save(h2, r2)
        assertEquals(1, store.active())
        store.promote(2); store.promote(1) // rollback
        assertEquals(1, store.active())
        assertEquals(h1.sha256(), store.load(1).sha256())
        assertEquals(r2.trainedWeightsSha256, store.report(2).trainedWeightsSha256)
        assertTrue(runCatching { store.save(h2, r2) }.isFailure)
    }

    @Test fun loadedHeadPredictsIdentically() {
        val store = HeadStore(tmp.root, "h")
        val (h, r) = train(store, null)
        store.save(h, r)
        val x = data().first().x
        assertArrayEquals(h.predict(x), store.load(1).predict(x), 0f)
    }

    @Test fun recordingRoundTrip() {
        val rs = RecordingStore(tmp.newFolder("rec"))
        val samples = (0 until 10).map { ImuSample(it * 10_000_000L, ImuChannel.ACCEL, 0f, 0f, 9.8f) }
        rs.save("shake", true, "shake-001", samples)
        val back = rs.list().single()
        assertEquals("shake", back.label)
        assertTrue(back.heldOut)
        assertEquals(false, back.simulation)
        assertEquals(samples, back.samples)
    }
}
