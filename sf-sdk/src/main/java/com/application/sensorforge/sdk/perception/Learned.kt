package com.application.sensorforge.sdk.perception

/**
 * Merges learned-detector boxes (M1: EfficientDet / YOLOE) into tag detections (pure, tested).
 * Only classes the procedure names are kept; a learned box never duplicates a tag of the same class.
 */
object Learned {
    /** Detector class names → spec class ids (`cell phone` → `cell_phone`). */
    fun normalise(name: String) = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    fun merge(tags: List<Detection>, learned: List<Detection>, specClasses: Set<String>, minScore: Double = 0.3, overlap: Float = 0.2f): List<Detection> {
        val kept = learned.map { it.copy(cls = normalise(it.cls), learned = true) }
            .filter { it.cls in specClasses && it.confidence >= minScore }
            .filter { l -> tags.none { t -> t.cls == l.cls && t.box.iou(l.box) > overlap } }
        // Non-maximum suppression among learned boxes of the same class.
        val out = mutableListOf<Detection>()
        for (d in kept.sortedByDescending { it.confidence }) if (out.none { it.cls == d.cls && it.box.iou(d.box) > 0.5f }) out += d
        return tags + out
    }
}
