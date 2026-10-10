package com.application.sensorforge.sdk.perception

/**
 * Decoder for an Ultralytics YOLOE/YOLO11 head exported to ONNX: output (1, 4+C+extra, N),
 * channels-first, boxes as cx,cy,w,h in input pixels, class scores already sigmoid'd.
 * Pure Kotlin: input was letterboxed to a square [size] image with [padX]/[padY] pixels of padding.
 */
object YoloDecode {
    /** Read access to the flat output without copying it (the prompt-free head is ~16M floats). */
    fun interface Src { fun at(i: Int): Float }

    fun decode(
        out: FloatArray, channels: Int, n: Int, classes: List<String>,
        padX: Float, padY: Float, scale: Float, imgW: Int, imgH: Int,
        scoreThreshold: Float = 0.35f, iou: Float = 0.5f, maxResults: Int = 10, agnostic: Boolean = false,
    ): List<Detection> {
        require(out.size >= channels * n)
        return decode(Src { out[it] }, channels, n, classes, padX, padY, scale, imgW, imgH, scoreThreshold, iou, maxResults, agnostic)
    }

    fun decode(
        out: Src, channels: Int, n: Int, classes: List<String>,
        padX: Float, padY: Float, scale: Float, imgW: Int, imgH: Int,
        scoreThreshold: Float = 0.35f, iou: Float = 0.5f, maxResults: Int = 10, agnostic: Boolean = false,
    ): List<Detection> {
        require(channels >= 4 + classes.size)
        val cand = mutableListOf<Detection>()
        for (i in 0 until n) {
            var best = -1; var bs = scoreThreshold
            for (c in classes.indices) { val s = out.at((4 + c) * n + i); if (s >= bs) { bs = s; best = c } }
            if (best < 0) continue
            val cx = out.at(i); val cy = out.at(n + i); val w = out.at(2 * n + i); val h = out.at(3 * n + i)
            val l = ((cx - w / 2 - padX) / scale / imgW).coerceIn(0f, 1f)
            val t = ((cy - h / 2 - padY) / scale / imgH).coerceIn(0f, 1f)
            val r = ((cx + w / 2 - padX) / scale / imgW).coerceIn(0f, 1f)
            val b = ((cy + h / 2 - padY) / scale / imgH).coerceIn(0f, 1f)
            if (r > l && b > t) cand += Detection(classes[best], BBox(l, t, r, b), bs.toDouble())
        }
        val keep = mutableListOf<Detection>()
        for (d in cand.sortedByDescending { it.confidence }) {
            if (keep.none { (agnostic || it.cls == d.cls) && overlap(it.box, d.box) > iou }) keep += d
            if (keep.size >= maxResults) break
        }
        return keep
    }

    private fun overlap(a: BBox, b: BBox): Float {
        val iw = minOf(a.r, b.r) - maxOf(a.l, b.l); val ih = minOf(a.b, b.b) - maxOf(a.t, b.t)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        return inter / ((a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter)
    }
}
