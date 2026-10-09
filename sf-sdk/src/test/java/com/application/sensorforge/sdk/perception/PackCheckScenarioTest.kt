package com.application.sensorforge.sdk.perception

import com.application.sensorforge.sdk.Fixtures
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.engine.Decision
import com.application.sensorforge.sdk.engine.StepStatus
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.engine.VerifyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic tagged-prop scenes (SIMULATION) through the real tracker, extractor and engine.
 * They check perception → timeline → verdict logic for T-B01–T-B05; the on-device runs are separate.
 */
class PackCheckScenarioTest {
    private val spec = Contracts.parseProcedure(Fixtures.text("specs/packcheck.spec.json"))

    private val zone = "box_interior"
    private val corners = mapOf(0 to Pt(.2f, .3f), 1 to Pt(.8f, .3f), 2 to Pt(.8f, .8f), 3 to Pt(.2f, .8f))
    private val IN_A = Pt(.4f, .55f)
    private val IN_B = Pt(.6f, .55f)
    private val OUT_A = Pt(.3f, .12f)
    private val OUT_B = Pt(.7f, .12f)

    /** Linear move from a to b over [t0, t1]. */
    private fun move(a: Pt, b: Pt, t0: Double, t1: Double, t: Double): Pt {
        val f = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0).toFloat()
        return Pt(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    }

    private data class Item(val cls: String, val at: Pt, val code: String? = null)

    /** Builds a scene at 10 fps; `frame(t)` describes what is readable at time t. */
    private inner class Scene {
        val builder = TimelineBuilder("test", targetsOf = { cls -> spec.entities.filter { it.cls == cls }.map { it.id } })
        private var i = 0L
        fun run(from: Double, to: Double, cornersOn: Boolean = true, floor: Boolean = false,
                state: String? = null, sharp: Boolean = true, items: (Double) -> List<Item> = { emptyList() }) {
            var t = from
            while (t < to - 1e-9) {
                val dets = items(t).map { Detection(it.cls, BBox(it.at.x - .03f, it.at.y - .03f, it.at.x + .03f, it.at.y + .03f), 0.99, it.code) }
                builder.add(
                    FrameObservation(
                        (t * 1e9).toLong(), i++, dets,
                        zoneCorners = if (cornersOn) mapOf(zone to corners) else emptyMap(),
                        floors = if (floor) setOf(zone) else emptySet(),
                        states = listOfNotNull(state?.let { "box" to it }),
                        quality = FrameQuality(120.0, if (sharp) 50.0 else 1.0),
                    )
                )
                t += 0.1
            }
        }
        fun decide(): Decision {
            val tl = builder.timeline(close = true)
            Validator.requireValid(tl)
            return VerifyEngine.evaluate(spec, tl, Tags.STACK)
        }
    }

    /** Closed box, then opened with charger + adapter inside. */
    private fun Scene.openWithItems(adapterCode: String? = "AD-42") {
        run(0.0, 1.0, state = "closed")
        run(1.0, 2.0, state = "open") { listOf(Item("charger", IN_A), Item("adapter", IN_B, adapterCode)) }
    }

    private fun Scene.unpackAll() {
        run(2.0, 3.0, state = "open") { listOf(Item("charger", move(IN_A, OUT_A, 2.0, 2.5, it)), Item("adapter", IN_B, "AD-42")) }
        run(3.0, 4.0, floor = true, state = "open") { listOf(Item("charger", OUT_A), Item("adapter", move(IN_B, OUT_B, 3.0, 3.5, it), "AD-42")) }
        run(4.0, 6.0, floor = true, state = "open") { listOf(Item("charger", OUT_A), Item("adapter", OUT_B, "AD-42")) }
    }

    private fun Decision.codes() = reasons.map { "${it.code}:${it.ref}" }

    @Test fun validUnpackPasses() {  // T-B02
        val s = Scene(); s.openWithItems(); s.unpackAll()
        val d = s.decide()
        assertEquals(d.codes().toString(), Verdict.PASS, d.verdict)
        assertTrue(d.steps.all { it.status == StepStatus.OBSERVED })
        assertTrue(d.steps.first { it.id == "charger_out" }.evidenceFrames.isNotEmpty())
    }

    @Test fun missingAdapterFailsRequiredAbsent() {  // T-B01
        val s = Scene(); s.openWithItems()
        s.run(2.0, 6.0, state = "open") { listOf(Item("charger", move(IN_A, OUT_A, 2.0, 2.5, it)), Item("adapter", IN_B, "AD-42")) }
        val d = s.decide()
        assertEquals(d.codes().toString() + d.steps, Verdict.FAIL, d.verdict)
        assertTrue(d.codes().toString(), "REQUIRED_ABSENT:adapter_out" in d.codes())
    }

    @Test fun duplicateAdapterIsProhibited() {  // T-B03 duplicate
        val s = Scene(); s.openWithItems()
        s.run(2.0, 3.0, state = "open") { listOf(Item("charger", OUT_A), Item("adapter", IN_B, "AD-42"), Item("adapter", IN_A, "AD-43")) }
        s.unpackAll()
        val d = s.decide()
        assertEquals(d.codes().toString() + d.steps, Verdict.FAIL, d.verdict)
        assertTrue(d.codes().toString(), "PROHIBITED_OBSERVED:adapter" in d.codes())
    }

