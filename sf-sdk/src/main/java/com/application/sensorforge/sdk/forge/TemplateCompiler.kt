package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.contracts.AfterParam
import com.application.sensorforge.sdk.contracts.AnyOrder
import com.application.sensorforge.sdk.contracts.Before
import com.application.sensorforge.sdk.contracts.ConstraintDef
import com.application.sensorforge.sdk.contracts.EntityDef
import com.application.sensorforge.sdk.contracts.ForbiddenClass
import com.application.sensorforge.sdk.contracts.MaxInstances
import com.application.sensorforge.sdk.contracts.ParameterDef
import com.application.sensorforge.sdk.contracts.PolicyDef
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.ProhibitedDef
import com.application.sensorforge.sdk.contracts.StepDef
import com.application.sensorforge.sdk.contracts.Vocabulary

/**
 * P0 authoring (tech spec §16.2): a small set of sentence patterns mapped to steps and
 * constraints. Deterministic and offline. Output is always an editable draft, never trusted
 * blindly; clauses it can't read are reported, not guessed.
 */
object TemplateCompiler {
    data class ClauseResult(val text: String, val stepIds: List<String>, val note: String?)
    data class Draft(val spec: ProcedureSpec, val clauses: List<ClauseResult>, val warnings: List<String>)

    private val STATES = setOf("intact", "broken", "open", "closed", "sealed", "unsealed", "on", "off")
    private val PRONOUNS = setOf("it", "them", "this", "that")
    private val DETERMINERS = setOf("the", "a", "an", "my", "our", "its", "their", "all")

    fun compile(procedureId: String, sentence: String, version: Int = 1): Draft {
        val entities = linkedMapOf<String, EntityDef>()
        val steps = mutableListOf<StepDef>()
        val prohibited = mutableListOf<ProhibitedDef>()
        val params = linkedMapOf<String, ParameterDef>()
        val constraints = mutableListOf<ConstraintDef>()
        val results = mutableListOf<ClauseResult>()
        val warnings = mutableListOf<String>()

        var lastEntity: EntityDef? = null
        fun entity0(phrase: String, code: String? = null): EntityDef {
            val words = phrase.trim().lowercase().split(Regex("[\\s_]+")).filter { it.isNotBlank() && it !in DETERMINERS && it !in STATES }
            val cls = words.joinToString("_").ifBlank { slug(phrase) }
            val old = entities[cls]
            if (old != null && (code == null || old.expectedCode == code)) return old
            val e = EntityDef(cls, cls, if (code != null) "code_required" else "track_continuity", expectedCode = code)
            entities[cls] = e
            return e
        }

        fun entity(phrase: String, code: String? = null): EntityDef {
            if (phrase.trim().lowercase() in PRONOUNS && lastEntity != null) return lastEntity!!
            return entity0(phrase, code).also { lastEntity = it }
        }

        fun stepId(base: String): String {
            var id = base; var n = 2
            while (steps.any { it.id == id }) id = "${base}_${n++}"
            return id
        }

        val clauses = splitClauses(sentence)
        for (raw in clauses) {
            var clause = raw.trim().trimEnd('.', '!').trim()
            if (clause.isEmpty()) continue
            val lower = clause.lowercase()

            // "... only after T" suffix, or a clause that is just "after T".
            var afterParam: String? = null
            Regex("^(.*?)[,\\s]*(?:but\\s+)?(?:only\\s+)?after\\s+(?:the\\s+)?(.+)$").matchEntire(lower)?.let { m ->
                val head = m.groupValues[1].trim()
                val t = m.groupValues[2].trim()
                val name = if (t.startsWith("start")) "start_time" else slug(t) + if (t.endsWith("time")) "" else "_time"
                params[name] = ParameterDef("time", "host_app")
                afterParam = name
                clause = head
            }
            val c = clause.lowercase()
            if (c.isEmpty() && afterParam != null) {
                val last = steps.lastOrNull()
                if (last != null) { constraints += AfterParam(last.id, afterParam!!, "developer"); results += ClauseResult(raw, emptyList(), "AFTER_PARAM on ${last.id}") }
                else warnings += "'$raw': nothing before it to apply the time to."
                continue
            }

            val added = mutableListOf<StepDef>()
            var note: String? = null
            val remove = Regex("^(?:remove|take out|take|pull out)\\s+(.+?)\\s+(?:from|out of)\\s+(?:the\\s+)?(.+)$").matchEntire(c)
            val open = Regex("^open\\s+(.+)$").matchEntire(c)
            val close = Regex("^close\\s+(.+)$").matchEntire(c)
            val read = Regex("^read\\s+(.+?)\\s+(?:label|code|barcode|qr)(?:\\s+(\\S+))?$").matchEntire(c)
            val show = Regex("^(?:show|display|present|keep)\\s+(.+)$").matchEntire(c)
            val atMost = Regex("^(?:at most one|only one|no more than one)\\s+(.+)$").matchEntire(c)
            val never = Regex("^(?:no|never show|reject|forbid)\\s+(.+)$").matchEntire(c)
            when {
                remove != null -> {
                    val container = entity(remove.groupValues[2])
                    for (item in remove.groupValues[1].split(Regex("\\s*(?:,|\\band\\b)\\s*")).filter { it.isNotBlank() }) {
                        val e = entity(item)
                        added += StepDef(stepId("${e.id}_out"), "exits_container", e.id, container = container.id, observeTarget = container.id)
                    }
                }
                open != null -> entity(open.groupValues[1]).let { added += StepDef(stepId("${it.id}_opened"), "state_change", it.id, state = "open", observeTarget = it.id) }
                close != null -> entity(close.groupValues[1]).let { added += StepDef(stepId("${it.id}_closed"), "state_change", it.id, state = "closed", observeTarget = it.id) }
                read != null -> entity(read.groupValues[1], read.groupValues[2].takeIf { it.isNotBlank() }?.uppercase()).let {
                    added += StepDef(stepId("${it.id}_label"), "code_read", it.id, observeTarget = it.id)
                }
                show != null -> {
                    val words = show.groupValues[1].split(Regex("\\s+"))
                    val state = words.lastOrNull()?.takeIf { it in STATES && words.size > 1 }
                    val adj = words.firstOrNull { it in STATES }
                    val e = entity(show.groupValues[1])
                    val st = (state ?: adj)?.let { if (it == "sealed") "intact" else it }
                    if (st != null) added += StepDef(stepId("${e.id}_${st}"), "state_is", e.id, state = st, observeTarget = e.id)
                    else added += StepDef(stepId("${e.id}_shown"), "appears", e.id, observeTarget = e.id)
                }
                atMost != null -> { prohibited += MaxInstances(entity(atMost.groupValues[1]).id, 1); note = "at most one" }
                never != null -> { prohibited += ForbiddenClass(entity(never.groupValues[1]).cls, 0.8); note = "forbidden" }
                Regex("^(?:hold|keep).*(?:for|\\d)").containsMatchIn(c) -> {
                    warnings += "'$raw': timed holds need a body-pose pack (RepRight); not added to a camera-object draft."
                    results += ClauseResult(raw, emptyList(), "not supported here"); continue
                }
                else -> { warnings += "Could not read: '$raw'. Add this step by hand."; results += ClauseResult(raw, emptyList(), "unread"); continue }
            }
            steps += added
            if (afterParam != null && added.isNotEmpty()) added.forEach { constraints += AfterParam(it.id, afterParam!!, "developer") }
            results += ClauseResult(raw, added.map { it.id }, note)
        }

        // Sequence: every step of a clause comes before every step of the next; a multi-step clause is ANY_ORDER.
        val groups = results.map { it.stepIds }.filter { it.isNotEmpty() }
        groups.filter { it.size > 1 }.forEach { constraints += AnyOrder(it, "developer") }
        groups.zipWithNext().forEach { (g, n) -> g.forEach { a -> n.forEach { b -> constraints += Before(a, b, "developer") } } }

        if (steps.isEmpty()) warnings += "No steps could be read. Try: 'Show the sealed envelope, open it only after start, remove the papers from the envelope.'"
        val spec = ProcedureSpec(
            Vocabulary.SCHEMA_VERSION, procedureId, version, "verify", params, entities.values.toList(), steps,
            constraints.distinct(), prohibited, PolicyDef(0.9, false, 3.0),
        )
        return Draft(spec, results, warnings)
    }

