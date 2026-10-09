package com.application.sensorforge.sdk.engine

import com.application.sensorforge.sdk.contracts.Timeline

/**
 * Union-find over identity ids linked by `identity_unresolved` (tech spec §15).
 * Identity is never merged on appearance alone: an unresolved link means "may be the same".
 */
internal class IdentityGraph(timeline: Timeline) {
    private val identityOfTrack = timeline.tracks.associate { it.trackId to it.identityId }
    private val classOfIdentity = timeline.tracks.associate { it.identityId to it.cls }
    private val parent = mutableMapOf<String, String>()

    init {
        timeline.tracks.forEach { parent[it.identityId] = it.identityId }
        timeline.primitives
            .filter { it.type == "identity_unresolved" }
            .forEach { p ->
                val a = identityOfTrack[p.entity?.trackId]
                val b = identityOfTrack[p.otherTrackId]
                if (a != null && b != null) union(a, b)
            }
    }

    fun identityOf(trackId: String): String? = identityOfTrack[trackId]

    fun sameComponent(a: String, b: String) = find(a) == find(b)

    /** (min possible distinct instances, max possible distinct instances) for a class. */
    fun instanceBounds(cls: String): Pair<Int, Int> {
        val ids = classOfIdentity.filterValues { it == cls }.keys
        return ids.map { find(it) }.toSet().size to ids.size
    }

    private fun find(x: String): String {
        val p = parent[x] ?: return x
        if (p == x) return x
        return find(p).also { parent[x] = it }
    }

    private fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) {
            // Deterministic root choice.
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }
    }
}
