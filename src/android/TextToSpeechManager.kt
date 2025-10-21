package com.powerednow.ai

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import android.util.Log
import java.util.Locale

class TextToSpeechManager(private val cordova: CordovaInterface) : TextToSpeech.OnInitListener {

    companion object { private const val TAG = "TextToSpeechManager" }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeakText: String? = null
    private var pendingSpeakCallback: CallbackContext? = null

    fun speak(text: String, callback: CallbackContext) {
        val t = text.trim()
        if (t.isEmpty()) {
            callback.error("Text to speak is required.")
            return
        }
        val activity = cordova.activity ?: run {
            callback.error("Activity unavailable.")
            return
        }

        activity.runOnUiThread {
            try {
                if (tts == null) {
                    pendingSpeakText = t
                    pendingSpeakCallback = callback
                    tts = TextToSpeech(activity, this).apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    }
                    return@runOnUiThread
                }

                if (!ttsReady) {
                    pendingSpeakText = t
                    pendingSpeakCallback = callback
                    return@runOnUiThread
                }

                speakNow(t)
                callback.success("Speaking started.")
            } catch (ex: Throwable) {
                callback.error("TTS error: ${ex.message}")
            }
        }
    }

    private fun speakNow(text: String) {
        try { tts?.stop() } catch (_: Throwable) {}
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt-${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        val activity = cordova.activity ?: return
        activity.runOnUiThread {
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.getDefault())
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.1f)
                tts?.setPitch(1.2f)

                if (!ttsReady) {
                    pendingSpeakCallback?.error("TTS language data missing or not supported.")
                    pendingSpeakText = null
                    pendingSpeakCallback = null
                    return@runOnUiThread
                }

                pendingSpeakText?.let { txt ->
                    speakNow(txt)
                    pendingSpeakCallback?.success("Speaking started.")
                }
            } else {
                ttsReady = false
                pendingSpeakCallback?.error("TTS initialization failed.")
            }
            pendingSpeakText = null
            pendingSpeakCallback = null
        }
    }

    fun onReset() {
        try { tts?.stop() } catch (_: Throwable) {}
    }

    fun onDestroy() {
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ttsReady = false
        pendingSpeakText = null
        pendingSpeakCallback = null
    }
}
