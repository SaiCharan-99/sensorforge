package com.application.sensorforge.sdk.engine

import com.application.sensorforge.sdk.Fixtures
import com.application.sensorforge.sdk.contracts.ContractException
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.EntityRef
import com.application.sensorforge.sdk.contracts.ObservabilityWindow
import com.application.sensorforge.sdk.contracts.Primitive
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.contracts.Track
import com.application.sensorforge.sdk.contracts.Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SealSafe (Part F) with AFTER_PARAM start_time, on synthetic timelines (SIMULATION). T-S02 logic. */
class SealSafeSpecTest {
    private val spec = Contracts.parseProcedure(Fixtures.text("specs/sealsafe.spec.json"))
    private val t0 = 1_760_000_000_000L
    private val start = mapOf("start_time" to listOf("${t0 + 10_000}")) // start = clip t 10 s

    private val tracks = listOf(Track("s", "seal", "i0", 0.0, 30.0), Track("p", "papers", "i1", 0.0, 30.0))
    private fun tl(vararg ps: Primitive, seen: Boolean = true) = Timeline(
        "synthetic", "test", 30.0, tracks, ps.toList(),
        if (seen) listOf(ObservabilityWindow("seal", listOf(listOf(0.0, 30.0))), ObservabilityWindow("envelope", listOf(listOf(0.0, 30.0)))) else emptyList(),
        startUtcMs = t0,
    )
    private fun intact(t: Double) = Primitive("a", "state_is", t, entity = EntityRef("s", "seal"), state = "intact", confidence = 0.9)
    private fun broken(t: Double) = Primitive("b", "state_change", t, entity = EntityRef("s", "seal"), state = "broken", confidence = 0.9)
    private fun papers(t: Double) = Primitive("c", "exits_container", t, entity = EntityRef("p", "papers"), container = "envelope", confidence = 0.9)

    @Test fun specIsValid() = Validator.requireValid(spec)

    @Test fun openedAfterStartPasses() =
        assertEquals(Verdict.PASS, VerifyEngine.evaluate(spec, tl(intact(2.0), broken(12.0), papers(15.0)), params = start).verdict)

    @Test fun openedBeforeStartIsOrderViolated() {
        val d = VerifyEngine.evaluate(spec, tl(intact(2.0), broken(5.0), papers(8.0)), params = start)
        assertEquals(Verdict.FAIL, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.ORDER_VIOLATED && it.ref == "seal_broken>start_time" })
    }

    @Test fun sealNeverShownIsUnverified() {
        val d = VerifyEngine.evaluate(spec, tl(broken(12.0), papers(15.0), seen = false), params = start)
        assertEquals(Verdict.UNVERIFIED, d.verdict)
        assertTrue(d.reasons.any { it.code == ReasonCode.REQUIRED_NOT_OBSERVABLE && it.ref == "seal_intact" })
    }

    @Test fun coveredLensIsUnverified() {
        val d = VerifyEngine.evaluate(spec, tl(seen = false), params = start)
        assertEquals(Verdict.UNVERIFIED, d.verdict)
    }

    @Test fun missingParamIsContractError() {
        assertThrows(ContractException::class.java) { VerifyEngine.evaluate(spec, tl(intact(2.0), broken(12.0))) }
    }

    @Test fun missingWallClockIsContractError() {
        assertThrows(ContractException::class.java) { VerifyEngine.evaluate(spec, tl(intact(2.0)).copy(startUtcMs = null), params = start) }
    }

    @Test fun afterParamOnNonTimeParamRejected() {
        val bad = spec.copy(parameters = mapOf("start_time" to spec.parameters.getValue("start_time").copy(type = "list<code>")))
        assertTrue(Validator.validate(bad).any { it.path.endsWith(".param") })
    }

    @Test fun roundTripsThroughJson() {
        val again = Contracts.parseProcedure(Contracts.json.encodeToString(com.application.sensorforge.sdk.contracts.ProcedureSpec.serializer(), spec))
        assertEquals(spec, again)
    }
}
