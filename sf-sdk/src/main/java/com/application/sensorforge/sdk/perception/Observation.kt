package com.application.sensorforge.sdk.perception

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Point in upright, normalised image coordinates ([0,1] × [0,1]). */
data class Pt(val x: Float, val y: Float)

data class BBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val w get() = r - l
    val h get() = b - t
    val diag get() = sqrt(w * w + h * h)
    val center get() = Pt(cx, cy)

    fun iou(o: BBox): Float {
        val iw = max(0f, min(r, o.r) - max(l, o.l))
        val ih = max(0f, min(b, o.b) - max(t, o.t))
        val inter = iw * ih
        val union = w * h + o.w * o.h - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun shifted(dx: Float, dy: Float) = BBox(l + dx, t + dy, r + dx, b + dy)

    companion object {
        fun around(points: List<Pt>, grow: Float = 1f): BBox {
            val l = points.minOf { it.x }; val r = points.maxOf { it.x }
            val t = points.minOf { it.y }; val b = points.maxOf { it.y }
            val cx = (l + r) / 2; val cy = (t + b) / 2
            val hw = (r - l) / 2 * grow; val hh = (b - t) / 2 * grow
            return BBox(cx - hw, cy - hh, cx + hw, cy + hh)
        }
    }
}

/** Convex or simple quad; point-in-polygon by ray casting. */
data class Quad(val pts: List<Pt>) {
    fun contains(p: Pt): Boolean {
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun bounds() = BBox.around(pts)
}

/** One object detection. `code` is an instance code when the detector can read one (tagged props). */
data class Detection(val cls: String, val box: BBox, val confidence: Double, val code: String? = null)

/** Frame quality: mean luma (0–255) and Laplacian variance (sharpness). */
data class FrameQuality(val brightness: Double, val sharpness: Double) {
    fun usable(minBrightness: Double = 25.0, maxBrightness: Double = 250.0, minSharpness: Double = 8.0) =
        brightness in minBrightness..maxBrightness && sharpness >= minSharpness
}

/** Everything perception saw in one analysed frame, in upright normalised coordinates. */
data class FrameObservation(
    val tNanos: Long,
    val index: Long,
    val detections: List<Detection>,
    /** zone name → corner index (0..3) → marker centre. */
    val zoneCorners: Map<String, Map<Int, Pt>> = emptyMap(),
    /** Zones whose floor marker was read this frame (interior visible and unoccluded). */
    val floors: Set<String> = emptySet(),
    /** (entity class, state) read from state markers this frame. */
    val states: List<Pair<String, String>> = emptyList(),
    val quality: FrameQuality,
)
