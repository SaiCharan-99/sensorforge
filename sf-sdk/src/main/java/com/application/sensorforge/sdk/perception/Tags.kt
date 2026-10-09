package com.application.sensorforge.sdk.perception

/**
 * Tagged-prop perception (model stack M1 fallback "tagged props (QR/colour), declared openly").
 * Every prop carries a printed QR tag; the claim for this stack is "controlled props".
 *
 * Payloads (`|`-separated, prefix `SF1`):
 * - `SF1|OBJ|<cls>|<instance code or empty>`  an object of class `cls`
 * - `SF1|ZONE|<zone>|<corner 0..3>`            zone corner marker (zone quad = corners 0→3)
 * - `SF1|FLOOR|<zone>`                         marker on the zone floor; readable ⇒ interior visible
 * - `SF1|STATE|<cls>|<state>`                  state marker (e.g. inside of a lid ⇒ `box` `open`)
 */
sealed interface Tag {
    data class Obj(val cls: String, val instance: String?) : Tag
    data class ZoneCorner(val zone: String, val corner: Int) : Tag
    data class Floor(val zone: String) : Tag
    data class State(val cls: String, val state: String) : Tag
}

/** A decoded QR: payload text and its corner/finder points in upright normalised coordinates. */
data class RawCode(val text: String, val points: List<Pt>)

object Tags {
    const val PREFIX = "SF1"
    /** Perception stack id written into timelines from this detector. */
    const val STACK = "tags-qr/zxing-3.5.3/v1"
    /** A decoded QR is near-certain; ZXing reports no score. */
    const val DECODE_CONFIDENCE = 0.99

    fun obj(cls: String, instance: String? = null) = "$PREFIX|OBJ|$cls|${instance.orEmpty()}"
    fun zoneCorner(zone: String, corner: Int) = "$PREFIX|ZONE|$zone|$corner"
    fun floor(zone: String) = "$PREFIX|FLOOR|$zone"
    fun state(cls: String, state: String) = "$PREFIX|STATE|$cls|$state"

    fun parse(text: String): Tag? {
        val p = text.trim().split('|')
        if (p.size < 3 || p[0] != PREFIX || p[2].isBlank()) return null
        return when (p[1]) {
            "OBJ" -> Tag.Obj(p[2], p.getOrNull(3)?.takeIf { it.isNotBlank() })
            "ZONE" -> p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..3 }?.let { Tag.ZoneCorner(p[2], it) }
            "FLOOR" -> Tag.Floor(p[2])
            "STATE" -> p.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { Tag.State(p[2], it) }
            else -> null
        }
    }

    /**
     * Turns decoded codes into a frame observation. `classify` may relabel an object detection
     * (identity head, H1); it gets the tag class and returns (class, confidence).
     */
    fun observe(
        tNanos: Long,
        index: Long,
        codes: List<RawCode>,
        quality: FrameQuality,
        classify: (Detection) -> Detection = { it },
    ): FrameObservation {
        val dets = mutableListOf<Detection>()
        val corners = mutableMapOf<String, MutableMap<Int, Pt>>()
        val floors = mutableSetOf<String>()
        val states = mutableListOf<Pair<String, String>>()
        for (c in codes) {
            if (c.points.isEmpty()) continue
            when (val tag = parse(c.text)) {
                is Tag.Obj -> dets += classify(Detection(tag.cls, BBox.around(c.points, grow = 1.4f), DECODE_CONFIDENCE, tag.instance))
                is Tag.ZoneCorner -> corners.getOrPut(tag.zone) { mutableMapOf() }[tag.corner] = BBox.around(c.points).center
                is Tag.Floor -> floors += tag.zone
                is Tag.State -> states += tag.cls to tag.state
                null -> Unit
            }
        }
        return FrameObservation(tNanos, index, dets, corners, floors, states, quality)
    }
}
