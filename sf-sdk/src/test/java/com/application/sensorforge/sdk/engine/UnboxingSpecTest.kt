package com.application.sensorforge.sdk.engine

import com.application.sensorforge.sdk.Fixtures
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.EntityRef
import com.application.sensorforge.sdk.contracts.ObservabilityWindow
import com.application.sensorforge.sdk.contracts.Primitive
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Track
import com.application.sensorforge.sdk.contracts.Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unboxing procedure (seal → open → envelope → phone → cable → film → power on) on synthetic timelines. */
class UnboxingSpecTest {
    private val spec = Contracts.parseProcedure(Fixtures.text("specs/unboxing.spec.json"))

    private val tracks = listOf("box", "seal", "envelope", "phone", "cable").mapIndexed { i, c -> Track("t$i", c, "id$i", 0.0, 30.0) }
    private fun ref(cls: String) = EntityRef(tracks.first { it.cls == cls }.trackId, cls)

    private fun timeline(vararg events: Pair<Double, Primitive>) = Timeline(
        "synthetic", "test", 30.0, tracks,
        events.mapIndexed { i, (t, p) -> p.copy(id = "p$i", t = t) },
        listOf(ObservabilityWindow("box", listOf(listOf(0.0, 30.0))), ObservabilityWindow("phone", listOf(listOf(0.0, 30.0)))),
    )

    private fun state(cls: String, s: String) = Primitive("", "state_change", 0.0, entity = ref(cls), state = s, confidence = 0.9)
    private fun out(cls: String) = Primitive("", "exits_container", 0.0, entity = ref(cls), container = "box", confidence = 0.9)

    private val inOrder = arrayOf(
        2.0 to state("seal", "broken"), 4.0 to state("box", "open"), 6.0 to out("envelope"),
        9.0 to out("phone"), 12.0 to out("cable"), 15.0 to state("phone", "unsealed"), 20.0 to state("phone", "on"),
    )

    @Test fun specIsValid() = Validator.requireValid(spec)

    @Test fun fullUnboxingPasses() {
        val tl = timeline(*inOrder); Validator.requireValid(tl)
        assertEquals(Verdict.PASS, VerifyEngine.evaluate(spec, tl).verdict)
    }

    @Test fun phoneBeforeEnvelopeIsOrderViolated() {
        val swapped = inOrder.map { (t, p) -> when (p.entity?.cls) { "envelope" -> 9.0 to p; "phone" -> if (p.type == "exits_container") 6.0 to p else t to p; else -> t to p } }
        val d = VerifyEngine.evaluate(spec, timeline(*swapped.toTypedArray()))
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.ORDER_VIOLATED && it.ref == "envelope_out->phone_out" })
    }

    @Test fun cableNeverTakenOutIsAbsent() {
        val d = VerifyEngine.evaluate(spec, timeline(*inOrder.filter { it.second.entity?.cls != "cable" }.toTypedArray()))
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.REQUIRED_ABSENT && it.ref == "cable_out" })
    }
}
