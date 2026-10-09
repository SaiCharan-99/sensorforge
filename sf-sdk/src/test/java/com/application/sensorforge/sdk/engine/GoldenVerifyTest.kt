package com.application.sensorforge.sdk.engine

import com.application.sensorforge.sdk.Fixtures
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Validator
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Golden verify scenarios (tech spec §25) from `fixtures/timelines`. */
class GoldenVerifyTest {

    private data class Golden(
        val name: String,
        val specPath: String,
        val expectedStack: String,
        val timeline: Timeline,
        val verdict: Verdict,
        val reasons: List<String>,
    )

    private val goldens: List<Golden> = File(Fixtures.root, "timelines")
        .listFiles { f -> f.extension == "json" }!!
        .sortedBy { it.name }
        .map { f ->
            val o = Contracts.json.parseToJsonElement(f.readText()).jsonObject
            val expected = o.getValue("expected").jsonObject
            Golden(
                name = o.getValue("name").jsonPrimitive.content,
                specPath = o.getValue("spec").jsonPrimitive.content,
                expectedStack = o.getValue("expected_perception_stack").jsonPrimitive.content,
                timeline = Contracts.json.decodeFromJsonElement(Timeline.serializer(), o.getValue("timeline")),
                verdict = Verdict.valueOf(expected.getValue("verdict").jsonPrimitive.content),
                reasons = expected.getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
            )
        }

    private fun run(g: Golden, timeline: Timeline = g.timeline): Decision {
        val spec = Contracts.parseProcedure(Fixtures.text(g.specPath))
        Validator.requireValid(spec)
        Validator.requireValid(timeline)
        return VerifyEngine.evaluate(spec, timeline, g.expectedStack)
    }

    @Test fun allThirteenSpecScenariosPresent() {
        assertTrue("expected >= 14 golden fixtures, found ${goldens.size}", goldens.size >= 14)
    }

    @Test fun goldenScenarios() {
        val failures = goldens.mapNotNull { g ->
            val d = run(g)
            val actual = d.reasons.map { "${it.code}:${it.ref}" }
            if (d.verdict != g.verdict || actual != g.reasons) {
                "${g.name}: expected ${g.verdict} ${g.reasons}, got ${d.verdict} $actual"
            } else null
        }
        assertEquals("", failures.joinToString("\n"))
    }

    @Test fun determinismUnderInputShuffling() {
        val rnd = Random(42)
        for (g in goldens) {
            val reference = run(g)
            repeat(20) {
                val shuffled = g.timeline.copy(
                    tracks = g.timeline.tracks.shuffled(rnd),
                    primitives = g.timeline.primitives.shuffled(rnd),
                    observability = g.timeline.observability.shuffled(rnd),
                )
                assertEquals(g.name, reference, run(g, shuffled))
            }
        }
    }

    @Test fun passHasNoReasonsAndAllStepsObserved() {
        goldens.filter { it.verdict == Verdict.PASS }.forEach { g ->
            val d = run(g)
            assertTrue(g.name, d.steps.all { it.status == StepStatus.OBSERVED && it.evidenceFrames.isNotEmpty() })
        }
    }
}