    @Test fun impostorIsProhibited() {  // T-B03 impostor
        val s = Scene(); s.openWithItems()
        s.run(2.0, 3.0, state = "open") { listOf(Item("impostor_cable", OUT_A), Item("adapter", IN_B, "AD-42")) }
        s.unpackAll()
        val d = s.decide()
        assertEquals(d.codes().toString() + d.steps, Verdict.FAIL, d.verdict)
        assertTrue(d.codes().toString(), "PROHIBITED_OBSERVED:impostor_cable" in d.codes())
    }

    @Test fun coveredContentsIsUnverifiedAndCoached() {  // T-B04
        val s = Scene(); s.openWithItems()
        // A hand covers the box interior: corners stay visible, contents and floor don't.
        s.run(2.0, 6.0, state = "open")
        val live = VerifyEngine.evaluate(spec, s.builder.timeline(), Tags.STACK)
        val prompt = Coach(spec).check(live, s.builder.nowS) { s.builder.lastObservableAt(it) }
        assertNotNull(prompt)
        assertEquals("charger_out", prompt!!.stepId)
        val d = s.decide()
        assertEquals(d.codes().toString(), Verdict.UNVERIFIED, d.verdict)
        assertTrue("REQUIRED_NOT_OBSERVABLE:charger_out" in d.codes())
        assertTrue(d.codes().none { it.startsWith("REQUIRED_ABSENT") })
    }

    @Test fun noCoachingWhileTargetVisible() {
        val s = Scene(); s.openWithItems()
        val live = VerifyEngine.evaluate(spec, s.builder.timeline(), Tags.STACK)
        assertNull(Coach(spec).check(live, s.builder.nowS) { s.builder.lastObservableAt(it) })
    }

    @Test fun lookAlikeReturningIsIdentityUnproven() {  // T-B05
        val s = Scene(); s.openWithItems(adapterCode = null)
        // Adapter leaves the box with its label unread, leaves the frame, a similar one comes back and is read.
        s.run(2.0, 3.0, floor = true, state = "open") { listOf(Item("charger", move(IN_A, OUT_A, 2.0, 2.5, it)), Item("adapter", move(IN_B, OUT_B, 2.0, 2.5, it))) }
        s.run(3.0, 4.5, floor = true, state = "open") { listOf(Item("charger", OUT_A)) }
        s.run(4.5, 7.0, floor = true, state = "open") { listOf(Item("charger", OUT_A), Item("adapter", OUT_B, "AD-42")) }
        val d = s.decide()
        assertEquals(d.codes().toString(), Verdict.UNVERIFIED, d.verdict)
        assertTrue(d.codes().toString(), "IDENTITY_UNPROVEN:adapter_out=adapter_label" in d.codes())
    }

    @Test fun labelReadAfterReturnWithSameCodeKeepsIdentity() {
        val s = Scene(); s.openWithItems()
        s.run(2.0, 3.0, floor = true, state = "open") { listOf(Item("charger", move(IN_A, OUT_A, 2.0, 2.5, it)), Item("adapter", move(IN_B, OUT_B, 2.0, 2.5, it), "AD-42")) }
        s.run(3.0, 4.5, floor = true, state = "open") { listOf(Item("charger", OUT_A)) }
        s.run(4.5, 7.0, floor = true, state = "open") { listOf(Item("charger", OUT_A), Item("adapter", OUT_B, "AD-42")) }
        s.decide().let { assertEquals(it.codes().toString() + it.steps, Verdict.PASS, it.verdict) }
    }

    @Test fun alreadyOpenBoxNeverOpened() {
        val s = Scene()
        s.run(0.0, 2.0, state = "open") { listOf(Item("charger", IN_A), Item("adapter", IN_B, "AD-42")) }
        s.unpackAll()
        val d = s.decide()
        assertEquals(StepStatus.ABSENT, d.steps.first { it.id == "box_opened" }.status)
    }

    @Test fun blurredFramesAreCameraUnstable() {
        val s = Scene(); s.openWithItems()
        s.run(2.0, 3.0, sharp = false) { listOf(Item("charger", OUT_A), Item("adapter", OUT_B, "AD-42")) }
        s.run(3.0, 4.0, state = "open") { listOf(Item("charger", IN_A), Item("adapter", IN_B, "AD-42")) }
        val tl = s.builder.timeline(close = true)
        val u = tl.primitives.single { it.type == "camera_unstable" }
        assertEquals(2.0, u.t, 1e-6)
        assertEquals(3.0, u.tEnd!!, 1e-6)
        assertTrue(tl.primitives.none { it.type == "exits_container" })
    }

    @Test fun deterministic() {
        fun once() = Scene().also { it.openWithItems(); it.unpackAll() }.builder.timeline(close = true)
        assertEquals(once(), once())
    }

    @Test fun tagParsing() {
        assertEquals(Tag.Obj("adapter", "AD-42"), Tags.parse(Tags.obj("adapter", "AD-42")))
        assertEquals(Tag.Obj("charger", null), Tags.parse(Tags.obj("charger")))
        assertEquals(Tag.ZoneCorner("box_interior", 2), Tags.parse(Tags.zoneCorner("box_interior", 2)))
        assertEquals(Tag.Floor("box_interior"), Tags.parse(Tags.floor("box_interior")))
        assertEquals(Tag.State("box", "open"), Tags.parse(Tags.state("box", "open")))
        assertNull(Tags.parse("https://example.com"))
        assertNull(Tags.parse("SF1|ZONE|box_interior|7"))
    }
}
