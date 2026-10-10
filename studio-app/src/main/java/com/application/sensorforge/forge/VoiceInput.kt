package com.application.sensorforge.forge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** M7: one-shot on-device speech to text for Forge Describe. Foreground, user-triggered; audio is never stored. */
class VoiceInput(private val ctx: Context, private val onText: (String) -> Unit, private val onState: (String) -> Unit) {
    private var rec: SpeechRecognizer? = null

    val available get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    fun start() {
        if (!available) { onState("No on-device recognizer. Type the sentence instead."); return }
        stop()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
                onState("Heard. Edit the text if needed."); stop()
            }
            override fun onError(error: Int) { onState("Speech not recognised (code $error). Is the offline language pack installed? Type instead."); stop() }
            override fun onReadyForSpeech(params: Bundle?) = onState("Listening…")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    fun stop() { rec?.destroy(); rec = null }
}
