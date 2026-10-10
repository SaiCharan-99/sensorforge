package com.application.sensorforge.sdk.scene

import com.application.sensorforge.sdk.perception.BBox
import kotlinx.serialization.Serializable

/** One labelled thing in the frame. `source` says who named it: the trained head or only the detector's guess. */
data class SceneObject(val label: String, val box: BBox, val confidence: Double, val source: Source) {
    enum class Source { HEAD, DETECTOR }
}

/** What the developer wants true of the scene: `inside` items must be in the container, `outside` items must not. */
@Serializable
data class SceneRules(val container: String = "box", val inside: List<String> = emptyList(), val outside: List<String> = emptyList())

enum class Where { INSIDE, OUTSIDE, NOT_SEEN }

data class ItemState(val label: String, val expected: Where, val actual: Where, val overlap: Double?) {
    val ok get() = actual == expected
}

enum class Verdict { PASS, FAIL, UNVERIFIED }

data class SceneVerdict(val verdict: Verdict, val items: List<ItemState>, val reasons: List<String>)

/**
 * Deterministic scene judge (pure, rule 5: unknown by default). Geometry only: an object is
 * INSIDE when at least half of its box lies within the container's box. The container must be
 * seen; an expected item that is not seen is UNVERIFIED, never a pass.
 */
object SceneJudge {
    const val INSIDE_FRACTION = 0.5

    /** Fraction of [item]'s area that lies inside [container]. */
    fun overlap(item: BBox, container: BBox): Double {
        val iw = minOf(item.r, container.r) - maxOf(item.l, container.l)
        val ih = minOf(item.b, container.b) - maxOf(item.t, container.t)
        val area = (item.r - item.l) * (item.b - item.t)
        return if (iw <= 0 || ih <= 0 || area <= 0f) 0.0 else (iw * ih / area).toDouble()
    }

    fun judge(objects: List<SceneObject>, rules: SceneRules): SceneVerdict {
        val container = objects.filter { it.label == rules.container }.maxByOrNull { it.confidence }
            ?: return SceneVerdict(Verdict.UNVERIFIED, emptyList(), listOf("The container '${rules.container}' is not visible."))
        val items = mutableListOf<ItemState>()
        fun check(label: String, expected: Where) {
            val o = objects.filter { it.label == label }.maxByOrNull { it.confidence }
            if (o == null) { items += ItemState(label, expected, Where.NOT_SEEN, null); return }
            val ov = overlap(o.box, container.box)
            items += ItemState(label, expected, if (ov >= INSIDE_FRACTION) Where.INSIDE else Where.OUTSIDE, ov)
        }
        rules.inside.forEach { check(it, Where.INSIDE) }
        rules.outside.forEach { check(it, Where.OUTSIDE) }
        val reasons = items.mapNotNull {
            when {
                it.actual == Where.NOT_SEEN -> "'${it.label}' is not visible."
                !it.ok -> "'${it.label}' is ${it.actual.name.lowercase()} the ${rules.container}, expected ${it.expected.name.lowercase()}."
                else -> null
            }
        }
        val verdict = when {
            items.any { it.actual != Where.NOT_SEEN && !it.ok } -> Verdict.FAIL
            items.any { it.actual == Where.NOT_SEEN } -> Verdict.UNVERIFIED
            else -> Verdict.PASS
        }
        return SceneVerdict(verdict, items, reasons)
    }
}

/** Needs [window] consecutive identical frame verdicts before it reports one; otherwise UNVERIFIED (flicker is not evidence). */
class SceneSmoother(private val window: Int = 5) {
    private val recent = ArrayDeque<Verdict>()
    fun add(v: Verdict): Verdict {
        recent.addLast(v); while (recent.size > window) recent.removeFirst()
        return if (recent.size == window && recent.all { it == v }) v else Verdict.UNVERIFIED
    }
    fun reset() = recent.clear()
}

/** Text for the local LLM and the parser for its reply. The LLM is advisory; [SceneJudge] stays the verdict of record. */
object SceneNarrator {
    data class Opinion(val verdict: Verdict, val reason: String)

    fun describe(objects: List<SceneObject>, rules: SceneRules): String {
        val c = objects.filter { it.label == rules.container }.maxByOrNull { it.confidence }
        return buildString {
            appendLine("Image coordinates run 0..1 (x right, y down).")
            if (c == null) appendLine("The container '${rules.container}' was NOT detected.")
            else appendLine("container '${rules.container}': x ${f(c.box.l)}-${f(c.box.r)}, y ${f(c.box.t)}-${f(c.box.b)}")
            objects.filter { it.label != rules.container }.forEach {
                val ov = c?.let { cc -> SceneJudge.overlap(it.box, cc.box) }
                appendLine("object '${it.label}': x ${f(it.box.l)}-${f(it.box.r)}, y ${f(it.box.t)}-${f(it.box.b)}, confidence ${f(it.confidence.toFloat())}" + (ov?.let { o -> ", ${(o * 100).toInt()}% of it lies inside the container" } ?: ""))
            }
            rules.inside.filter { l -> objects.none { it.label == l } }.forEach { appendLine("expected '$it' was NOT detected.") }
            rules.outside.filter { l -> objects.none { it.label == l } }.forEach { appendLine("expected-outside '$it' was NOT detected.") }
        }
    }

    fun prompt(objects: List<SceneObject>, rules: SceneRules): String =
        "Rules: these must be INSIDE the ${rules.container}: ${rules.inside.joinToString().ifEmpty { "nothing" }}. " +
            "These must be OUTSIDE it: ${rules.outside.joinToString().ifEmpty { "nothing" }}.\n" +
            describe(objects, rules) +
            "Decide. Answer in two lines. Line 1: VERDICT: PASS or FAIL or UNVERIFIED (UNVERIFIED if something needed is not detected). Line 2: REASON: one short sentence."

    fun parse(text: String): Opinion? {
        val v = Regex("VERDICT:\\s*(PASS|FAIL|UNVERIFIED)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase() ?: return null
        val reason = Regex("REASON:\\s*(.+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return Opinion(Verdict.valueOf(v), reason)
    }

    private fun f(x: Float) = "%.2f".format(x)
}
