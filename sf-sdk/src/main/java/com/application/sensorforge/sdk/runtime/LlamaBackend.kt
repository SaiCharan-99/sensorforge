package com.application.sensorforge.sdk.runtime

import android.content.Context
import android.net.Uri
import com.application.sensorforge.sdk.forge.LlmBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.nehuatl.llamacpp.LlamaHelper

/**
 * M6 runtime: SmolLM2-1.7B-Instruct Q4 GGUF through llama.cpp (CPU). Forge only; never created
 * by a verifier. [complete] blocks, so call it off the main thread. Latency goes to the backend log.
 */
class LlamaBackend private constructor(private val context: Context, private val model: java.io.File) : LlmBackend, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 256)
    private val helper = LlamaHelper(context.contentResolver, scope, events)
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val t0 = System.nanoTime()
        runBlocking {
            withTimeout(120_000) {
                helper.load(Uri.fromFile(model).toString(), 2048, null) {}
                events.first { it is LlamaHelper.LLMEvent.Loaded || it is LlamaHelper.LLMEvent.Error }
                    .let { if (it is LlamaHelper.LLMEvent.Error) error(it.message) }
            }
        }
        loaded = true
        BackendLog.record("m6_smollm2", "llama.cpp", "CPU", "CPU", "gguf q4_k_m", "load", (System.nanoTime() - t0) / 1e6)
    }

    @Synchronized
    override fun complete(prompt: String, maxTokens: Int): String {
        ensureLoaded()
        val t0 = System.nanoTime()
        val chat = "<|im_start|>system\nYou output only JSON.<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
        val text = runBlocking {
            withTimeout(180_000) {
                helper.predict(chat)
                when (val e = events.first { it is LlamaHelper.LLMEvent.Done || it is LlamaHelper.LLMEvent.Error }) {
                    is LlamaHelper.LLMEvent.Done -> e.fullText
                    is LlamaHelper.LLMEvent.Error -> error(e.message)
                    else -> ""
                }
            }
        }
        BackendLog.record("m6_smollm2", "llama.cpp", "CPU", "CPU", "prompt ${prompt.length} chars", "generate", (System.nanoTime() - t0) / 1e6)
        return text
    }

    override fun close() { runCatching { helper.release() }; scope.cancel() }

    companion object {
        /** Null unless the GGUF is installed with the pinned hash (registry says INSTALLED). */
        fun openIfInstalled(context: Context): LlamaBackend? {
            val reg = ModelRegistry.get(context)
            val st = reg.state("m6_smollm2")
            return if (st.status == com.application.sensorforge.sdk.models.ModelStatus.INSTALLED) LlamaBackend(context, reg.deviceFile("m6_smollm2")) else null
        }
    }
}
