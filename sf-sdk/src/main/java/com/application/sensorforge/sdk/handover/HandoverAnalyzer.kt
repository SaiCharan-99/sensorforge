package com.application.sensorforge.sdk.handover

import com.application.sensorforge.sdk.contracts.EntityRef
import com.application.sensorforge.sdk.contracts.ObservabilityWindow
import com.application.sensorforge.sdk.contracts.Primitive
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Track
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.Pt
import kotlin.math.max

/** An item tag (instance code) seen this frame. */
data class ItemObs(val code: String, val box: BBox)

/** One hand: its landmark points (upright normalised). */
data class HandObs(val points: List<Pt>)

/** What perception saw at time [t]. [usable] false = blurred or dark frame: nothing can be concluded from it. */
data class HandoverFrame(
    val t: Double,
    val usable: Boolean,
    val items: List<ItemObs> = emptyList(),
    val hands: List<HandObs> = emptyList(),
    val slipCodes: List<String> = emptyList(),
)

enum class ItemState(val text: String) {
    NOT_SEEN("not seen yet"), WITH_PARTNER("with partner"), IN_HAND("in hand"), IN_TRANSIT("in transit"),
    HANDED("handed over"), TAKEN_BACK("taken back"), OUT_OF_VIEW("out of view"),
}

data class HandoverLive(
    val items: Map<String, ItemState>,
    val slipRead: String?,
    val steps: Int?,
    val locationAccuracyM: Float?,
    val feedback: String?,
)

/**
 * Pure hand-over state machine (no Android). A hand-over = the item rests on the customer side with
 * no hand on it for [HandoverConfig.settleS], having been seen on the partner side first (side zones,
 * the M5-free fallback of model stack section 7). Never handed or taken back while the item stayed
 * in view reads ABSENT (FAIL); once it left the view it reads NOT_OBSERVABLE (UNVERIFIED), by the
 * engine's coverage rule.
 */
class HandoverAnalyzer(private val cfg: HandoverConfig) {
    private enum class Side { PARTNER, CUSTOMER, MID }

    private class Item(val code: String) {
        var lastSeen = -1.0
        var side: Side? = null
        var held = false
        var wasPartner = false
        var settleSince: Double? = null
        var partnerSince: Double? = null
        var handedAt: Double? = null
        var takenBack = false
        val events = mutableListOf<Pair<String, Double>>() // held_by_hand / released
        val windows = mutableListOf<DoubleArray>()
        var windowOpen = false
        var firstSeen: Double? = null
    }

    private val items = cfg.items.associateWith { Item(it) }
    private val slipWindows = mutableListOf<DoubleArray>()
    private var slipWindowOpen = false
    private var slipAt: Double? = null
    private var slipValue: String? = null
    private var lastT = 0.0
    private var stepSensor = false
    private var stepsAt: Double? = null
    private var steps: Int? = null
    private var locAt: Double? = null
    private var locAcc: Float? = null

    var live = HandoverLive(items.mapValues { ItemState.NOT_SEEN }, null, null, null, "Show each item to the camera on the partner side.")
        private set

    fun add(f: HandoverFrame): HandoverLive {
        lastT = max(lastT, f.t)
        if (!f.usable) {
            items.values.forEach { it.windowOpen = false }
            slipWindowOpen = false
            live = live.copy(feedback = "Image unclear: improve the light and hold still.")
            return live
        }
        // Slip: the camera is looking at the scene, so a slip that is absent is genuinely absent.
        if (slipWindowOpen) slipWindows.last()[1] = f.t else { slipWindows += doubleArrayOf(f.t, f.t); slipWindowOpen = true }
        f.slipCodes.firstOrNull()?.let { c -> if (slipAt == null) { slipAt = f.t; slipValue = c } }

        val seen = f.items.associateBy { it.code }
        for (item in items.values) {
            val o = seen[item.code]
            if (o == null) {
                if (item.windowOpen && f.t - item.lastSeen > cfg.lostS) item.windowOpen = false
                continue
            }
            if (item.firstSeen == null) item.firstSeen = f.t
            // A gap longer than lostS ends the old window; the new one starts at this frame.
            if (item.windowOpen && f.t - item.lastSeen <= cfg.lostS) item.windows.last()[1] = f.t
            else { item.windows += doubleArrayOf(f.t, f.t); item.windowOpen = true }
            item.lastSeen = f.t
            step(item, o.box, f.hands, f.t)
        }
        live = HandoverLive(items.mapValues { (_, i) -> stateOf(i, f.t) }, slipValue, steps, locAcc, feedbackFor(f.t))
        return live
    }

    private fun sideOf(b: BBox): Side {
        val left = b.cx < cfg.splitX - cfg.bandX
        val right = b.cx > cfg.splitX + cfg.bandX
        return when {
            !left && !right -> Side.MID
            left == cfg.partnerOnLeft -> Side.PARTNER
            else -> Side.CUSTOMER
        }
    }

    private fun heldBy(b: BBox, hands: List<HandObs>): Boolean {
        val l = b.cx - b.w * 0.75f; val r = b.cx + b.w * 0.75f
        val t = b.cy - b.h * 0.75f; val bo = b.cy + b.h * 0.75f
        return hands.any { h -> h.points.any { p -> p.x in l..r && p.y in t..bo } }
    }

