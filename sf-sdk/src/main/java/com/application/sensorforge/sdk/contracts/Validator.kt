package com.application.sensorforge.sdk.contracts

/**
 * Contract validation (tech spec §8). Every `validate*` returns all errors found;
 * `require*` throws [ContractException] so nothing runs on an invalid contract.
 */
object Validator {

    fun requireValid(spec: ProcedureSpec, availableHeads: Set<String>? = null) =
        throwIfAny(validate(spec, availableHeads))

    fun requireValid(spec: DetectSpec, availableHeads: Set<String>? = null) =
        throwIfAny(validate(spec, availableHeads))

    fun requireValid(timeline: Timeline) = throwIfAny(validate(timeline))

    fun requireValid(manifest: PackManifest) = throwIfAny(validate(manifest))

    /** @param availableHeads head ids shipped in the pack; null skips the check (spec-only validation). */
    fun validate(spec: ProcedureSpec, availableHeads: Set<String>? = null): List<ContractError> {
        val errors = mutableListOf<ContractError>()
        fun err(path: String, msg: String) = errors.add(ContractError(path, msg))

        checkSchemaVersion(spec.schemaVersion, errors)
        if (spec.mode != "verify") err("$.mode", "procedure spec must have mode 'verify', got '${spec.mode}'")

        val entityIds = spec.entities.map { it.id }
        duplicates(entityIds).forEach { err("$.entities", "duplicate entity id '$it'") }
        val stepIds = spec.steps.map { it.id }
        duplicates(stepIds).forEach { err("$.steps", "duplicate step id '$it'") }
        val entities = entityIds.toSet()
        val steps = stepIds.toSet()

        spec.entities.forEachIndexed { i, e ->
            val p = "$.entities[$i]"
            if (e.identityPolicy !in Vocabulary.IDENTITY_POLICIES) err("$p.identity_policy", "unknown identity policy '${e.identityPolicy}'")
            if (e.identityPolicy == "code_required" && e.expectedCode == null) err("$p.expected_code", "code_required entity needs expected_code")
            if (e.head != null && availableHeads != null && e.head !in availableHeads) err("$p.head", "head '${e.head}' is not in the pack")
        }

        spec.parameters.forEach { (name, def) ->
            if (def.type !in Vocabulary.PARAMETER_TYPES) err("$.parameters.$name.type", "unsupported parameter type '${def.type}'")
        }

        spec.steps.forEachIndexed { i, s ->
            val p = "$.steps[$i]"
            when {
                s.primitive !in Vocabulary.ALL_PRIMITIVES -> err("$p.primitive", "unknown primitive '${s.primitive}'")
                s.primitive !in Vocabulary.VERIFY_PRIMITIVES -> err("$p.primitive", "primitive '${s.primitive}' is detect-only")
            }
            if (s.primitive != "region_empty" && s.entity == null && s.forEach == null) err("$p.entity", "step needs an entity")
            if (s.primitive == "region_empty" && s.zone == null) err("$p.zone", "region_empty needs a zone")
            if (s.forEach == null) {
                if (s.entity != null && s.entity !in entities) err("$p.entity", "dangling entity '${s.entity}'")
                if (s.container != null && s.container !in entities) err("$p.container", "dangling container '${s.container}'")
                if (!s.observeTarget.startsWith("zone:") && s.observeTarget !in entities) {
                    err("$p.observe_target", "dangling observe_target '${s.observeTarget}'")
                }
            } else if (s.forEach !in spec.parameters) {
                err("$p.for_each", "for_each references undeclared parameter '${s.forEach}'")
            } else if (spec.parameters.getValue(s.forEach).type != "list<code>") {
                err("$p.for_each", "for_each needs a 'list<code>' parameter")
            }
            checkConfidence(s.minConfidence, "$p.min_confidence", errors)
        }

        val stepRefsOk = spec.steps.none { it.forEach != null }
        spec.constraints.forEachIndexed { i, c ->
            val p = "$.constraints[$i]"
            val refs = when (c) {
                is Before -> listOf(c.a, c.b)
                is AnyOrder -> c.steps
                is SameIdentity -> listOf(c.a, c.b)
                is AfterParam -> {
                    val def = spec.parameters[c.param]
                    if (def == null) err("$p.param", "AFTER_PARAM references undeclared parameter '${c.param}'")
                    else if (def.type != "time") err("$p.param", "AFTER_PARAM needs a 'time' parameter, '${c.param}' is '${def.type}'")
                    listOf(c.step)
                }
            }
            if (stepRefsOk) refs.filter { it !in steps }.forEach { err(p, "dangling step reference '$it'") }
            if (c.source != null && c.source !in setOf("induced", "developer")) err("$p.source", "unknown source '${c.source}'")
        }
        if (stepRefsOk) beforeCycle(spec.constraints.filterIsInstance<Before>())?.let {
            err("$.constraints", "cyclic BEFORE: ${it.joinToString(" -> ")}")
        }

        spec.prohibited.forEachIndexed { i, pr ->
            val p = "$.prohibited[$i]"
            when (pr) {
                is MaxInstances -> {
                    if (pr.entity !in entities) err("$p.entity", "dangling entity '${pr.entity}'")
                    if (pr.max < 0) err("$p.max", "max must be >= 0")
                }
                is ForbiddenClass -> checkConfidence(pr.minConfidence, "$p.min_confidence", errors)
            }
        }

        checkConfidence(spec.policy.minObservableCoverage, "$.policy.min_observable_coverage", errors)
        if (spec.policy.coachingTimeoutS <= 0) err("$.policy.coaching_timeout_s", "must be > 0")
        return errors
    }