    /** Perception profiles a spec needs. */
    fun profileOf(spec: ProcedureSpec): List<String> = buildList {
        add("objects"); add("quality")
        if (spec.steps.any { it.zone != null }) add("zones")
        if (spec.steps.any { it.primitive == "code_read" }) add("codes")
        if (spec.steps.any { it.state != null }) add("state")
        if (spec.entities.any { it.head == "identity" }) add("identity")
        if (spec.steps.any { it.primitive in setOf("joint_angle_in_range", "pose_held", "rep_completed") }) { remove("objects"); add("body") }
        if (spec.steps.any { it.primitive in setOf("held_by_hand", "released", "transferred") }) add("hands")
        if (spec.steps.any { it.primitive in setOf("steps_walked", "at_location") }) add("sensors")
    }.distinct()

    fun sensorsOf(spec: ProcedureSpec): List<String> = buildList {
        add("camera"); add("clock")
        if (spec.steps.any { it.primitive == "steps_walked" }) add("step_counter")
        if (spec.steps.any { it.primitive == "at_location" }) add("location")
    }

    private fun splitClauses(s: String): List<String> =
        s.replace(Regex("\\s+(?:and then|then)\\s+", RegexOption.IGNORE_CASE), "|")
            .replace(Regex("[.;]\\s*"), "|")
            .replace(Regex(",\\s*(?=(?:show|open|close|remove|read|take|pull|only|after|at most|no |never)\\b)", RegexOption.IGNORE_CASE), "|")
            .split('|').map { it.trim() }.filter { it.isNotEmpty() }

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "item" }
}
