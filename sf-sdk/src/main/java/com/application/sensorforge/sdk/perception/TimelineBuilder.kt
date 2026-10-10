package com.application.sensorforge.sdk.perception

import com.application.sensorforge.sdk.contracts.EntityRef
import com.application.sensorforge.sdk.contracts.ObservabilityWindow
import com.application.sensorforge.sdk.contracts.Primitive
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Track

/**
 * Primitive extractor (tech spec §22 `objects`, `zones`, `codes`, `quality`): turns frame
 * observations into the observation timeline, incrementally.
 *
 * - Containers: a zone `<cls>_interior` belongs to container class `<cls>`; its quad comes from
 *   the four corner markers (each valid for [cornerTtlS] after it was last read). The container
 *   itself is tracked as a detection covering the quad.
 * - Observability (perception owns it, §11): a zone, and its container class, is observable while
 *   the quad is valid and the interior is visible (floor marker read, or an object read inside it).
 *   Other classes are observable while read. Unusable frames (dark, blurred) and frame gaps
 *   open `camera_unstable` and close every window.
 * - `targetsOf(cls)` maps a class to the observe targets (spec entity ids) it feeds.
 */
class TimelineBuilder(
    private val clipId: String,
    private val perceptionStack: String = Tags.STACK,
    private val targetsOf: (String) -> List<String> = { listOf(it) },
    private val cornerTtlS: Double = 1.0,
    private val maxGapS: Double = 0.5,
    /** Consecutive frames needed to flip inside/outside or a state. */
    private val hysteresis: Int = 3,
    /** Seconds the interior must stay visible and empty for `region_empty`. */
    private val emptyHoldS: Double = 1.0,
    private val closedStates: Set<String> = setOf("closed", "sealed"),
    /** Zone names (`box_interior`) that may be taken from a detected container box when no QR corner tags are visible. */
    private val detectorZones: Set<String> = emptySet(),
) {
    private val detZoneSeen = mutableMapOf<String, Pair<Quad, Double>>()
    /** Zones in use this frame that come from the detector, not from printed corner tags (less strict; shown in the UI). */
    var detectorBased: Set<String> = emptySet(); private set
    private val tracker = Tracker()
    private val primitives = mutableListOf<Primitive>()
    private val windows = mutableMapOf<String, MutableList<DoubleArray>>()
    private var t0: Long? = null
    private var lastT: Double? = null
    private var lastObservable: Set<String> = emptySet()
    private var unstableStart: Double? = null
    private val cornerSeen = mutableMapOf<String, MutableMap<Int, Pair<Pt, Double>>>()
    private val inside = mutableMapOf<Pair<String, String>, Boolean>()
    private val streak = mutableMapOf<Pair<String, String>, Pair<Boolean, Int>>()
    private val stateKnown = mutableMapOf<String, String>()
    private val stateStreak = mutableMapOf<String, Pair<String, Int>>()
    private val emptySince = mutableMapOf<String, Double>()
    private val emptyEmitted = mutableSetOf<String>()
    private val lastSeenAt = mutableMapOf<String, Double>()

    /** Last frame's zone quads (for overlays). */
    var zones: Map<String, Quad> = emptyMap(); private set
    val activeTracks: List<Tracker.TrackState> get() = tracker.active
    val nowS: Double get() = lastT ?: 0.0

    val primitiveCount: Int get() = primitives.size
    fun primitivesFrom(i: Int): List<Primitive> = primitives.subList(i, primitives.size).toList()

    /** Seconds since `target` was last observable (null if never). */
    fun lastObservableAt(target: String): Double? = lastSeenAt[target]

    fun add(obs: FrameObservation) {
        val base = t0 ?: obs.tNanos.also { t0 = it }
        val t = (obs.tNanos - base) / 1e9
        val prev = lastT
        if (prev != null && t <= prev) return // monotonic clock only

        // Close windows from the previous frame.
        if (prev != null) {
            if (t - prev > maxGapS) openUnstable(prev) else lastObservable.forEach { addWindow(it, prev, t) }
        }
        lastT = t

        if (!obs.quality.usable()) {
            if (unstableStart == null) unstableStart = t
            tracker.update(t, emptyList()).ended.forEach { emit(t, "disappears", it) }
            lastObservable = emptySet()
            return
        }
        unstableStart?.let { emitUnstable(it, t) }

        val validZones = updateZones(obs, t)
        zones = validZones
        val dets = obs.detections + validZones.filterKeys { it !in detectorBased }.map { (z, q) -> Detection(containerOf(z) ?: z, q.bounds(), Tags.DECODE_CONFIDENCE) }
        val up = tracker.update(t, dets)
        up.started.forEach { tr ->
            emit(t, "appears", tr, frames = listOf(obs.index))
            tr.unresolvedWith?.let { emit(t, "identity_unresolved", tr, other = it, frames = listOf(obs.index)) }
        }
        up.codeRead.forEach { emit(t, "code_read", it, code = it.code, frames = listOf(obs.index)) }
        up.ended.forEach { emit(t, "disappears", it) }

        val observable = mutableSetOf<String>()
        for ((zone, quad) in validZones) {
            val container = containerOf(zone)
            val occupants = up.seen.filter { it.cls != container && quad.contains(it.box.center) }
            for (tr in up.seen) if (tr.cls != container) updateContainment(tr, zone, container, quad, t, obs.index)

            val interiorVisible = zone in obs.floors || zone in detectorBased || occupants.isNotEmpty()
            if (interiorVisible) observable += "zone:$zone"
            // The container is observed while its interior is visible, or while it is visibly
            // closed (nothing can leave a closed box unseen). An open lid alone proves nothing.
            val closed = obs.states.any { it.first == container && it.second in closedStates }
            if (container != null && (interiorVisible || closed)) observable += targetsOf(container)
            // region_empty: interior visible through its floor marker and nothing inside for emptyHoldS.
            val anyInside = tracker.active.any { it.cls != container && quad.contains(it.box.center) && t - it.tLast <= 0.5 }
            if ((zone in obs.floors || zone in detectorBased) && !anyInside) {
                val since = emptySince.getOrPut(zone) { t }
                if (t - since >= emptyHoldS && zone !in emptyEmitted) {
                    emptyEmitted += zone
                    primitives += Primitive(nextId(), "region_empty", t, zone = zone, confidence = 0.95, evidenceFrames = listOf(obs.index))
                }
            } else {
                emptySince.remove(zone)
                if (anyInside) emptyEmitted -= zone
            }
        }
        for (tr in up.seen) if (validZones.keys.none { containerOf(it) == tr.cls }) observable += targetsOf(tr.cls)

        updateStates(obs, t)
        observable.forEach { lastSeenAt[it] = t }
        lastObservable = observable
    }

    /** Ends the clip: closes open tracks and windows. */
    fun timeline(close: Boolean = false): Timeline {
        val end = lastT ?: 0.0
        if (close) {
            tracker.close().forEach { emit(end, "disappears", it) }
            unstableStart?.let { emitUnstable(it, end); unstableStart = null }
        }
        val tracks = tracker.all.map { Track(it.trackId, it.cls, it.identityId, it.tStart, it.tLast, it.code) }
        val obs = windows.toSortedMap().map { (target, iv) -> ObservabilityWindow(target, merge(iv)) }
        val prims = primitives.toMutableList()
        if (!close) unstableStart?.let { prims += Primitive("u_open", "camera_unstable", it, tEnd = end, confidence = 1.0) }
        return Timeline(clipId, perceptionStack, end, tracks, prims, obs)
    }

    private fun containerOf(zone: String): String? = zone.removeSuffix("_interior").takeIf { it != zone }

    private fun updateZones(obs: FrameObservation, t: Double): Map<String, Quad> {
        for ((zone, cs) in obs.zoneCorners) for ((i, p) in cs) cornerSeen.getOrPut(zone) { mutableMapOf() }[i] = p to t
        val byTags = cornerSeen.mapNotNull { (zone, cs) ->
            val fresh = (0..3).map { i -> cs[i]?.takeIf { t - it.second <= cornerTtlS }?.first }
            if (fresh.any { it == null }) null else zone to Quad(fresh.filterNotNull())
        }.toMap()
        val byDet = mutableMapOf<String, Quad>()
        for (zone in detectorZones) {
            if (zone in byTags) continue
            val cls = containerOf(zone) ?: continue
            obs.detections.filter { it.learned && it.cls == cls }.maxByOrNull { it.confidence }?.let { d ->
                val b = d.box
                detZoneSeen[zone] = Quad(listOf(Pt(b.l, b.t), Pt(b.r, b.t), Pt(b.r, b.b), Pt(b.l, b.b))) to t
            }
            detZoneSeen[zone]?.takeIf { t - it.second <= cornerTtlS }?.let { byDet[zone] = it.first }
        }
        detectorBased = byDet.keys
        return byTags + byDet
    }

    private fun updateContainment(tr: Tracker.TrackState, zone: String, container: String?, quad: Quad, t: Double, frame: Long) {
        val key = tr.trackId to zone
        val now = quad.contains(tr.box.center)
        val (v, n) = streak[key] ?: (now to 0)
        val count = if (v == now) n + 1 else 1
        streak[key] = now to count
        if (count < hysteresis || inside[key] == now) return
        val was = inside[key]
        inside[key] = now
        if (now) {
            emit(t, "enters_zone", tr, zone = zone, frames = listOf(frame))
            if (container != null) emit(t, "contained_in", tr, container = container, frames = listOf(frame))
        } else if (was == true) {
            emit(t, "leaves_zone", tr, zone = zone, frames = listOf(frame))
            if (container != null) emit(t, "exits_container", tr, container = container, frames = listOf(frame))
        }
    }

    private fun updateStates(obs: FrameObservation, t: Double) {
        for ((cls, state) in obs.states.distinct()) {
            val (s, n) = stateStreak[cls] ?: (state to 0)
            val count = if (s == state) n + 1 else 1
            stateStreak[cls] = state to count
            val known = stateKnown[cls]
            if (count < 2 || known == state) continue
            val tr = tracker.active.firstOrNull { it.cls == cls } ?: continue
            stateKnown[cls] = state
            // The first state seen is a state, not a change: a box already open never "opened".
            emit(t, if (known == null) "state_is" else "state_change", tr, state = state, frames = listOf(obs.index))
        }
    }

    private fun openUnstable(prev: Double) { if (unstableStart == null) unstableStart = prev }

    private fun emitUnstable(start: Double, end: Double) {
        primitives += Primitive(nextId(), "camera_unstable", start, tEnd = end, confidence = 1.0)
        unstableStart = null
    }

    private fun addWindow(target: String, a: Double, b: Double) { windows.getOrPut(target) { mutableListOf() } += doubleArrayOf(a, b) }

    private fun merge(iv: List<DoubleArray>): List<List<Double>> {
        val out = mutableListOf<DoubleArray>()
        for (w in iv.sortedBy { it[0] }) {
            val last = out.lastOrNull()
            if (last != null && w[0] <= last[1] + 1e-9) last[1] = maxOf(last[1], w[1]) else out += w.copyOf()
        }
        return out.map { listOf(it[0], it[1]) }
    }

    private fun emit(
        t: Double, type: String, tr: Tracker.TrackState,
        container: String? = null, zone: String? = null, state: String? = null, code: String? = null,
        other: String? = null, frames: List<Long> = emptyList(),
    ) {
        primitives += Primitive(
            nextId(), type, t, entity = EntityRef(tr.trackId, tr.cls), container = container, zone = zone,
            state = state, code = code, otherTrackId = other, confidence = tr.confidence, evidenceFrames = frames,
        )
    }

    private fun nextId() = "p%04d".format(primitives.size + 1)
}