    fun validate(spec: DetectSpec, availableHeads: Set<String>? = null): List<ContractError> {
        val errors = mutableListOf<ContractError>()
        fun err(path: String, msg: String) = errors.add(ContractError(path, msg))
        checkSchemaVersion(spec.schemaVersion, errors)
        if (spec.mode != "detect") err("$.mode", "detect spec must have mode 'detect', got '${spec.mode}'")
        if (spec.window.lengthS <= 0) err("$.window.length_s", "must be > 0")
        if (spec.window.hopS <= 0) err("$.window.hop_s", "must be > 0")
        if (spec.window.imuHz <= 0) err("$.window.imu_hz", "must be > 0")
        checkConfidence(spec.head.minProb, "$.head.min_prob", errors)
        if (availableHeads != null && spec.head.id !in availableHeads) err("$.head.id", "head '${spec.head.id}' is not in the pack")
        if (spec.incident.autoEscalate) err("$.incident.auto_escalate", "auto_escalate=true is not allowed")
        if (spec.incident.transport != "mock") err("$.incident.transport", "transport must be 'mock' in the finale build")
        if (spec.incident.countdownS <= 0) err("$.incident.countdown_s", "must be > 0")
        return errors
    }

    fun validate(timeline: Timeline): List<ContractError> {
        val errors = mutableListOf<ContractError>()
        fun err(path: String, msg: String) = errors.add(ContractError(path, msg))
        if (timeline.durationS < 0) err("$.duration_s", "must be >= 0")
        val trackIds = timeline.tracks.map { it.trackId }
        duplicates(trackIds).forEach { err("$.tracks", "duplicate track id '$it'") }
        duplicates(timeline.primitives.map { it.id }).forEach { err("$.primitives", "duplicate primitive id '$it'") }
        val tracks = trackIds.toSet()
        timeline.primitives.forEachIndexed { i, pr ->
            val p = "$.primitives[$i]"
            if (pr.type !in Vocabulary.ALL_PRIMITIVES) err("$p.type", "unknown primitive '${pr.type}'")
            checkConfidence(pr.confidence, "$p.confidence", errors)
            if (pr.entity != null && pr.entity.trackId !in tracks) err("$p.entity.track_id", "dangling track '${pr.entity.trackId}'")
            if (pr.otherTrackId != null && pr.otherTrackId !in tracks) err("$p.other_track_id", "dangling track '${pr.otherTrackId}'")
            if (pr.type == "identity_unresolved" && (pr.entity == null || pr.otherTrackId == null)) {
                err(p, "identity_unresolved needs entity and other_track_id")
            }
            if (pr.tEnd != null && pr.tEnd < pr.t) err("$p.t_end", "t_end before t")
        }
        timeline.observability.forEachIndexed { i, w ->
            w.intervals.forEachIndexed { j, iv ->
                if (iv.size != 2 || iv[1] < iv[0]) err("$.observability[$i].intervals[$j]", "interval must be [t0, t1] with t1 >= t0")
            }
        }
        return errors
    }

    fun validate(manifest: PackManifest): List<ContractError> {
        val errors = mutableListOf<ContractError>()
        fun err(path: String, msg: String) = errors.add(ContractError(path, msg))
        checkSchemaVersion(manifest.schemaVersion, errors)
        if (manifest.mode !in Vocabulary.MODES) err("$.mode", "unknown mode '${manifest.mode}'")
        manifest.perceptionProfile.filter { it !in Vocabulary.PERCEPTION_PROFILES }
            .forEach { err("$.perception_profile", "unknown perception profile '$it'") }
        manifest.sensors.filter { it !in Vocabulary.SENSORS }
            .forEach { err("$.sensors", "sensor '$it' is not permitted") }
        if (manifest.mode == "detect") {
            manifest.sensors.filter { it in Vocabulary.DETECT_FORBIDDEN_SENSORS }
                .forEach { err("$.sensors", "sensor '$it' is not permitted in detect mode") }
        }
        return errors
    }

    private fun checkSchemaVersion(v: String, errors: MutableList<ContractError>) {
        if (v != Vocabulary.SCHEMA_VERSION) errors.add(ContractError("$.schema_version", "unsupported schema version '$v'"))
    }

    private fun checkConfidence(c: Double, path: String, errors: MutableList<ContractError>) {
        if (c.isNaN() || c < 0.0 || c > 1.0) errors.add(ContractError(path, "confidence $c outside [0,1]"))
    }

    private fun duplicates(ids: List<String>) = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys

    /** Returns one cycle as a step path, or null. */
    private fun beforeCycle(edges: List<Before>): List<String>? {
        val adj = edges.groupBy({ it.a }, { it.b })
        val state = mutableMapOf<String, Int>() // 1 = on stack, 2 = done
        val stack = ArrayDeque<String>()
        fun dfs(n: String): List<String>? {
            state[n] = 1
            stack.addLast(n)
            for (m in adj[n].orEmpty().sorted()) {
                when (state[m]) {
                    1 -> return stack.toList().dropWhile { it != m } + m
                    null -> dfs(m)?.let { return it }
                }
            }
            stack.removeLast()
            state[n] = 2
            return null
        }
        for (n in adj.keys.sorted()) if (state[n] == null) dfs(n)?.let { return it }
        return null
    }

    private fun throwIfAny(errors: List<ContractError>) {
        if (errors.isNotEmpty()) throw ContractException(errors)
    }
}
