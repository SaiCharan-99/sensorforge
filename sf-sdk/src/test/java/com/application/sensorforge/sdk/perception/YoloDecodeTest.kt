package com.application.sensorforge.sdk.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloDecodeTest {
    private val classes = listOf("box", "cable")

    /** Builds a (channels x n) channels-first output with the given anchors (cx,cy,w,h,class,score). */
    private fun out(n: Int, vararg a: FloatArray): FloatArray {
        val ch = 4 + classes.size
        val o = FloatArray(ch * n)
        a.forEachIndexed { i, v ->
            o[i] = v[0]; o[n + i] = v[1]; o[2 * n + i] = v[2]; o[3 * n + i] = v[3]
            o[(4 + v[4].toInt()) * n + i] = v[5]
        }
        return o
    }

    @Test fun decodesLetterboxedBoxBackToImageCoordinates() {
        // 640x320 image letterboxed to 320: scale .5, padX 0, padY 80. Box centred at image (320,160) size 160x80.
        val o = out(4, floatArrayOf(160f, 160f, 80f, 40f, 0f, 0.9f))
        val d = YoloDecode.decode(o, 6, 4, classes, 0f, 80f, 0.5f, 640, 320)
        assertEquals(1, d.size)
        assertEquals("box", d[0].cls)
        assertEquals(0.375f, d[0].box.l, 1e-4f); assertEquals(0.625f, d[0].box.r, 1e-4f)
        assertEquals(0.375f, d[0].box.t, 1e-4f); assertEquals(0.625f, d[0].box.b, 1e-4f)
    }

    @Test fun nmsKeepsHigherScoreOfOverlappingSameClassAndBothClasses() {
        val o = out(4,
            floatArrayOf(100f, 100f, 50f, 50f, 0f, 0.9f), floatArrayOf(102f, 100f, 50f, 50f, 0f, 0.7f),
            floatArrayOf(100f, 100f, 50f, 50f, 1f, 0.8f))
        val d = YoloDecode.decode(o, 6, 4, classes, 0f, 0f, 1f, 320, 320)
        assertEquals(listOf("box", "cable"), d.map { it.cls })
    }

    @Test fun lowScoresAndEmptyBoxesAreDropped() {
        val o = out(2, floatArrayOf(100f, 100f, 50f, 50f, 0f, 0.2f), floatArrayOf(100f, 100f, 0f, 0f, 1f, 0.9f))
        assertTrue(YoloDecode.decode(o, 6, 2, classes, 0f, 0f, 1f, 320, 320).isEmpty())
    }
}
