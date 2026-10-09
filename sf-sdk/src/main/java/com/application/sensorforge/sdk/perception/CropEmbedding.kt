package com.application.sensorforge.sdk.perception

/**
 * Handcrafted crop embedding for the identity head (H1) while no M2 image encoder is
 * integrated: normalised 8-bin histograms of Y, U and V over a crop (24 dims).
 * Declared as "handcrafted embedding", never as an encoder.
 */
object CropEmbedding {
    const val BINS = 8
    const val DIM = 3 * BINS
    const val NAME = "yuv-hist-24/v1"

    /**
     * `sample(x, y)` returns packed (Y shl 16) or (U shl 8) or V for pixel (x, y) in image space;
     * the crop is `[x0, x1) × [y0, y1)`, sampled on a grid of `step` pixels.
     */
    fun compute(x0: Int, y0: Int, x1: Int, y1: Int, step: Int = 2, sample: (Int, Int) -> Int): FloatArray? {
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        val h = FloatArray(DIM)
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = sample(x, y)
                h[(p ushr 16 and 0xFF) * BINS / 256]++
                h[BINS + (p ushr 8 and 0xFF) * BINS / 256]++
                h[2 * BINS + (p and 0xFF) * BINS / 256]++
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return null
        for (i in h.indices) h[i] /= n
        return h
    }
}