    private fun step(it: Item, box: BBox, hands: List<HandObs>, t: Double) {
        val side = sideOf(box)
        val held = heldBy(box, hands)
        if (held && !it.held) it.events += "held_by_hand" to t
        if (!held && it.held) it.events += "released" to t
        it.held = held; it.side = side

        if (side == Side.PARTNER) {
            it.wasPartner = true
            it.settleSince = null
            if (it.handedAt != null) {
                // Back on the partner side after the hand-over: taken back once it rests there.
                if (it.partnerSince == null) it.partnerSince = t
                if (t - it.partnerSince!! >= cfg.settleS) { it.takenBack = true; it.handedAt = null }
            }
            return
        }
        it.partnerSince = null
        if (side == Side.CUSTOMER && !held && it.wasPartner) {
            if (it.settleSince == null) it.settleSince = t
            if (it.handedAt == null && t - it.settleSince!! >= cfg.settleS) { it.handedAt = it.settleSince; it.takenBack = false }
        } else {
            it.settleSince = null
        }
    }

    private fun stateOf(i: Item, t: Double): ItemState = when {
        i.firstSeen == null -> ItemState.NOT_SEEN
        t - i.lastSeen > cfg.lostS -> if (i.handedAt != null) ItemState.HANDED else ItemState.OUT_OF_VIEW
        i.handedAt != null -> ItemState.HANDED
        i.takenBack -> ItemState.TAKEN_BACK
        i.held -> ItemState.IN_HAND
        i.side == Side.PARTNER -> ItemState.WITH_PARTNER
        else -> ItemState.IN_TRANSIT
    }

    private fun feedbackFor(t: Double): String? {
        val st = items.mapValues { (_, i) -> stateOf(i, t) }
        st.entries.firstOrNull { it.value == ItemState.TAKEN_BACK }?.let { return "Item ${it.key} was taken back: hand it over again." }
        st.entries.firstOrNull { it.value == ItemState.OUT_OF_VIEW }?.let { return "Item ${it.key} left the view: keep it in the picture." }
        st.entries.firstOrNull { it.value == ItemState.NOT_SEEN }?.let { return "Show item ${it.key} to the camera on the partner side." }
        st.entries.firstOrNull { it.value == ItemState.WITH_PARTNER || it.value == ItemState.IN_HAND || it.value == ItemState.IN_TRANSIT }
            ?.let { return "Hand item ${it.key} to the customer side and let go." }
        if (slipValue == null) return "Show the signed slip to the camera."
        return null
    }

    /** Steps walked since the run started; [available] false = no sensor or no permission (never a pass). */
    fun onSteps(t: Double, walked: Int, available: Boolean = true) {
        lastT = max(lastT, t)
        stepSensor = available
        if (!available) return
        steps = walked
        if (stepsAt == null && walked >= cfg.minSteps) stepsAt = t
        live = live.copy(steps = walked)
    }

    /** A location fix; recorded with its accuracy, never sole evidence. */
    fun onLocation(t: Double, accuracyM: Float) {
        lastT = max(lastT, t)
        if (locAt == null) locAt = t
        locAcc = accuracyM
        live = live.copy(locationAccuracyM = accuracyM)
    }

    val handedCount get() = items.values.count { it.handedAt != null }
    fun stateOf(code: String, t: Double = lastT) = stateOf(items.getValue(code), t)

    /** Closed timeline for the engine (tech spec section 9). */
    fun timeline(clipId: String, durationS: Double = lastT, startUtcMs: Long? = null): Timeline {
        val end = max(durationS, lastT)
        val tracks = mutableListOf<Track>()
        val prims = mutableListOf<Primitive>()
        val obs = mutableListOf<ObservabilityWindow>()
        var n = 0
        fun id() = "p${n++}"
        for (i in items.values) {
            val tid = "item_${i.code}"
            val ref = EntityRef(tid, "item")
            i.firstSeen?.let { tracks += Track(tid, "item", tid, it, i.lastSeen) }
            i.events.forEach { (type, t) -> prims += Primitive(id(), type, t, entity = ref, state = i.code, confidence = 0.9) }
            i.handedAt?.let { prims += Primitive(id(), "transferred", it, entity = ref, zone = "customer_side", state = i.code, confidence = 0.9) }
            obs += ObservabilityWindow("zone:item_${i.code}", i.windows.map { listOf(it[0], it[1]) })
        }
        slipAt?.let { t ->
            tracks += Track("slip", "slip", "slip", t, t, code = slipValue)
            prims += Primitive(id(), "code_read", t, entity = EntityRef("slip", "slip"), code = slipValue, confidence = 0.99)
        }
        obs += ObservabilityWindow("slip", slipWindows.map { listOf(it[0], it[1]) })
        if (stepSensor || locAt != null) {
            tracks += Track("walker", "person", "walker", 0.0, end)
            if (stepSensor) obs += ObservabilityWindow("walker", listOf(listOf(0.0, end)))
            stepsAt?.let { prims += Primitive(id(), "steps_walked", it, entity = EntityRef("walker", "person"), confidence = 0.9) }
            locAt?.let { prims += Primitive(id(), "at_location", it, entity = EntityRef("walker", "person"), confidence = if ((locAcc ?: 1e9f) <= 50f) 0.9 else 0.3) }
        }
        return Timeline(clipId, HandoverSpecs.STACK, end, tracks, prims, obs, startUtcMs)
    }
}
