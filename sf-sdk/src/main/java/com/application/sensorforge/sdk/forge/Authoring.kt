package com.application.sensorforge.sdk.forge

import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Validator

/** Text-in, text-out local language model (M6). Implemented by a native runtime; Forge only. */
fun interface LlmBackend {
    fun complete(prompt: String, maxTokens: Int): String
}

/**
 * Sentence → spec authoring (tech spec §16.2, model stack M6). The LLM only drafts JSON; the
 * draft must parse and pass the contract validator, otherwise the deterministic template
 * compiler is used ("guided authoring"). Output is always an editable draft.
 */
object Authoring {
    enum class Source { LLM, TEMPLATE }

    data class Result(val spec: ProcedureSpec, val source: Source, val notes: List<String>)

    fun prompt(procedureId: String, sentence: String, version: Int): String = buildString {
        appendLine("You convert a procedure sentence into JSON for a verification spec. Output only one JSON object.")
        appendLine("Schema fields: schema_version, procedure_id, version, mode=\"verify\", parameters, entities[{id,cls,head,expected_code}], steps[{id,primitive,entity,container,state,observe_target,required}], constraints, prohibited, policy.")
        appendLine("Primitives: appears, state_is, state_change, exits_container, code_read.")
        appendLine("procedure_id=\"$procedureId\" version=$version")
        appendLine("Sentence: $sentence")
        append("JSON:")
    }

    /** First balanced {...} object in model output (models add prose or code fences). */
    fun extractJson(output: String): String? {
        val start = output.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until output.length) {
            val c = output[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> if (--depth == 0) return output.substring(start, i + 1)
            }
        }
        return null
    }

    fun author(procedureId: String, sentence: String, version: Int, llm: LlmBackend?): Result {
        val notes = mutableListOf<String>()
        if (llm != null) {
            val out = runCatching { llm.complete(prompt(procedureId, sentence, version), 768) }
                .onFailure { notes += "Local LLM failed (${it.message}); used the template compiler." }.getOrNull()
            if (out != null) {
                val json = extractJson(out)
                if (json == null) notes += "Local LLM returned no JSON; used the template compiler."
                else try {
                    val spec = Contracts.parseProcedure(json)
                    val errs = Validator.validate(spec)
                    if (errs.isEmpty()) return Result(spec, Source.LLM, listOf("Drafted by the local LLM; schema valid. Review before approving."))
                    notes += "Local LLM draft failed the contract (${errs.first()}); used the template compiler."
                } catch (e: Exception) { notes += "Local LLM JSON was invalid (${e.message?.take(80)}); used the template compiler." }
            }
        }
        val d = TemplateCompiler.compile(procedureId, sentence, version)
        return Result(d.spec, Source.TEMPLATE, notes + d.warnings)
    }
}
