package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthoringTest {
    private val sentence = "Show the sealed envelope, open it, remove the papers from the envelope."

    private fun validJson(): String {
        val spec = TemplateCompiler.compile("x", sentence).spec
        return Contracts.json.encodeToString(ProcedureSpec.serializer(), spec)
    }

    @Test fun noLlmUsesTemplate() {
        val r = Authoring.author("x", sentence, 1, null)
        assertEquals(Authoring.Source.TEMPLATE, r.source)
        assertEquals(3, r.spec.steps.size)
    }

    @Test fun validLlmJsonInsideProseAndFencesIsAccepted() {
        val r = Authoring.author("x", sentence, 1) { _, _ -> "Sure!\n```json\n${validJson()}\n```" }
        assertEquals(Authoring.Source.LLM, r.source)
    }

    @Test fun invalidOrMissingJsonFallsBack() {
        listOf("no json here", "{\"procedure_id\": ", "{\"a\":1}").forEach { bad ->
            val r = Authoring.author("x", sentence, 1) { _, _ -> bad }
            assertEquals(Authoring.Source.TEMPLATE, r.source)
            assert(r.notes.isNotEmpty())
        }
    }

    @Test fun llmFailureFallsBack() {
        val r = Authoring.author("x", sentence, 1) { _, _ -> error("oom") }
        assertEquals(Authoring.Source.TEMPLATE, r.source)
    }

    @Test fun extractJsonHandlesBracesInStrings() {
        assertEquals("{\"a\":\"}{\"}", Authoring.extractJson("x {\"a\":\"}{\"} y"))
        assertNull(Authoring.extractJson("{ unbalanced"))
        assertNotNull(Authoring.extractJson("{}"))
    }
}
