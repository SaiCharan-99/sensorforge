package com.application.sensorforge.sdk.runtime

import android.content.Context
import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Validator
import com.application.sensorforge.sdk.forge.TemplateCompiler
import com.application.sensorforge.sdk.forge.UseCaseDef
import com.application.sensorforge.sdk.forge.UseCases
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** A developer-forged verifier ("Forge new verifier", S01): a title plus the sentence it was compiled from. */
@Serializable
data class CustomUseCase(val id: String, val title: String, val sentence: String)

/**
 * Persistent custom use cases. Each gets the same guided flow as the built-in ones; its first spec is
 * the template-compiler draft (editable in Describe) and perception is tagged props (no trained head).
 */
object CustomUseCases {
    private fun dir(c: Context) = File(c.applicationContext.filesDir, "forge").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "custom.json")

    fun list(c: Context): List<CustomUseCase> = index(c).takeIf { it.exists() }
        ?.let { runCatching { Contracts.json.decodeFromString(ListSerializer(CustomUseCase.serializer()), it.readText()) }.getOrNull() }
        .orEmpty()

    fun shippedSpecFile(c: Context, id: String) = File(dir(c), "$id/shipped_spec.json")

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "verifier" }

    /** Result of creating a verifier: the new use case, or why the sentence can't be used. */
    sealed interface Created {
        data class Ok(val def: UseCaseDef, val warnings: List<String>) : Created
        data class Rejected(val reason: String) : Created
    }

    fun create(c: Context, title: String, sentence: String): Created {
        if (title.isBlank()) return Created.Rejected("Give the verifier a name.")
        val id = "custom_" + slug(title)
        if (UseCases.ALL.any { it.id == id } || list(c).any { it.id == id }) return Created.Rejected("A verifier named '$title' already exists.")
        val draft = TemplateCompiler.compile(id, sentence)
        if (draft.spec.steps.isEmpty()) return Created.Rejected("No steps could be read from the sentence. " + draft.warnings.joinToString(" "))
        val spec: ProcedureSpec = draft.spec
        val text = Contracts.json.encodeToString(ProcedureSpec.serializer(), spec)
        runCatching { Validator.requireValid(spec) }.onFailure { return Created.Rejected("The draft is not a valid spec: ${it.message}") }
        shippedSpecFile(c, id).apply { parentFile?.mkdirs() }.writeText(text)
        index(c).writeText(Contracts.json.encodeToString(ListSerializer(CustomUseCase.serializer()), list(c) + CustomUseCase(id, title.trim(), sentence)))
        return Created.Ok(defOf(c, CustomUseCase(id, title.trim(), sentence)), draft.warnings)
    }

    fun defOf(c: Context, u: CustomUseCase) = UseCaseDef(
        id = u.id, title = u.title, mode = "verify", specAsset = "file:" + shippedSpecFile(c, u.id).absolutePath,
        blurb = "Custom verifier: \"${u.sentence.take(90)}\"", exampleSentence = u.sentence,
        sensors = listOf("camera", "clock"), heads = emptyList(),
        models = listOf("m1_tags", "c1_quality", "c2_zxing", "c3_tracker"),
    )

    fun all(c: Context): List<UseCaseDef> = UseCases.ALL + list(c).map { defOf(c, it) }

    fun byId(c: Context, id: String): UseCaseDef = all(c).firstOrNull { it.id == id } ?: error("unknown use case $id")

    fun delete(c: Context, id: String) {
        if (list(c).none { it.id == id }) return
        File(dir(c), id).deleteRecursively()
        index(c).writeText(Contracts.json.encodeToString(ListSerializer(CustomUseCase.serializer()), list(c).filter { it.id != id }))
    }
}
