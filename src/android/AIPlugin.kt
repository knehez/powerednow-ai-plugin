package com.powerednow.ai

import android.util.Log
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.json.JSONArray
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class AIPlugin : CordovaPlugin() {

    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var recognitionIntent: android.content.Intent? = null
    private var transcriptCallback: CallbackContext? = null
    private var hasFinished = false
    private var latestPartial: String? = null
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var silenceRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    private val silenceTimeoutMs = 1500L
    private val hardTimeoutMs    = 15000L

    private val REQUEST_RECORD_AUDIO = 1001
    private val RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO

    companion object {
        private const val TAG = "AIPlugin"
    }

    override fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        return when (action) {
            "ask" -> {
                ask(args, callbackContext)
                true
            }
            "transcript" -> {
                transcript(args, callbackContext)
                true
            }
            "speak" -> {
                speak(args, callbackContext)
                true
            }
            else -> false
        }
    }

    private fun transcript(args: JSONArray, callbackContext: CallbackContext) {
        Log.d(TAG, "transcript() called")
        if (transcriptCallback != null) {
            // már fut egy felismerés – egyszerre egyet engedjünk
            Log.w(TAG, "transcript already in progress; ignoring")
            callbackContext.error("Recognition already in progress.")
            return
        }
        transcriptCallback = callbackContext
        // jogosultság ellenőrzés
        if (!hasAudioPermission()) {
            requestAudioPermission()
            return
        }
        startRecognition()
    }

    private fun ask(args: JSONArray, callbackContext: CallbackContext) {
        val question = args.optString(0, "")
        Log.d(TAG, "ask() called with question='$question'")
        // később ide jöhet a feldolgozás, most csak log
        callbackContext.success() // üres OK válasz
    }

    private fun speak(args: JSONArray, callbackContext: CallbackContext) {
        val text = args.optString(0, "")
        Log.d(TAG, "speak() called with text='$text'")
        // később ide jöhet TTS logika, most csak log
        callbackContext.success()
    }

    private fun hasAudioPermission(): Boolean {
        val ctx = cordova.activity ?: return false
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val activity = cordova.activity ?: run {
            failOnce("Activity unavailable")
            return
        }
        androidx.core.app.ActivityCompat.requestPermissions(
            activity, arrayOf(RECORD_AUDIO), REQUEST_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startRecognition()
            } else {
                failOnce("Microphone permission denied.")
            }
        }
    }

    private fun startRecognition() {
        val activity = cordova.activity ?: run {
            failOnce("Activity unavailable")
            return
        }

        Log.d(TAG, "startRecognition()")

        // Minden, ami SpeechRecognizer-rel kapcsolatos, fusson a főszálon!
        cordova.activity.runOnUiThread {
            try {
                cleanupRecognizer()

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).also { sr ->
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "onReadyForSpeech")
                        }

                        override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }
                        override fun onError(error: Int) {
                            Log.w(TAG, "onError: $error")
                            val partial = latestPartial?.trim().orEmpty()
                            if (partial.isNotEmpty()) successOnce(partial)
                            else failOnce("Speech error: $error")
                        }
                        override fun onResults(results: Bundle) {
                            val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull()?.trim().orEmpty()
                            Log.d(TAG, "onResults: '$text'")
                            successOnce(text)
                        }
                        override fun onPartialResults(partialResults: Bundle) {
                            val list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull()?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                latestPartial = text
                                Log.d(TAG, "onPartialResults: '$text'")
                                scheduleSilenceFinish()
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }

                Log.d(TAG, "startListening() called")
                speechRecognizer?.startListening(recognitionIntent)

                // hard timeout
                timeoutRunnable?.let { handler.removeCallbacks(it) }

                val runnable = Runnable {
                    Log.w(TAG, "hard timeout reached")
                    val text = latestPartial?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        successOnce(text)
                    } else {
                        failOnce("Timeout before final recognition.")
                    }
                }
                timeoutRunnable = runnable
                handler.postDelayed(runnable, hardTimeoutMs)
            } catch (t: Throwable) {
                failOnce("Failed to start recognition: ${t.message}")
            }
        }
    }

    private fun stopRecognition() {
        try { speechRecognizer?.stopListening() } catch (_: Throwable) {}
        cleanupRecognizer()
    }

    private fun cleanupRecognizer() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = null
        timeoutRunnable = null

        try { speechRecognizer?.cancel() } catch (_: Throwable) {}
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        speechRecognizer = null
    }

    private fun scheduleSilenceFinish() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            val text = latestPartial?.trim().orEmpty()
            Log.d(TAG, "silence timeout, finishing with partial: '$text'")
            successOnce(text)
        }.also { handler.postDelayed(it, silenceTimeoutMs) }
    }

    private fun successOnce(text: String) {
        if (hasFinished) return
        hasFinished = true
        cleanupRecognizer()
        val cb = transcriptCallback
        transcriptCallback = null
        cb?.success(text)
    }

    private fun failOnce(message: String) {
        if (hasFinished) return
        hasFinished = true
        cleanupRecognizer()
        val cb = transcriptCallback
        transcriptCallback = null
        cb?.error(message)
    }

    override fun onReset() {
        super.onReset()
        stopRecognition()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecognition()
    }
}
