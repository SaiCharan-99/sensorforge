package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.Fixtures
import com.application.sensorforge.sdk.engine.Verdict
import com.application.sensorforge.sdk.models.ModelCatalog
import com.application.sensorforge.sdk.pack.Pack
import com.application.sensorforge.sdk.pack.Receipts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ForgeProjectTest {
    private val hashes = ModelCatalog.ENTRIES.associate { it.id to (it.expectedSha256 ?: ModelCatalog.builtinSha256(it)) }

    private fun project(def: UseCaseDef): ForgeProject {
        val dir = createTempDirectory("forge").toFile()
        return ForgeProject(dir, def) { Fixtures.text(def.specAsset) }
    }

    /** Whole Describe → Plan → Capture(sim) → Test → Export → Import run on the JVM, SIMULATION data only. */
    @Test fun fullFlowSealSafe() {
        val p = project(UseCases.SEALSAFE)
        assertEquals(0, p.specVersion())
        assertTrue(p.check(p.specText()).isEmpty())
        val v1 = p.approveSpec(p.specText())
        assertEquals(1, v1)

        val plan = p.resetPlan()
        assertFalse(plan.accepted)
        p.acceptPlan(); assertTrue(p.plan().accepted)

        // Capture (simulated) every RUN item: good ×5 dev + 1 held-out, others ×2.
        val runItems = plan.items.filter { it.capture == CaptureKind.RUN }
        for (it in runItems) {
            p.simulate(it.id, if (it.kind == PlanKind.GOOD) 5 else 2)
            if (it.kind == PlanKind.GOOD) p.simulate(it.id, 1, Split.HELD_OUT)
        }
        val prog = p.progress()
        assertTrue(prog.filter { it.item.capture == CaptureKind.RUN }.all { it.devMet })
        assertTrue(p.exampleFiles().all { it.simulation })

        val score = p.score()
        assertEquals(0, score.dev.falseAccepts); assertEquals(0, score.heldOut.falseAccepts); assertTrue(score.heldOut.n > 0)
        assertTrue(score.shippable)

        val cal = p.calibrate(); p.saveThresholds(cal)
        assertTrue(p.thresholds()!!.steps.isNotEmpty())

        val lock = ModelCatalog.lockOf(UseCases.SEALSAFE.models, hashes)
        val (file, manifest) = p.exportPack(emptyMap(), "{}", lock, TemplateCompiler.profileOf(p.procedure()), TemplateCompiler.sensorsOf(p.procedure()))
        assertTrue(file.exists())

        // Import: same bytes → same hash; changing one byte is rejected.
        val opened = Pack.open(file.readBytes(), hashes)
        assertEquals(manifest.packSha256, opened.sha256)
        assertEquals("sealsafe", opened.manifest.packId)
        val bad = file.readBytes().also { it[it.size / 3] = (it[it.size / 3].toInt() xor 1).toByte() }
        assertTrue(runCatching { Pack.open(bad, hashes) }.isFailure)
    }

    @Test fun planItemsCoverGoodBadUnclearForPackCheck() {
        val p = project(UseCases.PACKCHECK)
        val items = p.defaultPlan().items
        assertTrue(items.any { it.headClass == "impostor_cable" })
        assertTrue(items.filter { it.capture == CaptureKind.RUN }.all { it.expectedVerdict != null })
    }

    @Test fun specVersionsAreImmutableAndIncrement() {
        val p = project(UseCases.SEALSAFE)
        assertEquals(1, p.approveSpec(p.specText()))
        assertEquals(2, p.approveSpec(p.specText().replace("\"version\": 1", "\"version\": 2")))
        assertTrue(File(p.root, "spec_v1.json").exists() && File(p.root, "spec_v2.json").exists())
        assertTrue(p.check("{ not json").isNotEmpty())
        assertTrue(runCatching { p.approveSpec("{}") }.isFailure)
    }

    @Test fun simulateRefusesUnknownItemAndLabelsSimulation() {
        val p = project(UseCases.PACKCHECK)
        assertTrue(runCatching { p.simulate("nonsense", 1) }.isFailure)
        val ex = p.simulate("missing_adapter_out", 2)
        assertTrue(ex.all { it.simulation && it.label == ExampleLabel.INVALID && it.missingSteps == listOf("adapter_out") })
    }

    @Test fun detectProjectLoadsAndExports() {
        val p = project(UseCases.CRASHGUARD)
        assertTrue(p.check(p.specText()).isEmpty())
        assertTrue(p.defaultPlan().items.any { it.headClass == "impact_like" && it.minCount == 0 })
        val (file, m) = p.exportPack(emptyMap(), "{}", ModelCatalog.lockOf(emptyList(), hashes), listOf("sensors"), listOf("accelerometer", "gyroscope"))
        assertEquals("detect", Pack.open(file.readBytes(), hashes).manifest.mode)
        assertNotEquals("", m.packSha256)
    }

    @Test fun receiptForSimulatedRunVerifies() {
        val p = project(UseCases.SEALSAFE)
        val ex = p.simulate("order_swap_seal_intact_seal_broken", 1).first()
        val d = Scoring.evaluate(p.procedure(), ex.toExample())
        assertEquals(Verdict.FAIL, d.verdict)
        val r = com.application.sensorforge.sdk.pack.ReceiptInput("sealsafe", 1, "a".repeat(64), "simulation", emptyMap(), d.verdict.name, null,
            d.reasons.map { Triple(it.code.name, it.ref, it.message) }, d.steps.map { it.id to it.status.name }, null, null, "2026-10-10T00:00:00Z", 1, "sim", true)
        assertTrue(Receipts.verify(Receipts.create(r)).valid)
    }
}
