package com.application.sensorforge.packcheck

import android.app.Application
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.sensorforge.sdk.PackInfo
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.sdk.TagSheet
import com.application.sensorforge.sdk.Verifier
import com.application.sensorforge.sdk.VerifierEvent
import com.application.sensorforge.sdk.VerifierResult
import com.application.sensorforge.sdk.perception.CoachingPrompt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import com.application.sensorforge.sdk.train.TrainingReport
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** PackCheck end app: uses only the public SDK API (`SensorForge.load/start/events/stop`). */
class PackCheckViewModel(app: Application) : AndroidViewModel(app) {
    enum class Phase { IDLE, LIVE, RESULT }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val pack: PackInfo? = null,
        val frame: VerifierEvent.Frame? = null,
        val live: VerifierEvent.Live? = null,
        val coaching: CoachingPrompt? = null,
        val backend: String? = null,
        val result: VerifierResult? = null,
        val error: String? = null,
        val head: HeadUi = HeadUi(),
    )

    /** Identity head (H1) card state. */
    data class HeadUi(
        val counts: Map<String, Pair<Int, Int>> = emptyMap(),
        val capturing: String? = null,
        val heldOut: Boolean = false,
        val report: TrainingReport? = null,
        val active: Int? = null,
        val message: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private var verifier: Verifier? = null
    private var collect: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val forge = SensorForge.identityForge(app, "packcheck", tagClass = "charger", classes = listOf("charger", "impostor_cable"))

    /** Which pack the Run screen verifies: a spec path / `.sfpkg` plus host parameters (e.g. start_time). */
    var packPath: String = PACK
        private set
    var title by androidx.compose.runtime.mutableStateOf("PackCheck")
        private set
    private var params: Map<String, Any> = emptyMap()

    fun configure(path: String, title: String, params: Map<String, Any>) {
        if (verifier != null) return
        packPath = path; this.title = title; this.params = params
        _ui.value = Ui(head = _ui.value.head)
    }

    private fun refreshHead(msg: String? = null) {
        val h = _ui.value.head
        _ui.value = _ui.value.copy(head = h.copy(counts = forge.counts(), active = forge.store.active(), message = msg ?: h.message))
    }

    fun setHeldOut(v: Boolean) { _ui.value = _ui.value.copy(head = _ui.value.head.copy(heldOut = v)) }

    fun capture(previewView: PreviewView, label: String) {
        if (verifier != null || _ui.value.head.capturing != null) return
        _ui.value = _ui.value.copy(head = _ui.value.head.copy(capturing = label, message = "Capturing '$label' for 4 s: keep its tag in view"))
        forge.capture(previewView, label, _ui.value.head.heldOut) { n ->
            _ui.value = _ui.value.copy(head = _ui.value.head.copy(capturing = null))
            refreshHead("Captured $n '$label' crops")
        }
    }

    fun train() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val (_, report) = forge.train()
                _ui.value = _ui.value.copy(head = _ui.value.head.copy(report = report))
                refreshHead("Trained v${report.headVersion}; approve to use it")
            } catch (e: Exception) { refreshHead("Train refused: ${e.message}") }
        }
    }

    fun approve() { _ui.value.head.report?.let { forge.approve(it.headVersion); refreshHead("v${it.headVersion} active") } }
    fun clearExamples() { forge.clearExamples(); refreshHead("Examples cleared") }

    init {
        refreshHead()
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
    }

    fun start(previewView: PreviewView) {
        if (verifier != null) return
        try {
            val v = SensorForge.load(getApplication(), packPath, params)
            verifier = v
            _ui.value = Ui(Phase.LIVE, pack = v.pack, head = _ui.value.head)
            collect = viewModelScope.launch {
                v.events().collect { e ->
                    when (e) {
                        is VerifierEvent.Frame -> _ui.value = _ui.value.copy(frame = e)
                        is VerifierEvent.Live -> _ui.value = _ui.value.copy(live = e, coaching = _ui.value.coaching?.takeIf { it.stepId == e.activeStepId })
                        is VerifierEvent.Coaching -> {
                            _ui.value = _ui.value.copy(coaching = e.prompt)
                            if (ttsReady) tts?.speak(e.prompt.message, TextToSpeech.QUEUE_FLUSH, null, e.prompt.stepId)
                        }
                        is VerifierEvent.Backend -> _ui.value = _ui.value.copy(backend = e.line)
                    }
                }
            }
            v.start(previewView) { r -> _ui.value = _ui.value.copy(phase = Phase.RESULT, result = r) }
        } catch (e: Exception) {
            verifier = null
            _ui.value = Ui(head = _ui.value.head, error = "${e::class.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        val v = verifier ?: return
        verifier = null
        tts?.stop()
        v.stop()
        collect?.cancel()
    }

    fun reset() { stop(); _ui.value = Ui(head = _ui.value.head) }

    /** Called when the app leaves the foreground: never keep the camera running unseen. */
    fun onHidden() { if (verifier != null) stop() }

    fun tags(): List<Pair<String, String>> = TagSheet.forPack(getApplication(), packPath)

    /** Writes one PNG per tag to app files (pull with adb, print at ≥ 4 cm). */
    fun exportTags(): File {
        val dir = File(getApplication<Application>().filesDir, "tags").apply { mkdirs() }
        tags().forEachIndexed { i, (label, payload) ->
            val name = "%02d_%s.png".format(i + 1, label.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_'))
            File(dir, name).outputStream().use { TagSheet.render(payload).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return dir
    }

    override fun onCleared() { stop(); tts?.shutdown() }

    companion object { const val PACK = "asset:specs/packcheck.spec.json" }
}
