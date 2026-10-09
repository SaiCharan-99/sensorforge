package com.application.sensorforge.sdk.perception

/**
 * Multi-object tracker (model stack C3): greedy per-class association on a constant-velocity
 * predicted box (alpha-beta filter, a fixed-gain Kalman), ByteTrack-style.
 *
 * Identity (tech spec §9) is never restored from appearance: a new track gets the identity of an
 * ended track only when both carry the same instance code and the old track is no longer active.
 * Otherwise a new track of a class seen before gets a fresh identity plus an `unresolvedWith` link.
 */
class Tracker(
    private val maxMissedS: Double = 1.0,
    /** Max centre distance, in units of the track's box diagonal, for a match. */
    private val gate: Float = 3.0f,
    private val alpha: Float = 0.6f,
    private val beta: Float = 0.2f,
) {
    class TrackState(
        val trackId: String,
        val cls: String,
        val identityId: String,
        var code: String?,
        var box: BBox,
        val tStart: Double,
        var tLast: Double,
        var confidence: Double,
        /** Earlier track this one may equal (identity unproven). */
        val unresolvedWith: String?,
    ) {
        var vx = 0f
        var vy = 0f
        var active = true
        fun predicted(t: Double): BBox { val dt = (t - tLast).toFloat(); return box.shifted(vx * dt, vy * dt) }
    }

    data class Update(
        val started: List<TrackState>,
        val ended: List<TrackState>,
        /** Tracks seen this frame. */
        val seen: List<TrackState>,
        /** Tracks whose instance code was read for the first time this frame. */
        val codeRead: List<TrackState>,
    )

    private var nextTrack = 1
    private var nextIdentity = 1
    private val tracks = mutableListOf<TrackState>()

    val all: List<TrackState> get() = tracks
    val active: List<TrackState> get() = tracks.filter { it.active }

    fun update(t: Double, detections: List<Detection>): Update {
        val started = mutableListOf<TrackState>()
        val seen = mutableListOf<TrackState>()
        val codeRead = mutableListOf<TrackState>()
        val unmatched = detections.toMutableList()

        // Candidate pairs, best first: same instance code beats geometry.
        data class Pair(val tr: TrackState, val d: Detection, val score: Float)
        val pairs = mutableListOf<Pair>()
        for (tr in tracks) if (tr.active) for (d in detections) {
            if (d.cls != tr.cls) continue
            if (tr.code != null && d.code != null && tr.code != d.code) continue
            val p = tr.predicted(t)
            val dx = d.box.cx - p.cx; val dy = d.box.cy - p.cy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy) / maxOf(p.diag, 1e-3f)
            val sameCode = tr.code != null && tr.code == d.code
            // A matching instance code is identity proof; geometry only gates code-less matches.
            if (dist > gate && !sameCode) continue
            val codeBonus = if (sameCode) 10f else 0f
            pairs += Pair(tr, d, codeBonus + 1f / (1f + dist) + p.iou(d.box))
        }
        pairs.sortWith(compareByDescending<Pair> { it.score }.thenBy { it.tr.trackId })
        val usedT = mutableSetOf<TrackState>()
        val usedD = mutableSetOf<Detection>()
        for (p in pairs) {
            if (p.tr in usedT || p.d in usedD) continue
            usedT += p.tr; usedD += p.d; unmatched.remove(p.d)
            correct(p.tr, p.d, t)
            if (p.tr.code == null && p.d.code != null) { p.tr.code = p.d.code; codeRead += p.tr }
            seen += p.tr
        }

        for (d in unmatched.sortedWith(compareBy({ it.cls }, { it.box.cx }, { it.box.cy }))) {
            val activeIds = tracks.filter { it.active }.map { it.identityId }.toSet()
            val byCode = d.code?.let { c ->
                tracks.lastOrNull { !it.active && it.cls == d.cls && it.code == c && it.identityId !in activeIds }
            }
            // The new track may be an earlier one that ended, or an active one that jumped out of the gate.
            val previous = tracks.lastOrNull { it.cls == d.cls && (!it.active || it !in seen) }
            val identity = byCode?.identityId ?: "id${nextIdentity++}"
            val unresolved = if (byCode == null) previous?.trackId else null
            val tr = TrackState("t${nextTrack++}", d.cls, identity, d.code, d.box, t, t, d.confidence, unresolved)
            tracks += tr
            started += tr; seen += tr
            if (d.code != null) codeRead += tr
        }

        val ended = tracks.filter { it.active && it !in seen && t - it.tLast > maxMissedS }
        ended.forEach { it.active = false }
        return Update(started, ended, seen, codeRead)
    }

    /** Ends all active tracks (clip stop). */
    fun close(): List<TrackState> = tracks.filter { it.active }.onEach { it.active = false }

    private fun correct(tr: TrackState, d: Detection, t: Double) {
        val dt = (t - tr.tLast).toFloat()
        val p = tr.predicted(t)
        val rx = d.box.cx - p.cx; val ry = d.box.cy - p.cy
        if (dt > 0f) { tr.vx += beta * rx / dt; tr.vy += beta * ry / dt }
        val cx = p.cx + alpha * rx; val cy = p.cy + alpha * ry
        tr.box = BBox(cx - d.box.w / 2, cy - d.box.h / 2, cx + d.box.w / 2, cy + d.box.h / 2)
        tr.tLast = t
        tr.confidence = d.confidence
    }
}
