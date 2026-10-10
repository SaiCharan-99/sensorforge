package com.application.sensorforge.sdk.perception

import org.junit.Test
import org.junit.Assert.assertEquals

class LearnedTest {
    private val tag = Detection("bottle", BBox(0.1f, 0.1f, 0.3f, 0.3f), 0.99, "X")
    private fun d(cls: String, l: Float, score: Double) = Detection(cls, BBox(l, 0.1f, l + 0.2f, 0.3f), score)

    @Test fun keepsOnlySpecClassesAboveScore() {
        val m = Learned.merge(emptyList(), listOf(d("bottle", 0.5f, 0.8), d("person", 0.1f, 0.9), d("cup", 0.7f, 0.2)), setOf("bottle", "cup"))
        assertEquals(listOf("bottle"), m.map { it.cls })
    }

    @Test fun tagWinsOverlappingSameClass() {
        val m = Learned.merge(listOf(tag), listOf(d("bottle", 0.12f, 0.9)), setOf("bottle"))
        assertEquals(1, m.size)
        assertEquals("X", m.single().code)
    }

    @Test fun sameClassElsewhereIsKeptAndDuplicatesSuppressed() {
        val m = Learned.merge(listOf(tag), listOf(d("bottle", 0.6f, 0.9), d("bottle", 0.62f, 0.7)), setOf("bottle"))
        assertEquals(2, m.size)
    }

    @Test fun normalisesDetectorNames() {
        assertEquals("cell_phone", Learned.normalise("cell phone"))
    }
}
