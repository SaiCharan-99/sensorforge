package com.application.sensorforge.sdk.contracts

import com.application.sensorforge.sdk.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ValidatorTest {

    private val packcheckText = Fixtures.text("specs/packcheck.spec.json")
    private val packcheck = Contracts.parseProcedure(packcheckText)
    private val crashguard = Contracts.parseDetect(Fixtures.text("specs/crashguard.detect.json"))

    private fun errorsOf(spec: ProcedureSpec, heads: Set<String>? = setOf("identity")) = Validator.validate(spec, heads)
    private fun assertError(errors: List<ContractError>, fragment: String) =
        assertTrue("expected error containing '$fragment', got $errors", errors.any { fragment in it.message })

    @Test fun specExamplesFromTechSpecAreValid() {
        assertEquals(emptyList<ContractError>(), errorsOf(packcheck))
        assertEquals(emptyList<ContractError>(), Validator.validate(crashguard, setOf("imu_risk")))
    }

    @Test fun rejectsUnknownKeys() {
        val bad = packcheckText.replaceFirst("\"version\": 3,", "\"version\": 3, \"surprise\": 1,")
        assertParseFails { Contracts.parseProcedure(bad) }
    }

    @Test fun rejectsUnknownConstraintType() {
        val bad = packcheckText.replaceFirst("\"type\": \"BEFORE\"", "\"type\": \"AFTER_ISH\"")
        assertParseFails { Contracts.parseProcedure(bad) }
    }

    @Test fun rejectsUnknownPrimitive() =
        assertError(errorsOf(packcheck.copy(steps = packcheck.steps + packcheck.steps[0].copy(id = "x", primitive = "teleports"))), "unknown primitive")

    @Test fun rejectsDetectOnlyPrimitiveInVerify() =
        assertError(errorsOf(packcheck.copy(steps = packcheck.steps + packcheck.steps[0].copy(id = "x", primitive = "impact_candidate"))), "detect-only")

    @Test fun rejectsDanglingEntity() =
        assertError(errorsOf(packcheck.copy(steps = packcheck.steps + packcheck.steps[1].copy(id = "x", entity = "ghost"))), "dangling entity")

    @Test fun rejectsDanglingConstraintRef() =
        assertError(errorsOf(packcheck.copy(constraints = packcheck.constraints + Before("box_opened", "nope"))), "dangling step")

    @Test fun rejectsDuplicateIds() {
        assertError(errorsOf(packcheck.copy(steps = packcheck.steps + packcheck.steps[0])), "duplicate step id")
        assertError(errorsOf(packcheck.copy(entities = packcheck.entities + packcheck.entities[0])), "duplicate entity id")
    }

    @Test fun rejectsConfidenceOutOfRange() {
        assertError(errorsOf(packcheck.copy(steps = listOf(packcheck.steps[0].copy(minConfidence = 1.5)) + packcheck.steps.drop(1))), "outside [0,1]")
        assertError(errorsOf(packcheck.copy(prohibited = listOf(ForbiddenClass("x", -0.1)))), "outside [0,1]")
    }

    @Test fun rejectsUnsupportedSchemaVersion() =
        assertError(errorsOf(packcheck.copy(schemaVersion = "2.0")), "unsupported schema version")

    @Test fun rejectsCyclicBefore() =
        assertError(errorsOf(packcheck.copy(constraints = packcheck.constraints + Before("box_empty", "adapter_out"))), "cyclic BEFORE")

    @Test fun rejectsMissingHead() = assertError(errorsOf(packcheck, heads = emptySet()), "head 'identity' is not in the pack")

    @Test fun rejectsAutoEscalateAndRealTransport() {
        assertError(Validator.validate(crashguard.copy(incident = crashguard.incident.copy(autoEscalate = true))), "auto_escalate")
        assertError(Validator.validate(crashguard.copy(incident = crashguard.incident.copy(transport = "sms"))), "mock")
    }

    @Test fun rejectsSensorsNotPermitted() {
        val m = PackManifest("3.0", "crashguard", 1, "detect", listOf("sensors"), listOf("accelerometer", "camera"), true, "x")
        assertError(Validator.validate(m), "not permitted in detect mode")
        assertError(Validator.validate(m.copy(mode = "verify", sensors = listOf("microphone"))), "not permitted")
    }

    @Test fun rejectsBadTimeline() {
        val t = Timeline("c", "s", 10.0, primitives = listOf(Primitive("p", "teleports", 1.0, confidence = 2.0)))
        val errors = Validator.validate(t)
        assertError(errors, "unknown primitive")
        assertError(errors, "outside [0,1]")
    }

    @Test fun expandsForEachSteps() {
        val spec = ProcedureSpec(
            schemaVersion = "3.0", procedureId = "handover", version = 1,
            parameters = mapOf("items" to ParameterDef("list<code>", "host_app", optional = true)),
            entities = listOf(EntityDef("A", "box", "track_continuity"), EntityDef("B", "box", "track_continuity")),
            steps = listOf(
                StepDef("{item}_out", "exits_container", entity = "{item}", observeTarget = "{item}", forEach = "items"),
                StepDef("done", "appears", entity = "A", observeTarget = "A"),
            ),
            constraints = listOf(Before("{item}_out", "done"), AnyOrder(listOf("{item}_out"))),
            policy = packcheck.policy,
        )
        val out = SpecExpander.expand(spec, mapOf("items" to listOf("A", "B")))
        assertEquals(listOf("A_out", "B_out", "done"), out.steps.map { it.id })
        assertEquals(listOf("A", "B", "A"), out.steps.map { it.entity })
        assertEquals(listOf(Before("A_out", "done"), Before("B_out", "done"), AnyOrder(listOf("A_out", "B_out"))), out.constraints)
        assertEquals(emptyList<ContractError>(), Validator.validate(out))
    }

    @Test fun requiredParameterMustBeSupplied() {
        val spec = packcheck.copy(parameters = mapOf("items" to ParameterDef("list<code>", "host_app", optional = false)))
        assertParseFails { SpecExpander.expand(spec, emptyMap()) }
    }

    private fun assertParseFails(block: () -> Unit) {
        try {
            block(); fail("expected ContractException")
        } catch (_: ContractException) {
        }
    }
}
